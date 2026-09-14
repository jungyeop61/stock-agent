"""FastAPI entrypoint and runtime dependency wiring."""

from collections.abc import AsyncIterator
from contextlib import AsyncExitStack, asynccontextmanager
from typing import Any
from uuid import UUID

from fastapi import FastAPI, Request
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from pydantic import BaseModel

from jusika_agent.config import CheckpointProvider, CommandInterpreterProvider, Settings
from jusika_agent.graph import SpringGateway, build_agent_graph
from jusika_agent.interpreters import (
    CommandInterpreter,
    OpenAICommandInterpreter,
    RuleBasedCommandInterpreter,
)
from jusika_agent.models import AgentMessageRequest, AgentTurnResponse
from jusika_agent.service import AgentService
from jusika_agent.spring_client import SpringBackendClient


class HealthResponse(BaseModel):
    status: str
    environment: str
    broker_mutation_mode: str


def create_app(
    *,
    settings: Settings | None = None,
    interpreter: CommandInterpreter | None = None,
    spring: SpringGateway | None = None,
    checkpointer: Any | None = None,
) -> FastAPI:
    """Create an app with overridable boundaries for deterministic tests."""

    resolved_settings = settings or Settings()
    resolved_interpreter = interpreter or _create_interpreter(resolved_settings)
    owns_spring = spring is None
    resolved_spring = spring or SpringBackendClient(
        base_url=resolved_settings.spring_backend_url,
        read_api_key=resolved_settings.spring_read_api_key.get_secret_value(),
        order_api_key=resolved_settings.spring_order_api_key.get_secret_value(),
        connect_timeout_seconds=resolved_settings.spring_connect_timeout_seconds,
        read_timeout_seconds=resolved_settings.spring_read_timeout_seconds,
    )

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        async with AsyncExitStack() as stack:
            resolved_checkpointer = checkpointer
            if resolved_checkpointer is None:
                resolved_checkpointer = await _create_checkpointer(resolved_settings, stack)
            graph = build_agent_graph(
                interpreter=resolved_interpreter,
                spring=resolved_spring,
                checkpointer=resolved_checkpointer,
            )
            application.state.agent_service = AgentService(graph)
            yield
        if owns_spring and isinstance(resolved_spring, SpringBackendClient):
            await resolved_spring.aclose()

    application = FastAPI(
        title="Jusika Python Agent",
        version="0.1.0",
        lifespan=lifespan,
    )

    @application.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(
            status="UP",
            environment=resolved_settings.environment,
            broker_mutation_mode="SPRING_CONTROLLED",
        )

    @application.post(
        "/api/agent/sessions/{session_id}/messages",
        response_model=AgentTurnResponse,
    )
    async def process_message(
        session_id: UUID,
        body: AgentMessageRequest,
        request: Request,
    ) -> AgentTurnResponse:
        service: AgentService = request.app.state.agent_service
        return await service.process_message(session_id=str(session_id), text=body.text)

    return application


def _create_interpreter(settings: Settings) -> CommandInterpreter:
    if settings.command_interpreter is CommandInterpreterProvider.OPENAI:
        return OpenAICommandInterpreter(
            api_key=settings.openai_api_key.get_secret_value(),
            model=settings.openai_model,
        )
    return RuleBasedCommandInterpreter()


async def _create_checkpointer(settings: Settings, stack: AsyncExitStack) -> Any:
    if settings.checkpoint_provider is CheckpointProvider.POSTGRES:
        connection_string = settings.checkpoint_database_url.get_secret_value()
        if not connection_string:
            raise RuntimeError(
                "PostgreSQL 체크포인터를 사용하려면 "
                "JUSIKA_AGENT_CHECKPOINT_DATABASE_URL을 설정해야 합니다."
            )
        saver = await stack.enter_async_context(
            AsyncPostgresSaver.from_conn_string(connection_string)
        )
        await saver.setup()
        return saver
    return InMemorySaver()


app = create_app()

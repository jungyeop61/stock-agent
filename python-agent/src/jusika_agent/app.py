"""FastAPI entrypoint and runtime dependency wiring."""

import logging
from collections.abc import AsyncIterator
from contextlib import AsyncExitStack, asynccontextmanager
from time import perf_counter
from typing import Any
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request, Response
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from pydantic import BaseModel
from starlette.middleware.base import RequestResponseEndpoint

from jusika_agent.concurrency import (
    InMemorySessionConcurrencyGuard,
    PostgresSessionConcurrencyGuard,
    SessionConcurrencyGuard,
)
from jusika_agent.config import CheckpointProvider, CommandInterpreterProvider, Settings
from jusika_agent.graph import SpringGateway, build_agent_graph
from jusika_agent.interpreters import (
    CommandInterpreter,
    OpenAICommandInterpreter,
    RuleBasedCommandInterpreter,
)
from jusika_agent.mobile_security import MobileSecurityMiddleware, owned_session
from jusika_agent.models import AgentMessageRequest, AgentTurnResponse, AgentVoiceMessageRequest
from jusika_agent.observability import (
    bind_request_id,
    configure_logging,
    current_request_id,
    log_event,
    reset_request_id,
)
from jusika_agent.readiness import ReadinessService
from jusika_agent.service import AgentService
from jusika_agent.spring_client import SpringBackendClient
from jusika_agent.transcription import OpenAISpeechTranscriber, SpeechTranscriber


class HealthResponse(BaseModel):
    status: str
    environment: str
    broker_mutation_mode: str


class ReadinessResponse(BaseModel):
    status: str
    components: dict[str, str]


class TranscriptionResponse(BaseModel):
    text: str


def create_app(
    *,
    settings: Settings | None = None,
    interpreter: CommandInterpreter | None = None,
    spring: SpringGateway | None = None,
    checkpointer: Any | None = None,
    session_guard: SessionConcurrencyGuard | None = None,
    transcriber: SpeechTranscriber | None = None,
) -> FastAPI:
    """Create an app with overridable boundaries for deterministic tests."""

    resolved_settings = settings or Settings()
    configure_logging(resolved_settings.log_level)
    logger = logging.getLogger("jusika_agent.app")
    owns_interpreter = interpreter is None
    resolved_interpreter = interpreter or _create_interpreter(resolved_settings)
    owns_spring = spring is None
    owns_transcriber = transcriber is None
    resolved_transcriber = transcriber
    if resolved_transcriber is None and resolved_settings.openai_api_key.get_secret_value():
        resolved_transcriber = OpenAISpeechTranscriber(
            api_key=resolved_settings.openai_api_key.get_secret_value(),
            model=resolved_settings.openai_transcription_model,
            timeout_seconds=resolved_settings.openai_transcription_timeout_seconds,
        )
    resolved_spring = spring or SpringBackendClient(
        base_url=resolved_settings.spring_backend_url,
        read_api_key=resolved_settings.spring_read_api_key.get_secret_value(),
        order_api_key=resolved_settings.spring_order_api_key.get_secret_value(),
        connect_timeout_seconds=resolved_settings.spring_connect_timeout_seconds,
        read_timeout_seconds=resolved_settings.spring_read_timeout_seconds,
        read_max_attempts=resolved_settings.spring_read_max_attempts,
        retry_base_delay_seconds=resolved_settings.spring_retry_base_delay_seconds,
    )

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        async with AsyncExitStack() as stack:
            resolved_checkpointer = checkpointer
            if resolved_checkpointer is None:
                resolved_checkpointer = await _create_checkpointer(resolved_settings, stack)
            resolved_session_guard = session_guard
            if resolved_session_guard is None:
                resolved_session_guard = await _create_session_guard(resolved_settings, stack)
            graph = build_agent_graph(
                interpreter=resolved_interpreter,
                spring=resolved_spring,
                checkpointer=resolved_checkpointer,
            )
            application.state.agent_service = AgentService(
                graph,
                session_guard=resolved_session_guard,
                voice_safety_check=resolved_spring.check_voice_safety,
            )
            application.state.readiness_service = ReadinessService(
                spring=resolved_spring,
                checkpointer=resolved_checkpointer,
                checkpoint_provider=resolved_settings.checkpoint_provider,
                interpreter_provider=resolved_settings.command_interpreter,
                timeout_seconds=resolved_settings.readiness_timeout_seconds,
            )
            yield
            application.state.readiness_service = None
        if owns_spring and isinstance(resolved_spring, SpringBackendClient):
            await resolved_spring.aclose()
        if owns_interpreter and isinstance(resolved_interpreter, OpenAICommandInterpreter):
            await resolved_interpreter.aclose()
        if owns_transcriber and resolved_transcriber is not None:
            await resolved_transcriber.aclose()

    application = FastAPI(
        title="Jusika Python Agent",
        version="0.1.0",
        lifespan=lifespan,
    )
    application.state.readiness_service = None
    application.add_middleware(MobileSecurityMiddleware, settings=resolved_settings)

    @application.middleware("http")
    async def request_observability(
        request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        request_id_token = bind_request_id(request.headers.get("X-Jusika-Request-Id"))
        started_at = perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            log_event(
                logger,
                "http.request.failed",
                level=logging.ERROR,
                method=request.method,
                route=_route_template(request),
                durationMs=round((perf_counter() - started_at) * 1000, 2),
            )
            raise
        else:
            response.headers["X-Jusika-Request-Id"] = current_request_id()
            if request.url.path.startswith("/api/agent"):
                response.headers["Cache-Control"] = "no-store"
            log_event(
                logger,
                "http.request.completed",
                method=request.method,
                route=_route_template(request),
                statusCode=response.status_code,
                durationMs=round((perf_counter() - started_at) * 1000, 2),
            )
            return response
        finally:
            reset_request_id(request_id_token)

    @application.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(
            status="UP",
            environment=resolved_settings.environment,
            broker_mutation_mode="SPRING_CONTROLLED",
        )

    @application.get("/ready", response_model=ReadinessResponse)
    async def ready(request: Request, response: Response) -> ReadinessResponse:
        readiness_service: ReadinessService | None = request.app.state.readiness_service
        if readiness_service is None:
            response.status_code = 503
            return ReadinessResponse(
                status="NOT_READY",
                components={"application": "DOWN:STARTING"},
            )
        report = await readiness_service.check()
        if not report.ready:
            response.status_code = 503
        return ReadinessResponse(
            status="READY" if report.ready else "NOT_READY",
            components=report.components,
        )

    @application.post(
        "/api/agent/transcriptions",
        response_model=TranscriptionResponse,
    )
    async def transcribe_voice(request: Request) -> TranscriptionResponse:
        if resolved_transcriber is None:
            raise HTTPException(status_code=503, detail="음성 인식이 설정되지 않았습니다.")
        if request.headers.get("content-type", "").split(";", 1)[0].strip() != "audio/wav":
            raise HTTPException(status_code=415, detail="WAV 음성만 지원합니다.")
        audio = await request.body()
        if not (44 <= len(audio) <= 1_000_044) or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
            raise HTTPException(status_code=400, detail="음성 데이터가 올바르지 않습니다.")
        try:
            text = await resolved_transcriber.transcribe(audio)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail="음성을 인식하지 못했습니다.") from exc
        except Exception as exc:
            raise HTTPException(
                status_code=502, detail="음성 인식 서비스에 연결하지 못했습니다."
            ) from exc
        return TranscriptionResponse(text=text)

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
        result = await service.process_message(
            session_id=owned_session(request.state.mobile_principal, session_id), text=body.text
        )
        return result.model_copy(update={"session_id": str(session_id)})

    @application.post(
        "/api/agent/sessions/{session_id}/voice-messages",
        response_model=AgentTurnResponse,
    )
    async def process_voice_message(
        session_id: UUID,
        body: AgentVoiceMessageRequest,
        request: Request,
    ) -> AgentTurnResponse:
        service: AgentService = request.app.state.agent_service
        result = await service.process_message(
            session_id=owned_session(request.state.mobile_principal, session_id),
            text=body.text,
            voice=True,
            confirmation_preview_id=body.confirmation_preview_id,
        )
        return result.model_copy(update={"session_id": str(session_id)})

    return application


def _route_template(request: Request) -> str:
    route = request.scope.get("route")
    path = getattr(route, "path", None)
    return path if isinstance(path, str) else "/unmatched"


def _create_interpreter(settings: Settings) -> CommandInterpreter:
    if settings.command_interpreter is CommandInterpreterProvider.OPENAI:
        return OpenAICommandInterpreter(
            api_key=settings.openai_api_key.get_secret_value(),
            model=settings.openai_model,
            timeout_seconds=settings.openai_timeout_seconds,
            max_output_tokens=settings.openai_max_output_tokens,
            max_attempts=settings.openai_max_attempts,
            retry_base_delay_seconds=settings.openai_retry_base_delay_seconds,
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


async def _create_session_guard(
    settings: Settings,
    stack: AsyncExitStack,
) -> SessionConcurrencyGuard:
    if settings.checkpoint_provider is CheckpointProvider.POSTGRES:
        connection_string = settings.checkpoint_database_url.get_secret_value()
        if not connection_string:
            raise RuntimeError(
                "PostgreSQL 세션 잠금을 사용하려면 "
                "JUSIKA_AGENT_CHECKPOINT_DATABASE_URL을 설정해야 합니다."
            )
        guard = PostgresSessionConcurrencyGuard(
            connection_string=connection_string,
            lock_timeout_seconds=settings.checkpoint_session_lock_timeout_seconds,
        )
        await guard.open()
        stack.push_async_callback(guard.aclose)
        return guard
    return InMemorySessionConcurrencyGuard()


app = create_app()

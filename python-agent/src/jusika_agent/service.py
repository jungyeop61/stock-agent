"""Session-aware application service around the compiled LangGraph."""

import logging
import re
from collections.abc import Awaitable, Callable
from time import perf_counter
from typing import Any

from langchain_core.runnables import RunnableConfig
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command

from jusika_agent.concurrency import (
    InMemorySessionConcurrencyGuard,
    SessionBusyError,
    SessionConcurrencyGuard,
)
from jusika_agent.graph import AgentState
from jusika_agent.models import AgentStatus, AgentTurnResponse
from jusika_agent.observability import hash_identifier, log_event

logger = logging.getLogger("jusika_agent.service")


class AgentService:
    """Starts commands and safely resumes interrupted slot or approval nodes."""

    _approve_words = {"승인", "사", "네", "예", "응", "주문해", "진행해"}
    _cancel_words = {"취소", "아니", "아니요", "안해", "하지마", "그만"}

    def __init__(
        self,
        graph: CompiledStateGraph[AgentState, None, AgentState, AgentState],
        session_guard: SessionConcurrencyGuard | None = None,
        voice_safety_check: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        self._graph = graph
        self._session_guard = session_guard or InMemorySessionConcurrencyGuard()
        self._voice_safety_check = voice_safety_check

    async def process_message(
        self,
        *,
        session_id: str,
        text: str,
        voice: bool = False,
        confirmation_preview_id: str | None = None,
    ) -> AgentTurnResponse:
        started_at = perf_counter()
        try:
            async with self._session_guard.hold(session_id):
                voice_safe = True
                if voice:
                    try:
                        if self._voice_safety_check is None:
                            raise RuntimeError("voice safety checker missing")
                        await self._voice_safety_check()
                    except Exception:
                        voice_safe = False
                if voice_safe:
                    response = await self._process_message_locked(
                        session_id=session_id,
                        text=text,
                        voice=voice,
                        confirmation_preview_id=confirmation_preview_id,
                    )
                else:
                    response = AgentTurnResponse(
                        session_id=session_id,
                        status=AgentStatus.ERROR,
                        message=(
                            "음성 요청을 중단했습니다. 금융 백엔드 연결과 "
                            "증권사 안전 상태를 확인해주세요."
                        ),
                    )
        except SessionBusyError:
            response = AgentTurnResponse(
                session_id=session_id,
                status=AgentStatus.ERROR,
                message="같은 대화의 이전 요청을 처리 중입니다. 잠시 후 다시 말씀해주세요.",
            )
        log_event(
            logger,
            "agent.turn.completed",
            sessionHash=hash_identifier(session_id),
            status=response.status.value,
            requiresConfirmation=response.requires_confirmation,
            durationMs=round((perf_counter() - started_at) * 1000, 2),
        )
        return response

    async def _process_message_locked(
        self,
        *,
        session_id: str,
        text: str,
        voice: bool = False,
        confirmation_preview_id: str | None = None,
    ) -> AgentTurnResponse:
        config: RunnableConfig = {"configurable": {"thread_id": session_id}}
        snapshot = await self._graph.aget_state(config)

        if voice:
            normalized = re.sub(r"[\s.!?]+", "", text.strip())
            waiting = "await_confirmation" in snapshot.next
            if confirmation_preview_id is not None and (
                not waiting or confirmation_preview_id != snapshot.values.get("preview_id")
            ):
                return AgentTurnResponse(
                    session_id=session_id,
                    status=AgentStatus.ERROR,
                    message=(
                        "안내한 미리보기와 승인 대상이 다르거나 이미 처리된 요청입니다. "
                        "실행하지 않았습니다."
                    ),
                )
            if waiting:
                if normalized not in {"승인", "취소"}:
                    return self._response(session_id, dict(snapshot.values))
                if confirmation_preview_id is None:
                    return AgentTurnResponse(
                        session_id=session_id,
                        status=AgentStatus.ERROR,
                        message="음성 안내한 미리보기 식별값이 없어 승인 또는 중단하지 않았습니다.",
                    )
            elif "await_slot" not in snapshot.next and normalized in self._approve_words:
                return AgentTurnResponse(
                    session_id=session_id,
                    status=AgentStatus.ERROR,
                    message="현재 음성 승인할 미리보기가 없습니다. 먼저 요청 내용을 말씀해주세요.",
                )

        if snapshot.next:
            if "await_slot" in snapshot.next:
                result = await self._graph.ainvoke(Command(resume=text), config=config)
            else:
                state = dict(snapshot.values)
                decision = self._confirmation_decision(text)
                if decision is None:
                    return AgentTurnResponse(
                        session_id=session_id,
                        status=AgentStatus.WAITING_CONFIRMATION,
                        message="실행하려면 승인, 그만두려면 취소라고 말씀해주세요.",
                        requires_confirmation=True,
                        preview_id=self._optional_text(state.get("preview_id")),
                        data=self._optional_dict(state.get("preview")),
                    )
                log_event(
                    logger,
                    "agent.confirmation.received",
                    sessionHash=hash_identifier(session_id),
                    decision=decision,
                    action=state.get("pending_action") or "UNKNOWN",
                )
                result = await self._graph.ainvoke(Command(resume=decision), config=config)
        else:
            result = await self._graph.ainvoke(
                self._initial_state(session_id=session_id, text=text),
                config=config,
            )
        response = self._response(session_id, result)
        parsed = result.get("parsed_intent")
        intent = parsed.get("intent") if isinstance(parsed, dict) else None
        pending_action = result.get("pending_action")
        log_event(
            logger,
            "agent.workflow.transitioned",
            sessionHash=hash_identifier(session_id),
            intent=intent or "UNKNOWN",
            action=pending_action or "NONE",
            status=response.status.value,
        )
        return response

    @classmethod
    def _confirmation_decision(cls, text: str) -> str | None:
        normalized = re.sub(r"[\s.!?]+", "", text.strip())
        if normalized in cls._approve_words:
            return "APPROVE"
        if normalized in cls._cancel_words:
            return "CANCEL"
        return None

    @staticmethod
    def _initial_state(*, session_id: str, text: str) -> AgentState:
        return {
            "session_id": session_id,
            "user_text": text,
            "status": "",
            "message": "",
            "parsed_intent": None,
            "display_name": None,
            "account_seq": None,
            "pending_action": None,
            "preview_id": None,
            "preview": None,
            "execution": None,
            "result": None,
            "confirmation": None,
            "missing_field": None,
            "slot_response": None,
            "selected_account_seq": None,
        }

    @staticmethod
    def _response(session_id: str, state: dict[str, Any]) -> AgentTurnResponse:
        status = AgentStatus(state.get("status", AgentStatus.ERROR.value))
        if status is AgentStatus.WAITING_CONFIRMATION:
            data = AgentService._optional_dict(state.get("preview"))
        elif state.get("execution") is not None:
            data = AgentService._optional_dict(state.get("execution"))
        else:
            data = AgentService._optional_dict(state.get("result"))
        return AgentTurnResponse(
            session_id=session_id,
            status=status,
            message=str(state.get("message") or "요청을 처리하지 못했습니다."),
            requires_confirmation=status is AgentStatus.WAITING_CONFIRMATION,
            preview_id=AgentService._optional_text(state.get("preview_id")),
            data=data,
        )

    @staticmethod
    def _optional_dict(value: Any) -> dict[str, Any] | None:
        return value if isinstance(value, dict) else None

    @staticmethod
    def _optional_text(value: Any) -> str | None:
        return value if isinstance(value, str) else None

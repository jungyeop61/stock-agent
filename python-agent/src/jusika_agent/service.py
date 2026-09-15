"""Session-aware application service around the compiled LangGraph."""

import re
from typing import Any

from langchain_core.runnables import RunnableConfig
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command

from jusika_agent.graph import AgentState
from jusika_agent.models import AgentStatus, AgentTurnResponse


class AgentService:
    """Starts commands and safely resumes interrupted slot or approval nodes."""

    _approve_words = {"승인", "사", "네", "예", "응", "주문해", "진행해"}
    _cancel_words = {"취소", "아니", "아니요", "안해", "하지마", "그만"}

    def __init__(
        self,
        graph: CompiledStateGraph[AgentState, None, AgentState, AgentState],
    ) -> None:
        self._graph = graph

    async def process_message(self, *, session_id: str, text: str) -> AgentTurnResponse:
        config: RunnableConfig = {"configurable": {"thread_id": session_id}}
        snapshot = await self._graph.aget_state(config)

        if snapshot.next:
            if "await_slot" in snapshot.next:
                result = await self._graph.ainvoke(Command(resume=text), config=config)
            else:
                decision = self._confirmation_decision(text)
                if decision is None:
                    state = dict(snapshot.values)
                    return AgentTurnResponse(
                        session_id=session_id,
                        status=AgentStatus.WAITING_CONFIRMATION,
                        message="실행하려면 승인, 그만두려면 취소라고 말씀해주세요.",
                        requires_confirmation=True,
                        preview_id=self._optional_text(state.get("preview_id")),
                        data=self._optional_dict(state.get("preview")),
                    )
                result = await self._graph.ainvoke(Command(resume=decision), config=config)
        else:
            result = await self._graph.ainvoke(
                self._initial_state(session_id=session_id, text=text),
                config=config,
            )
        return self._response(session_id, result)

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

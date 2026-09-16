"""Dependency readiness checks that never mutate financial state."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Any, Protocol

from jusika_agent.config import CheckpointProvider, CommandInterpreterProvider
from jusika_agent.observability import log_event

logger = logging.getLogger("jusika_agent.readiness")


class SpringReadinessGateway(Protocol):
    async def check_readiness(self) -> None: ...


@dataclass(frozen=True)
class ReadinessReport:
    ready: bool
    components: dict[str, str]


class ReadinessService:
    """Probe Spring and the active checkpointer with bounded read-only operations."""

    def __init__(
        self,
        *,
        spring: SpringReadinessGateway,
        checkpointer: Any,
        checkpoint_provider: CheckpointProvider,
        interpreter_provider: CommandInterpreterProvider,
        timeout_seconds: float,
    ) -> None:
        self._spring = spring
        self._checkpointer = checkpointer
        self._checkpoint_provider = checkpoint_provider
        self._interpreter_provider = interpreter_provider
        self._timeout_seconds = timeout_seconds

    async def check(self) -> ReadinessReport:
        spring_result, checkpoint_result = await asyncio.gather(
            self._probe("spring", self._spring.check_readiness()),
            self._probe("checkpoint", self._check_checkpoint()),
        )
        components = {
            "spring": spring_result,
            "checkpoint": checkpoint_result,
            "interpreter": (
                "UP:OPENAI_CONFIGURED"
                if self._interpreter_provider is CommandInterpreterProvider.OPENAI
                else "UP:RULES"
            ),
        }
        ready = spring_result == "UP" and checkpoint_result == "UP"
        log_event(
            logger,
            "agent.readiness.checked",
            level=logging.INFO if ready else logging.WARNING,
            ready=ready,
            checkpointProvider=self._checkpoint_provider.value,
            components=components,
        )
        return ReadinessReport(ready=ready, components=components)

    async def _probe(self, component: str, operation: Any) -> str:
        try:
            await asyncio.wait_for(operation, timeout=self._timeout_seconds)
        except TimeoutError:
            log_event(
                logger,
                "agent.readiness.component_failed",
                level=logging.WARNING,
                component=component,
                failure="TIMEOUT",
            )
            return "DOWN:TIMEOUT"
        except Exception:
            log_event(
                logger,
                "agent.readiness.component_failed",
                level=logging.WARNING,
                component=component,
                failure="UNAVAILABLE",
            )
            return "DOWN:UNAVAILABLE"
        return "UP"

    async def _check_checkpoint(self) -> None:
        await self._checkpointer.aget_tuple({"configurable": {"thread_id": "__jusika_readiness__"}})

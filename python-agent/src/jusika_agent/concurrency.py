"""Per-session concurrency guards for agent state transitions."""

import asyncio
from collections.abc import AsyncIterator
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from dataclasses import dataclass, field
from hashlib import blake2b
from typing import Protocol

from psycopg import errors
from psycopg_pool import AsyncConnectionPool, PoolTimeout


class SessionBusyError(RuntimeError):
    """Raised when another worker holds the same session longer than allowed."""


class SessionConcurrencyGuard(Protocol):
    """Serializes complete state transitions for one conversation session."""

    def hold(self, session_id: str) -> AbstractAsyncContextManager[None]: ...


@dataclass
class _LockEntry:
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    users: int = 0


class InMemorySessionConcurrencyGuard:
    """Process-local keyed locks used with the in-memory checkpointer."""

    def __init__(self) -> None:
        self._entries: dict[str, _LockEntry] = {}
        self._registry_lock = asyncio.Lock()

    @asynccontextmanager
    async def hold(self, session_id: str) -> AsyncIterator[None]:
        async with self._registry_lock:
            entry = self._entries.setdefault(session_id, _LockEntry())
            entry.users += 1

        acquired = False
        try:
            await entry.lock.acquire()
            acquired = True
            yield
        finally:
            if acquired:
                entry.lock.release()
            async with self._registry_lock:
                entry.users -= 1
                if entry.users == 0:
                    self._entries.pop(session_id, None)


class PostgresSessionConcurrencyGuard:
    """Cross-process session locks backed by PostgreSQL advisory locks."""

    def __init__(
        self,
        *,
        connection_string: str,
        lock_timeout_seconds: float,
    ) -> None:
        self._pool = AsyncConnectionPool(
            conninfo=connection_string,
            min_size=0,
            max_size=10,
            open=False,
            timeout=lock_timeout_seconds,
        )
        self._lock_timeout_ms = max(1, int(lock_timeout_seconds * 1000))

    async def open(self) -> None:
        await self._pool.open(wait=True)

    async def aclose(self) -> None:
        await self._pool.close()

    @asynccontextmanager
    async def hold(self, session_id: str) -> AsyncIterator[None]:
        lock_key = self._lock_key(session_id)
        try:
            async with self._pool.connection() as connection:
                async with connection.transaction():
                    async with connection.cursor() as cursor:
                        await cursor.execute(
                            "SELECT set_config('lock_timeout', %s, true)",
                            (f"{self._lock_timeout_ms}ms",),
                        )
                        await cursor.execute(
                            "SELECT pg_advisory_xact_lock(%s)",
                            (lock_key,),
                        )
                    yield
        except (errors.LockNotAvailable, PoolTimeout) as exc:
            raise SessionBusyError("동일한 대화 요청이 이미 처리 중입니다.") from exc

    @staticmethod
    def _lock_key(session_id: str) -> int:
        digest = blake2b(session_id.encode("utf-8"), digest_size=8).digest()
        return int.from_bytes(digest, byteorder="big", signed=True)

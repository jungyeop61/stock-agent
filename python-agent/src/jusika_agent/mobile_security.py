"""Single-process family access boundary; no tokens or utterances in logs."""

import hashlib
import hmac
import ipaddress
from collections import deque
from time import monotonic
from uuid import UUID

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from jusika_agent.config import Settings


class MobileSecurityMiddleware:
    def __init__(self, app: ASGIApp, settings: Settings) -> None:
        self.app = app
        self.required = settings.mobile_auth_required
        self.credentials = [
            (user, hashlib.sha256(token.encode()).digest())
            for user, token in settings.mobile_users().items()
        ]
        self.limit = settings.mobile_requests_per_minute
        self.windows: dict[str, deque[float]] = {}

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not scope["path"].startswith("/api/agent"):
            await self.app(scope, receive, send)
            return
        headers = scope.get("headers", [])
        # Browser-origin requests are not part of this native-app protocol.
        if any(key == b"origin" for key, _ in headers):
            await self.reject(scope, receive, send, 403)
            return
        auth = [value for key, value in headers if key == b"authorization"]
        principal: str | None = None
        if self.required:
            if len(auth) == 1 and auth[0].startswith(b"Bearer ") and len(auth[0]) <= 263:
                digest = hashlib.sha256(auth[0][7:]).digest()
                for user, expected in self.credentials:
                    if hmac.compare_digest(digest, expected):
                        principal = user
            if principal is None:
                await self.reject(scope, receive, send, 401)
                return
            # No token-bearing plaintext connection except loopback USB development.
            if scope.get("scheme") != "https" and not self.loopback(scope):
                await self.reject(scope, receive, send, 403)
                return
        elif not self.loopback(scope) or auth:
            await self.reject(scope, receive, send, 401)
            return
        bucket = principal or "local-development"
        now = monotonic()
        window = self.windows.setdefault(bucket, deque())
        while window and window[0] <= now - 60:
            window.popleft()
        if len(window) >= self.limit:
            await self.reject(scope, receive, send, 429)
            return
        window.append(now)
        scope.setdefault("state", {})["mobile_principal"] = principal
        # Bound the body before JSON parsing, including chunked requests.
        chunks = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(chunks) + len(chunk) > 8192:
                await self.reject(scope, receive, send, 413)
                return
            chunks.extend(chunk)
            if not message.get("more_body", False):
                break
        delivered = False

        async def bounded_receive() -> Message:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(chunks), "more_body": False}
            return await receive()

        await self.app(scope, bounded_receive, send)

    @staticmethod
    def loopback(scope: Scope) -> bool:
        try:
            client = scope.get("client")
            return bool(client and ipaddress.ip_address(client[0]).is_loopback)
        except ValueError:
            return False

    @staticmethod
    async def reject(scope: Scope, receive: Receive, send: Send, code: int) -> None:
        headers = {"Cache-Control": "no-store"}
        if code == 401:
            headers["WWW-Authenticate"] = "Bearer"
        if code == 429:
            headers["Retry-After"] = "60"
        await JSONResponse(
            {"detail": "요청이 허용되지 않았습니다."}, status_code=code, headers=headers
        )(scope, receive, send)


def owned_session(principal: str | None, session: UUID) -> str:
    """Stable across restarts/token rotation; raw client IDs never name private threads."""
    if principal is None:
        return str(session)
    return "mobile-" + hashlib.sha256(f"{principal}:{session}".encode()).hexdigest()

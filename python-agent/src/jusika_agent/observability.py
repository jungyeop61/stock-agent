"""Structured logging, request correlation, and defensive redaction."""

from __future__ import annotations

import json
import logging
import re
from contextvars import ContextVar, Token
from datetime import UTC, datetime
from hashlib import blake2b
from typing import Any
from uuid import UUID, uuid4

_request_id: ContextVar[str] = ContextVar("jusika_request_id", default="-")

_SECRET_ASSIGNMENT = re.compile(
    r"(?i)(authorization|api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|"
    r"client[-_ ]?secret|password)\s*[:=]\s*([^\s,;]+)"
)
_BEARER_TOKEN = re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+\-/=]+")
_SENSITIVE_IDENTIFIER = re.compile(
    r"(?i)\b(account(?:Seq|Number|No)?|orderId|executionId|previewId|"
    r"conditionalOrderId|clientOrderId|sessionId)\s*[:=]\s*"
    r"[A-Za-z0-9*._:-]+"
)
_UUID = re.compile(
    r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\b"
)
_ACCOUNT_NUMBER = re.compile(r"(?<!\d)\d{8,16}(?!\d)")
_SENSITIVE_FIELD_NAME = re.compile(
    r"(?i)^(account|accountSeq|accountNumber|accountNo|orderId|executionId|previewId|"
    r"conditionalOrderId|clientOrderId|sessionId|authorization|apiKey|token|password)$"
)


def current_request_id() -> str:
    """Return the correlation ID for the current async request."""

    return _request_id.get()


def bind_request_id(value: str | None) -> Token[str]:
    """Validate an inbound UUID or create a fresh correlation ID."""

    request_id = str(uuid4())
    if value:
        try:
            request_id = str(UUID(value))
        except ValueError:
            pass
    return _request_id.set(request_id)


def reset_request_id(token: Token[str]) -> None:
    _request_id.reset(token)


def hash_identifier(value: str) -> str:
    """Produce a stable, non-reversible short label for operational correlation."""

    return blake2b(value.encode("utf-8"), digest_size=8).hexdigest()


def sanitize_text(value: str) -> str:
    """Remove secrets and financial identifiers from arbitrary log text."""

    sanitized = _BEARER_TOKEN.sub("Bearer_[REDACTED]", value)
    sanitized = _SECRET_ASSIGNMENT.sub(lambda match: f"{match.group(1)}=[REDACTED]", sanitized)
    sanitized = _SENSITIVE_IDENTIFIER.sub(lambda match: f"{match.group(1)}=[REDACTED]", sanitized)
    sanitized = _UUID.sub("[REDACTED_ID]", sanitized)
    return _ACCOUNT_NUMBER.sub("[REDACTED_ACCOUNT]", sanitized)


def sanitize_value(value: Any) -> Any:
    """Recursively sanitize structured log fields."""

    if isinstance(value, str):
        return sanitize_text(value)
    if isinstance(value, dict):
        return {
            str(key): "[REDACTED]"
            if _SENSITIVE_FIELD_NAME.fullmatch(str(key))
            else sanitize_value(item)
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple, set)):
        return [sanitize_value(item) for item in value]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return sanitize_text(str(value))


class JsonLogFormatter(logging.Formatter):
    """Render one sanitized JSON object per log record."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "event": sanitize_text(record.getMessage()),
            "requestId": current_request_id(),
        }
        event_fields = getattr(record, "event_fields", None)
        if isinstance(event_fields, dict):
            payload.update(sanitize_value(event_fields))
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_logging(level: str) -> None:
    """Install one idempotent JSON handler on the application logger."""

    logger = logging.getLogger("jusika_agent")
    normalized_level = getattr(logging, level.upper(), logging.INFO)
    logger.setLevel(normalized_level)
    logger.propagate = False
    if any(getattr(handler, "_jusika_json", False) for handler in logger.handlers):
        for handler in logger.handlers:
            if getattr(handler, "_jusika_json", False):
                handler.setLevel(normalized_level)
        return
    handler = logging.StreamHandler()
    handler.setLevel(normalized_level)
    handler.setFormatter(JsonLogFormatter())
    handler._jusika_json = True  # type: ignore[attr-defined]
    logger.addHandler(handler)


def log_event(
    logger: logging.Logger,
    event: str,
    *,
    level: int = logging.INFO,
    **fields: Any,
) -> None:
    """Emit a structured event without interpolating sensitive values into text."""

    logger.log(level, event, extra={"event_fields": fields})

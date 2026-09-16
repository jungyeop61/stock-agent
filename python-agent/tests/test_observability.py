import io
import json
import logging

from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver

from jusika_agent.app import create_app
from jusika_agent.config import Settings
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from jusika_agent.observability import JsonLogFormatter, sanitize_text
from tests.fakes import FakeSpringGateway


def test_sanitize_text_removes_secrets_and_financial_identifiers() -> None:
    raw = (
        "Authorization: Bearer secret-token api_key=top-secret "
        "accountNumber=1234567890 orderId=order-123 "
        "executionId=execution-456 sessionId=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    )

    sanitized = sanitize_text(raw)

    for secret in (
        "secret-token",
        "top-secret",
        "1234567890",
        "order-123",
        "execution-456",
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    ):
        assert secret not in sanitized
    assert "[REDACTED]" in sanitized


def test_json_formatter_sanitizes_message_and_fields() -> None:
    formatter = JsonLogFormatter()
    record = logging.LogRecord(
        name="jusika_agent.test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="access_token=private-token accountNo=1234567890",
        args=(),
        exc_info=None,
    )
    record.event_fields = {  # type: ignore[attr-defined]
        "executionId": "execution-123",
        "safe": "SUCCESS",
    }

    payload = formatter.format(record)

    assert "private-token" not in payload
    assert "1234567890" not in payload
    assert "execution-123" not in payload
    assert json.loads(payload)["safe"] == "SUCCESS"


def test_http_logs_use_route_template_and_never_include_user_utterance() -> None:
    fake = FakeSpringGateway()
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    output = io.StringIO()
    handler = logging.StreamHandler(output)
    handler.setFormatter(JsonLogFormatter())
    logger = logging.getLogger("jusika_agent")
    logger.addHandler(handler)
    session_id = "11111111-1111-4111-8111-111111111111"

    try:
        with TestClient(app) as client:
            response = client.post(
                f"/api/agent/sessions/{session_id}/messages",
                json={"text": "삼성전자 5주 사줘"},
            )
    finally:
        logger.removeHandler(handler)

    logs = output.getvalue()
    assert response.status_code == 200
    assert "삼성전자 5주 사줘" not in logs
    assert session_id not in logs
    assert "/api/agent/sessions/{session_id}/messages" in logs
    assert "sessionHash" in logs

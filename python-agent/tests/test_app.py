from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver

from jusika_agent.app import create_app
from jusika_agent.config import Settings
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from tests.fakes import FakeSpringGateway


class FailingCheckpointer(InMemorySaver):
    async def aget_tuple(self, config):  # type: ignore[no-untyped-def, override]
        del config
        raise RuntimeError("postgresql://user:private-password@db.internal/finance")


def test_health_and_mock_order_http_flow() -> None:
    fake = FakeSpringGateway()
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    session_id = "11111111-1111-4111-8111-111111111111"

    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        health = client.get("/health")
        readiness = client.get("/ready")
        preview = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": "삼성전자 5주 사줘"},
        )
        execution = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": "승인"},
        )

    assert health.json()["status"] == "UP"
    assert readiness.status_code == 200
    assert readiness.json() == {
        "status": "READY",
        "components": {
            "spring": "UP",
            "checkpoint": "UP",
            "interpreter": "UP:RULES",
        },
    }
    assert fake.readiness_calls == 1
    assert preview.json()["status"] == "WAITING_CONFIRMATION"
    assert execution.json()["status"] == "COMPLETED"
    assert fake.executed_preview_ids == ["preview-1"]


def test_http_flow_collects_missing_order_fields() -> None:
    fake = FakeSpringGateway()
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    session_id = "22222222-2222-4222-8222-222222222222"

    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        stock_prompt = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": "사줘"},
        )
        quantity_prompt = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": "삼성전자"},
        )
        preview = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": "5주"},
        )

    assert stock_prompt.json()["status"] == "NEEDS_INPUT"
    assert quantity_prompt.json()["status"] == "NEEDS_INPUT"
    assert preview.json()["status"] == "WAITING_CONFIRMATION"
    assert len(fake.preview_requests) == 1


def test_readiness_returns_503_without_leaking_dependency_error() -> None:
    fake = FakeSpringGateway()
    fake.readiness_error = RuntimeError("token=super-secret accountNumber=1234567890")
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )

    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        response = client.get("/ready")

    assert response.status_code == 503
    assert response.json()["status"] == "NOT_READY"
    assert response.json()["components"]["spring"] == "DOWN:UNAVAILABLE"
    assert "super-secret" not in response.text
    assert "1234567890" not in response.text


def test_request_id_is_validated_and_returned() -> None:
    fake = FakeSpringGateway()
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    valid_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        preserved = client.get("/health", headers={"X-Jusika-Request-Id": valid_id})
        replaced = client.get("/health", headers={"X-Jusika-Request-Id": "not-a-valid-request-id"})

    assert preserved.headers["X-Jusika-Request-Id"] == valid_id
    assert replaced.headers["X-Jusika-Request-Id"] != "not-a-valid-request-id"
    assert len(replaced.headers["X-Jusika-Request-Id"]) == 36


def test_readiness_returns_503_when_checkpoint_is_unavailable() -> None:
    fake = FakeSpringGateway()
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=FailingCheckpointer(),
    )

    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        response = client.get("/ready")

    assert response.status_code == 503
    assert response.json()["components"]["checkpoint"] == "DOWN:UNAVAILABLE"
    assert "private-password" not in response.text

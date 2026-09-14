from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver

from jusika_agent.app import create_app
from jusika_agent.config import Settings
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from tests.fakes import FakeSpringGateway


def test_health_and_mock_order_http_flow() -> None:
    fake = FakeSpringGateway()
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    session_id = "11111111-1111-4111-8111-111111111111"

    with TestClient(app) as client:
        health = client.get("/health")
        preview = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": "삼성전자 5주 사줘"},
        )
        execution = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": "승인"},
        )

    assert health.json()["status"] == "UP"
    assert preview.json()["status"] == "WAITING_CONFIRMATION"
    assert execution.json()["status"] == "COMPLETED"
    assert fake.executed_preview_ids == ["preview-1"]

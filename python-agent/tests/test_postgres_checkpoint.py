"""Restart-recovery tests against the isolated PostgreSQL checkpoint database."""

import os
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from jusika_agent.app import create_app
from jusika_agent.config import CheckpointProvider, Settings
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from tests.fakes import FakeSpringGateway

_RUN_POSTGRES_TEST = os.getenv("RUN_JUSIKA_AGENT_POSTGRES_TEST", "false").lower() == "true"
pytestmark = [
    pytest.mark.postgres,
    pytest.mark.skipif(
        not _RUN_POSTGRES_TEST,
        reason="RUN_JUSIKA_AGENT_POSTGRES_TEST=true is required",
    ),
]


def _postgres_settings() -> Settings:
    connection_string = os.getenv("JUSIKA_AGENT_TEST_POSTGRES_URL", "")
    if not connection_string:
        pytest.fail("JUSIKA_AGENT_TEST_POSTGRES_URL must be set for the PostgreSQL test")
    database_name = urlsplit(connection_string).path.removeprefix("/").lower()
    if "test" not in database_name and "integration" not in database_name:
        pytest.fail("PostgreSQL checkpoint tests require a test or integration database")
    return Settings(
        _env_file=None,
        checkpoint_provider=CheckpointProvider.POSTGRES,
        checkpoint_database_url=connection_string,
    )


def _send_message(
    *,
    settings: Settings,
    spring: FakeSpringGateway,
    session_id: str,
    text: str,
) -> dict[str, object]:
    app = create_app(
        settings=settings,
        interpreter=RuleBasedCommandInterpreter(),
        spring=spring,
    )
    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        response = client.post(
            f"/api/agent/sessions/{session_id}/messages",
            json={"text": text},
        )
    assert response.status_code == 200
    payload: dict[str, object] = response.json()
    return payload


def _post(client: TestClient, session_id: str, text: str) -> dict[str, object]:
    response = client.post(
        f"/api/agent/sessions/{session_id}/messages",
        json={"text": text},
    )
    assert response.status_code == 200
    payload: dict[str, object] = response.json()
    return payload


def test_postgres_checkpoint_recovers_missing_fields_after_each_restart() -> None:
    settings = _postgres_settings()
    spring = FakeSpringGateway()
    session_id = str(uuid4())

    stock_prompt = _send_message(
        settings=settings,
        spring=spring,
        session_id=session_id,
        text="사줘",
    )
    quantity_prompt = _send_message(
        settings=settings,
        spring=spring,
        session_id=session_id,
        text="삼성전자",
    )
    preview = _send_message(
        settings=settings,
        spring=spring,
        session_id=session_id,
        text="5주",
    )

    assert stock_prompt["status"] == "NEEDS_INPUT"
    assert "어느 종목" in str(stock_prompt["message"])
    assert quantity_prompt["status"] == "NEEDS_INPUT"
    assert "몇 주" in str(quantity_prompt["message"])
    assert preview["status"] == "WAITING_CONFIRMATION"
    assert preview["requires_confirmation"] is True
    assert len(spring.preview_requests) == 1
    assert spring.account_list_calls == 1


def test_postgres_checkpoint_recovers_exact_preview_for_approval() -> None:
    settings = _postgres_settings()
    spring = FakeSpringGateway()
    session_id = str(uuid4())

    preview = _send_message(
        settings=settings,
        spring=spring,
        session_id=session_id,
        text="삼성전자 5주 사줘",
    )
    ambiguous = _send_message(
        settings=settings,
        spring=spring,
        session_id=session_id,
        text="삼성전자 50주 사줘",
    )
    execution = _send_message(
        settings=settings,
        spring=spring,
        session_id=session_id,
        text="승인",
    )

    assert preview["status"] == "WAITING_CONFIRMATION"
    assert ambiguous["status"] == "WAITING_CONFIRMATION"
    assert ambiguous["preview_id"] == preview["preview_id"]
    assert len(spring.preview_requests) == 1
    assert execution["status"] == "COMPLETED"
    assert spring.approved_preview_ids == [preview["preview_id"]]
    assert spring.executed_preview_ids == [preview["preview_id"]]


def test_postgres_lock_serializes_duplicate_approvals_across_app_instances() -> None:
    settings = _postgres_settings()
    spring = FakeSpringGateway()
    session_id = str(uuid4())
    first_app = create_app(
        settings=settings,
        interpreter=RuleBasedCommandInterpreter(),
        spring=spring,
    )
    second_app = create_app(
        settings=settings,
        interpreter=RuleBasedCommandInterpreter(),
        spring=spring,
    )

    with (
        TestClient(first_app, client=("127.0.0.1", 50000)) as first_client,
        TestClient(second_app, client=("127.0.0.1", 50000)) as second_client,
    ):
        preview = _post(first_client, session_id, "삼성전자 5주 사줘")
        with ThreadPoolExecutor(max_workers=2) as executor:
            first_future = executor.submit(_post, first_client, session_id, "승인")
            second_future = executor.submit(_post, second_client, session_id, "승인")
            responses = [first_future.result(), second_future.result()]

    assert preview["status"] == "WAITING_CONFIRMATION"
    assert sum(response["status"] == "COMPLETED" for response in responses) == 1
    assert sum(response["status"] == "NEEDS_INPUT" for response in responses) == 1
    assert spring.approved_preview_ids == [preview["preview_id"]]
    assert spring.executed_preview_ids == [preview["preview_id"]]

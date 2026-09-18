import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver
from pydantic import SecretStr, ValidationError

from jusika_agent.app import create_app
from jusika_agent.config import Settings
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from jusika_agent.mobile_security import owned_session
from tests.fakes import FakeSpringGateway

ALICE = "a" * 43
BOB = "b" * 43


def settings(**kwargs: object) -> Settings:
    values = dict(
        mobile_auth_required=True,
        mobile_credentials=SecretStr(json.dumps({"alice": ALICE, "bob": BOB})),
    )
    values.update(kwargs)
    return Settings(_env_file=None, **values)  # type: ignore[arg-type]


def client(
    config: Settings,
    fake: FakeSpringGateway | None = None,
    host: str = "127.0.0.1",
    scheme: str = "http",
) -> TestClient:
    return TestClient(
        create_app(
            settings=config,
            spring=fake or FakeSpringGateway(),
            interpreter=RuleBasedCommandInterpreter(),
            checkpointer=InMemorySaver(),
        ),
        client=(host, 50000),
        base_url=f"{scheme}://agent.example",
    )


@pytest.mark.parametrize("route", ["messages", "voice-messages"])
@pytest.mark.parametrize("authorization", [None, "Bearer wrong", "Basic " + ALICE])
def test_both_endpoints_require_auth(route: str, authorization: str | None) -> None:
    fake = FakeSpringGateway()
    with client(settings(), fake) as api:
        headers = {} if authorization is None else {"Authorization": authorization}
        response = api.post(
            f"/api/agent/sessions/{uuid4()}/{route}",
            json={"text": "삼성전자 5주 사줘"},
            headers=headers,
        )
        assert response.status_code == 401
        assert response.headers["www-authenticate"] == "Bearer"
        assert fake.executed_preview_ids == []
        assert api.get("/health").status_code == 200


def test_other_user_cannot_approve_or_resume_preview_using_identical_uuid() -> None:
    fake = FakeSpringGateway()
    session = uuid4()
    path = f"/api/agent/sessions/{session}/voice-messages"
    with client(settings(), fake) as api:
        preview = api.post(
            path, json={"text": "삼성전자 5주 사줘"}, headers={"Authorization": "Bearer " + ALICE}
        ).json()
        assert preview["session_id"] == str(session)
        assert preview["status"] == "WAITING_CONFIRMATION"
        stolen = api.post(
            path,
            json={"text": "승인", "confirmation_preview_id": preview["preview_id"]},
            headers={"Authorization": "Bearer " + BOB},
        ).json()
        assert stolen["status"] == "ERROR"
        assert fake.executed_preview_ids == []
        result = api.post(
            path,
            json={"text": "승인", "confirmation_preview_id": preview["preview_id"]},
            headers={"Authorization": "Bearer " + ALICE},
        ).json()
        assert result["status"] == "COMPLETED"
        assert fake.executed_preview_ids == [preview["preview_id"]]


def test_text_session_is_also_isolated_from_other_users() -> None:
    fake = FakeSpringGateway()
    path = f"/api/agent/sessions/{uuid4()}/messages"
    with client(settings(), fake) as api:
        preview = api.post(
            path, json={"text": "삼성전자 5주 사줘"}, headers={"Authorization": "Bearer " + ALICE}
        ).json()
        assert preview["status"] == "WAITING_CONFIRMATION"
        api.post(path, json={"text": "승인"}, headers={"Authorization": "Bearer " + BOB})
        assert fake.executed_preview_ids == []


def test_rate_limit_is_shared_across_sessions_and_routes_but_not_users() -> None:
    with client(settings(mobile_requests_per_minute=2)) as api:
        for route in ["messages", "voice-messages", "messages"]:
            response = api.post(
                f"/api/agent/sessions/{uuid4()}/{route}",
                json={"text": "삼성전자 현재가 알려줘"},
                headers={"Authorization": "Bearer " + ALICE},
            )
        assert response.status_code == 429
        assert response.headers["retry-after"] == "60"
        assert (
            api.post(
                f"/api/agent/sessions/{uuid4()}/messages",
                json={"text": "삼성전자 현재가 알려줘"},
                headers={"Authorization": "Bearer " + BOB},
            ).status_code
            == 200
        )


@pytest.mark.parametrize(
    "scheme,host,expected",
    [("http", "203.0.113.4", 403), ("https", "203.0.113.4", 200), ("http", "127.0.0.1", 200)],
)
def test_remote_token_requests_require_tls(scheme: str, host: str, expected: int) -> None:
    with client(settings(), host=host, scheme=scheme) as api:
        assert (
            api.post(
                f"/api/agent/sessions/{uuid4()}/messages",
                json={"text": "삼성전자 현재가 알려줘"},
                headers={"Authorization": "Bearer " + ALICE},
            ).status_code
            == expected
        )


def test_local_dev_does_not_trust_forwarded_headers() -> None:
    with client(Settings(_env_file=None), host="203.0.113.4") as api:
        assert (
            api.post(
                f"/api/agent/sessions/{uuid4()}/messages",
                json={"text": "삼성전자 현재가 알려줘"},
                headers={"X-Forwarded-For": "127.0.0.1"},
            ).status_code
            == 401
        )


def test_body_and_browser_origin_rejected_before_graph() -> None:
    with client(settings()) as api:
        path = f"/api/agent/sessions/{uuid4()}/messages"
        headers = {"Authorization": "Bearer " + ALICE}
        assert api.post(path, content="x" * 8193, headers=headers).status_code == 413
        assert (
            api.post(
                path, json={"text": "승인"}, headers={**headers, "Origin": "https://evil.example"}
            ).status_code
            == 403
        )


@pytest.mark.parametrize(
    "values",
    [
        {"environment": "production"},
        {"mobile_auth_required": True},
        {"mobile_credentials": SecretStr('{"alice":"short"}')},
        {"mobile_credentials": SecretStr(json.dumps({"alice": ALICE, "bob": ALICE}))},
    ],
)
def test_invalid_or_missing_credentials_fail_closed(values: dict) -> None:
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **values)


def test_namespace_is_stable_and_does_not_expose_user_id() -> None:
    session = uuid4()
    assert owned_session("alice", session) == owned_session("alice", session)
    assert owned_session("alice", session) != owned_session("bob", session)
    assert "alice" not in owned_session("alice", session)
    assert owned_session(None, session) == str(session)


def test_invalid_auth_is_also_ip_limited_and_window_expires(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    now = [0.0]
    monkeypatch.setattr("jusika_agent.mobile_security.monotonic", lambda: now[0])
    with client(settings(mobile_ip_requests_per_minute=2)) as api:
        path = f"/api/agent/sessions/{uuid4()}/voice-messages"
        assert api.post(path, json={"text": "승인"}).status_code == 401
        assert api.post(path, json={"text": "승인"}).status_code == 401
        assert api.post(path, json={"text": "승인"}).status_code == 429
        now[0] = 60.0
        assert api.post(path, json={"text": "승인"}).status_code == 401


def test_rate_window_expires_without_sleep(monkeypatch: pytest.MonkeyPatch) -> None:
    now = [0.0]
    monkeypatch.setattr("jusika_agent.mobile_security.monotonic", lambda: now[0])
    with client(settings(mobile_requests_per_minute=1)) as api:
        path = f"/api/agent/sessions/{uuid4()}/messages"
        headers = {"Authorization": "Bearer " + ALICE}
        assert (
            api.post(path, json={"text": "삼성전자 현재가 알려줘"}, headers=headers).status_code
            == 200
        )
        assert api.post(path, json={"text": "승인"}, headers=headers).status_code == 429
        now[0] = 60.0
        assert (
            api.post(path, json={"text": "삼성전자 현재가 알려줘"}, headers=headers).status_code
            == 200
        )


def test_duplicate_auth_headers_and_token_in_query_cannot_bypass_auth() -> None:
    with client(settings()) as api:
        path = f"/api/agent/sessions/{uuid4()}/messages"
        assert (
            api.post(
                path,
                json={"text": "승인"},
                headers=[
                    ("Authorization", "Bearer " + ALICE),
                    ("Authorization", "Bearer " + BOB),
                ],
            ).status_code
            == 401
        )
        assert api.post(path + "?token=" + ALICE, json={"text": "승인"}).status_code == 401


def test_token_rotation_keeps_owned_checkpoint_and_revokes_old_token() -> None:
    checkpoint = InMemorySaver()
    fake = FakeSpringGateway()
    path = f"/api/agent/sessions/{uuid4()}/voice-messages"

    def rotated_api(token: str) -> TestClient:
        return TestClient(
            create_app(
                settings=settings(mobile_credentials=SecretStr(json.dumps({"alice": token}))),
                interpreter=RuleBasedCommandInterpreter(),
                spring=fake,
                checkpointer=checkpoint,
            ),
            client=("127.0.0.1", 50000),
        )

    with rotated_api(ALICE) as api:
        preview = api.post(
            path, json={"text": "삼성전자 5주 사줘"}, headers={"Authorization": "Bearer " + ALICE}
        ).json()
    with rotated_api(BOB) as api:
        consent = {"text": "승인", "confirmation_preview_id": preview["preview_id"]}
        assert (
            api.post(path, json=consent, headers={"Authorization": "Bearer " + ALICE}).status_code
            == 401
        )
        assert fake.executed_preview_ids == []
        assert (
            api.post(path, json=consent, headers={"Authorization": "Bearer " + BOB}).json()[
                "status"
            ]
            == "COMPLETED"
        )

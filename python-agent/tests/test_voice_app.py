from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver

from jusika_agent.app import create_app
from jusika_agent.config import Settings
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from tests.fakes import FakeSpringGateway


class ControlledSafetyGateway(FakeSpringGateway):
    voice_allowed = True

    async def check_mock_safety(self) -> None:
        if not self.voice_allowed:
            raise RuntimeError("token=private-token accountNumber=1234567890")


def voice_client(fake: FakeSpringGateway) -> TestClient:
    return TestClient(
        create_app(
            settings=Settings(_env_file=None),
            interpreter=RuleBasedCommandInterpreter(),
            spring=fake,
            checkpointer=InMemorySaver(),
        )
    )


@pytest.mark.parametrize(
    "text",
    [
        "삼성전자 현재가 알려줘",
        "지금 달러 환율 알려줘",
        "100달러는 원화로 얼마야",
        "내 보유 주식 알려줘",
        "원화 주문 가능 금액 알려줘",
        "내 주식 수수료 알려줘",
        "삼성전자 매도 가능 수량 알려줘",
        "미체결 주문 알려줘",
        "전체 주문 내역 알려줘",
        "주문번호 order-123 상태 알려줘",
        "조건 주문 목록 알려줘",
        "조건주문번호 conditional-123 상세 알려줘",
        "실행번호 execution-123 상태 알려줘",
        "금액 주문 실행번호 amount-execution-123 상태 알려줘",
    ],
)
def test_voice_all_supported_reads(text: str) -> None:
    with voice_client(FakeSpringGateway()) as client:
        response = client.post(
            f"/api/agent/sessions/{uuid4()}/voice-messages", json={"text": text}
        )
    assert response.status_code == 200
    assert response.json()["status"] == "COMPLETED"
    assert response.json()["requires_confirmation"] is False


EXPIRY = (datetime.now(UTC).date() + timedelta(days=30)).isoformat()


@pytest.mark.parametrize(
    "text",
    [
        "삼성전자 5주 사줘",
        "삼성전자 2주 팔아",
        "애플 200달러어치 사줘",
        "주문번호 order-123 취소해줘",
        "주문번호 order-123을 7주 71,000원으로 정정해줘",
        f"삼성전자 80,000원이 되면 시장가로 2주 매도 조건주문 만료일 {EXPIRY}",
        "삼성전자 2주 OCO 조건주문 첫 조건 감시가 80,000원 주문가 79,000원 "
        f"둘째 조건 감시가 65,000원 주문가 64,900원 만료일 {EXPIRY}",
        "삼성전자 2주 OTO 조건주문 첫 조건 감시가 68,000원 주문가 69,000원 "
        f"둘째 조건 감시가 79,000원 주문가 80,000원 만료일 {EXPIRY}",
        "조건주문번호 conditional-123 취소해줘",
        "조건주문번호 conditional-123을 OCO 조건주문으로 정정 2주 "
        "첫 조건 감시가 80,000원 주문가 79,000원 "
        f"둘째 조건 감시가 65,000원 주문가 64,900원 만료일 {EXPIRY}",
        "실행번호 execution-123 복구해줘",
        "금액 주문 실행번호 amount-execution-123 복구해줘",
    ],
)
def test_voice_mutations_require_preview_bound_consent(text: str) -> None:
    fake = FakeSpringGateway()
    path = f"/api/agent/sessions/{uuid4()}/voice-messages"
    with voice_client(fake) as client:
        preview = client.post(path, json={"text": text}).json()
        assert preview["status"] == "WAITING_CONFIRMATION"
        assert preview["requires_confirmation"] is True
        result = client.post(
            path,
            json={"text": "승인", "confirmation_preview_id": preview["preview_id"]},
        ).json()
    assert result["status"] == "COMPLETED"
    assert result["data"]["brokerMode"] == "MOCK"


def test_voice_rejects_ambiguous_unbound_stale_and_repeated_approval() -> None:
    fake = FakeSpringGateway()
    path = f"/api/agent/sessions/{uuid4()}/voice-messages"
    with voice_client(fake) as client:
        preview = client.post(path, json={"text": "삼성전자 5주 사줘"}).json()
        for text in ["네", "응", "예", "사", "주문해", "진행해", "애플 50주 사줘"]:
            result = client.post(path, json={"text": text}).json()
            assert result["status"] == "WAITING_CONFIRMATION"
            assert result["preview_id"] == preview["preview_id"]
            assert result["message"] == preview["message"]  # Full terms are retained.
        assert client.post(path, json={"text": "승인"}).json()["status"] == "ERROR"
        assert client.post(
            path, json={"text": "승인", "confirmation_preview_id": "wrong-preview"}
        ).json()["status"] == "ERROR"
        assert fake.executed_preview_ids == []
        payload = {"text": "승인", "confirmation_preview_id": preview["preview_id"]}
        assert client.post(path, json=payload).json()["status"] == "COMPLETED"
        assert client.post(path, json=payload).json()["status"] == "ERROR"
    assert fake.executed_preview_ids == [preview["preview_id"]]
    assert len(fake.preview_requests) == 1


def test_voice_slot_collection_and_bound_cancellation() -> None:
    fake = FakeSpringGateway()
    path = f"/api/agent/sessions/{uuid4()}/voice-messages"
    with voice_client(fake) as client:
        assert client.post(path, json={"text": "사줘"}).json()["status"] == "NEEDS_INPUT"
        assert client.post(path, json={"text": "삼성전자"}).json()["status"] == "NEEDS_INPUT"
        preview = client.post(path, json={"text": "5주"}).json()
        assert preview["status"] == "WAITING_CONFIRMATION"
        cancelled = client.post(
            path, json={"text": "취소", "confirmation_preview_id": preview["preview_id"]}
        ).json()
        assert cancelled["status"] == "CANCELLED"
    assert fake.executed_preview_ids == []


def test_voice_safety_is_rechecked_before_approval_and_errors_are_sanitized() -> None:
    fake = ControlledSafetyGateway()
    path = f"/api/agent/sessions/{uuid4()}/voice-messages"
    with voice_client(fake) as client:
        preview = client.post(path, json={"text": "삼성전자 5주 사줘"}).json()
        fake.voice_allowed = False
        blocked = client.post(
            path, json={"text": "승인", "confirmation_preview_id": preview["preview_id"]}
        )
        assert blocked.json()["status"] == "ERROR"
        assert "private-token" not in blocked.text
        assert "1234567890" not in blocked.text
    assert fake.executed_preview_ids == []


def test_voice_refuses_real_fx_and_standalone_consent() -> None:
    with voice_client(FakeSpringGateway()) as client:
        path = f"/api/agent/sessions/{uuid4()}/voice-messages"
        assert client.post(path, json={"text": "승인"}).json()["status"] == "ERROR"
        result = client.post(path, json={"text": "10만 원을 달러로 환전해줘"}).json()
    assert result["status"] == "NEEDS_INPUT"
    assert result["requires_confirmation"] is False

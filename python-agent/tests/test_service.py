from langgraph.checkpoint.memory import InMemorySaver

from jusika_agent.graph import build_agent_graph
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from jusika_agent.models import AgentStatus, Intent, ParsedIntent
from jusika_agent.service import AgentService
from tests.fakes import FakeSpringGateway


def create_service(fake: FakeSpringGateway) -> AgentService:
    graph = build_agent_graph(
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    return AgentService(graph)


async def test_buy_waits_for_explicit_confirmation_then_executes_once() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    preview = await service.process_message(session_id="buy-session", text="삼성전자 5주 사줘")

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert preview.requires_confirmation is True
    assert preview.preview_id == "preview-1"
    assert len(fake.preview_requests) == 1
    assert fake.approved_preview_ids == []
    assert fake.executed_preview_ids == []

    ambiguous = await service.process_message(session_id="buy-session", text="삼성전자 50주 사줘")

    assert ambiguous.status is AgentStatus.WAITING_CONFIRMATION
    assert len(fake.preview_requests) == 1
    assert fake.executed_preview_ids == []

    executed = await service.process_message(session_id="buy-session", text="승인")

    assert executed.status is AgentStatus.COMPLETED
    assert executed.message == "모의 주문을 접수했습니다."
    assert fake.approved_preview_ids == ["preview-1"]
    assert fake.executed_preview_ids == ["preview-1"]

    duplicate = await service.process_message(session_id="buy-session", text="승인")

    assert duplicate.status is AgentStatus.NEEDS_INPUT
    assert fake.executed_preview_ids == ["preview-1"]


async def test_usd_amount_buy_waits_for_confirmation_then_executes_once() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    preview = await service.process_message(session_id="amount-buy", text="애플 200달러어치 사줘")

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert preview.preview_id == "amount-preview-1"
    assert "이백 달러" in preview.message
    request = fake.amount_preview_requests[0]
    assert request.symbol == "AAPL"
    assert str(request.order_amount) == "200"
    assert fake.executed_amount_preview_ids == []

    execution = await service.process_message(session_id="amount-buy", text="승인")

    assert execution.status is AgentStatus.COMPLETED
    assert execution.message == "모의 주문을 접수했습니다."
    assert fake.approved_amount_preview_ids == ["amount-preview-1"]
    assert fake.executed_amount_preview_ids == ["amount-preview-1"]

    duplicate = await service.process_message(session_id="amount-buy", text="승인")

    assert duplicate.status is AgentStatus.NEEDS_INPUT
    assert fake.executed_amount_preview_ids == ["amount-preview-1"]


async def test_krw_amount_buy_is_rejected_before_preview() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="krw-amount-buy", text="삼성전자 10만 원어치 사줘"
    )

    assert response.status is AgentStatus.NEEDS_INPUT
    assert "미국 주식을 달러 금액으로" in response.message
    assert fake.amount_preview_requests == []


async def test_quantity_and_amount_cannot_be_mixed() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="mixed-amount-buy", text="애플 2주 200달러어치 사줘"
    )

    assert response.status is AgentStatus.NEEDS_INPUT
    assert "수량과 금액을 동시에" in response.message
    assert fake.amount_preview_requests == []


async def test_amount_sell_is_rejected_before_preview() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(session_id="amount-sell", text="애플 200달러어치 팔아")

    assert response.status is AgentStatus.NEEDS_INPUT
    assert "금액 매도는 현재 지원하지 않습니다" in response.message
    assert fake.preview_requests == []


async def test_cancel_does_not_approve_or_execute() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)
    await service.process_message(session_id="cancel-session", text="삼성전자 5주 사줘")

    cancelled = await service.process_message(session_id="cancel-session", text="취소")

    assert cancelled.status is AgentStatus.CANCELLED
    assert fake.approved_preview_ids == []
    assert fake.executed_preview_ids == []


async def test_price_query_completes_without_order_calls() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="price-session", text="삼성전자 지금 얼마야"
    )

    assert response.status is AgentStatus.COMPLETED
    assert "칠만이천삼백 원" in response.message
    assert fake.preview_requests == []


async def test_exchange_rate_query_is_read_only() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="exchange-rate", text="지금 달러 환율 알려줘"
    )

    assert response.status is AgentStatus.COMPLETED
    assert "일 달러당 천삼백팔십 점 오 원" in response.message
    assert fake.exchange_rate_requests == [("USD", "KRW")]
    assert fake.preview_requests == []


async def test_currency_conversion_uses_current_reference_rate() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="currency-conversion", text="100달러는 원화로 얼마야"
    )

    assert response.status is AgentStatus.COMPLETED
    assert response.data is not None
    assert response.data["exchangeAmount"] == "100"
    assert response.data["estimatedConvertedAmount"] == "138050.0"
    assert "십삼만팔천오십 원" in response.message


async def test_actual_currency_exchange_is_rejected_without_external_call() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="currency-exchange", text="10만 원을 달러로 환전해줘"
    )

    assert response.status is AgentStatus.NEEDS_INPUT
    assert "실제 환전 거래를 제공하지 않아" in response.message
    assert fake.exchange_rate_requests == []


class MissingQuantityInterpreter:
    async def interpret(self, text: str) -> ParsedIntent:
        del text
        return ParsedIntent(intent=Intent.BUY, stock_name="삼성전자")


async def test_missing_quantity_requests_clarification_without_preview() -> None:
    fake = FakeSpringGateway()
    graph = build_agent_graph(
        interpreter=MissingQuantityInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )

    response = await AgentService(graph).process_message(
        session_id="missing-quantity", text="삼성전자 사줘"
    )

    assert response.status is AgentStatus.NEEDS_INPUT
    assert fake.preview_requests == []


async def test_open_order_list_is_read_only() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(session_id="orders", text="미체결 주문 알려줘")

    assert response.status is AgentStatus.COMPLETED
    assert "주문번호 order-123" in response.message
    assert response.requires_confirmation is False
    assert fake.cancellation_preview_requests == []


async def test_order_cancellation_requires_preview_and_confirmation() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    preview = await service.process_message(
        session_id="order-cancel", text="주문번호 order-123 취소해줘"
    )

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert preview.preview_id == "cancel-preview-1"
    assert fake.approved_cancellation_preview_ids == []
    assert fake.executed_cancellation_preview_ids == []

    execution = await service.process_message(session_id="order-cancel", text="승인")

    assert execution.status is AgentStatus.COMPLETED
    assert execution.message == "모의 주문 취소 요청을 접수했습니다."
    assert fake.approved_cancellation_preview_ids == ["cancel-preview-1"]
    assert fake.executed_cancellation_preview_ids == ["cancel-preview-1"]

    await service.process_message(session_id="order-cancel", text="승인")
    assert fake.executed_cancellation_preview_ids == ["cancel-preview-1"]


async def test_order_cancellation_without_explicit_id_does_not_preview() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(session_id="missing-order-id", text="주문 취소해줘")

    assert response.status is AgentStatus.NEEDS_INPUT
    assert fake.cancellation_preview_requests == []


async def test_order_modification_uses_explicit_new_terms() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    preview = await service.process_message(
        session_id="order-modify",
        text="주문번호 order-123을 7주 71,000원으로 정정해줘",
    )

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    request = fake.modification_preview_requests[0]
    assert request.order_id == "order-123"
    assert str(request.quantity) == "7"
    assert str(request.price) == "71000"

    execution = await service.process_message(session_id="order-modify", text="승인")

    assert execution.status is AgentStatus.COMPLETED
    assert fake.executed_modification_preview_ids == ["modify-preview-1"]


async def test_single_conditional_order_uses_same_approval_gate() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    preview = await service.process_message(
        session_id="single-conditional",
        text=("삼성전자 80,000원이 되면 시장가로 2주 매도 조건주문 만료일 2026-09-30"),
    )

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    request = fake.single_conditional_preview_requests[0]
    assert str(request.trigger_price) == "80000"
    assert request.order_price is None
    assert fake.executed_single_conditional_preview_ids == []

    execution = await service.process_message(session_id="single-conditional", text="승인")

    assert execution.status is AgentStatus.COMPLETED
    assert fake.executed_single_conditional_preview_ids == ["single-preview-1"]


async def test_conditional_order_cancellation_can_be_declined_without_execution() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    preview = await service.process_message(
        session_id="conditional-cancel",
        text="조건주문번호 conditional-123 취소해줘",
    )
    declined = await service.process_message(session_id="conditional-cancel", text="취소")

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert declined.status is AgentStatus.CANCELLED
    assert fake.approved_conditional_cancellation_preview_ids == []
    assert fake.executed_conditional_cancellation_preview_ids == []


async def test_oco_conditional_order_executes_only_after_confirmation() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)
    command = (
        "삼성전자 2주 OCO 조건주문 "
        "첫 조건 감시가 80,000원 주문가 79,000원 "
        "둘째 조건 감시가 65,000원 주문가 64,900원 "
        "만료일 2026-09-30"
    )

    preview = await service.process_message(session_id="oco", text=command)

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    request = fake.oco_preview_requests[0]
    assert request.first.side.value == "SELL"
    assert request.second.side.value == "SELL"
    assert fake.executed_oco_preview_ids == []

    execution = await service.process_message(session_id="oco", text="승인")

    assert execution.status is AgentStatus.COMPLETED
    assert fake.executed_oco_preview_ids == ["oco-preview-1"]


async def test_oto_conditional_order_preserves_buy_then_sell_order() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)
    command = (
        "삼성전자 2주 OTO 조건주문 "
        "첫 조건 감시가 68,000원 주문가 69,000원 "
        "둘째 조건 감시가 79,000원 주문가 80,000원 "
        "만료일 2026-09-30"
    )

    preview = await service.process_message(session_id="oto", text=command)

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    request = fake.oto_preview_requests[0]
    assert request.first.side.value == "BUY"
    assert request.second.side.value == "SELL"

    execution = await service.process_message(session_id="oto", text="승인")

    assert execution.status is AgentStatus.COMPLETED
    assert fake.executed_oto_preview_ids == ["oto-preview-1"]


async def test_dual_conditional_order_missing_second_condition_is_blocked() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)
    command = "삼성전자 2주 OCO 조건주문 첫 조건 감시가 80,000원 주문가 79,000원 만료일 2026-09-30"

    response = await service.process_message(session_id="incomplete-oco", text=command)

    assert response.status is AgentStatus.NEEDS_INPUT
    assert fake.oco_preview_requests == []


async def test_conditional_order_modification_sends_full_replacement() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)
    command = (
        "조건주문번호 conditional-123을 OCO 조건주문으로 정정 2주 "
        "첫 조건 감시가 80,000원 주문가 79,000원 "
        "둘째 조건 감시가 65,000원 주문가 64,900원 "
        "만료일 2026-09-30"
    )

    preview = await service.process_message(session_id="conditional-modify", text=command)

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    request = fake.conditional_modification_preview_requests[0]
    assert request.conditional_order_id == "conditional-123"
    assert request.type.value == "OCO"
    assert request.second is not None
    assert fake.executed_conditional_modification_preview_ids == []

    execution = await service.process_message(session_id="conditional-modify", text="승인")

    assert execution.status is AgentStatus.COMPLETED
    assert execution.data is not None
    assert execution.data["replacementConditionalOrderId"] == "conditional-replacement-123"
    assert fake.executed_conditional_modification_preview_ids == ["conditional-modify-preview-1"]

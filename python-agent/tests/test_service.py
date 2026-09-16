import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from langgraph.checkpoint.memory import InMemorySaver

from jusika_agent.concurrency import SessionBusyError
from jusika_agent.graph import build_agent_graph
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from jusika_agent.models import AccountResponse, AgentStatus, Intent, ParsedIntent
from jusika_agent.service import AgentService
from jusika_agent.spring_client import SpringBackendClient
from tests.fakes import FakeSpringGateway


def create_service(fake: FakeSpringGateway) -> AgentService:
    graph = build_agent_graph(
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    return AgentService(graph)


class MultipleAccountFakeSpringGateway(FakeSpringGateway):
    async def list_accounts(self) -> list[AccountResponse]:
        return [
            AccountResponse(
                account_seq=1,
                masked_account_number="****1234",
                account_type="GENERAL",
            ),
            AccountResponse(
                account_seq=2,
                masked_account_number="****5678",
                account_type="ISA",
            ),
        ]


class AlwaysBusyGuard:
    @asynccontextmanager
    async def hold(self, session_id: str) -> AsyncIterator[None]:
        del session_id
        raise SessionBusyError
        yield


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


async def test_concurrent_duplicate_commands_create_only_one_preview() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    responses = await asyncio.gather(
        service.process_message(session_id="duplicate-command", text="삼성전자 5주 사줘"),
        service.process_message(session_id="duplicate-command", text="삼성전자 5주 사줘"),
    )

    assert [response.status for response in responses] == [
        AgentStatus.WAITING_CONFIRMATION,
        AgentStatus.WAITING_CONFIRMATION,
    ]
    assert responses[0].preview_id == responses[1].preview_id
    assert len(fake.preview_requests) == 1


async def test_concurrent_duplicate_approvals_execute_only_once() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)
    preview = await service.process_message(
        session_id="duplicate-approval",
        text="삼성전자 5주 사줘",
    )

    responses = await asyncio.gather(
        service.process_message(session_id="duplicate-approval", text="승인"),
        service.process_message(session_id="duplicate-approval", text="승인"),
    )

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert sum(response.status is AgentStatus.COMPLETED for response in responses) == 1
    assert sum(response.status is AgentStatus.NEEDS_INPUT for response in responses) == 1
    assert fake.approved_preview_ids == ["preview-1"]
    assert fake.executed_preview_ids == ["preview-1"]


async def test_busy_session_returns_safe_error_without_starting_graph() -> None:
    fake = FakeSpringGateway()
    graph = build_agent_graph(
        interpreter=RuleBasedCommandInterpreter(),
        spring=fake,
        checkpointer=InMemorySaver(),
    )
    service = AgentService(graph, session_guard=AlwaysBusyGuard())

    response = await service.process_message(session_id="busy-session", text="삼성전자 5주 사줘")

    assert response.status is AgentStatus.ERROR
    assert "이전 요청을 처리 중" in response.message
    assert fake.preview_requests == []


async def test_mutation_transport_failure_is_spoken_without_automatic_resubmission() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        if request.method == "GET" and request.url.path == "/api/accounts":
            return httpx.Response(
                200,
                json=[
                    {
                        "accountSeq": 1,
                        "maskedAccountNumber": "****1234",
                        "accountType": "GENERAL",
                    }
                ],
            )
        raise httpx.ReadTimeout("token=private-timeout", request=request)

    spring = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        retry_base_delay_seconds=0,
        transport=httpx.MockTransport(handler),
    )
    graph = build_agent_graph(
        interpreter=RuleBasedCommandInterpreter(),
        spring=spring,
        checkpointer=InMemorySaver(),
    )
    try:
        response = await AgentService(graph).process_message(
            session_id="mutation-timeout",
            text="삼성전자 5주 사줘",
        )
    finally:
        await spring.aclose()

    assert response.status is AgentStatus.ERROR
    assert "자동 재전송하지 않았습니다" in response.message
    assert "private-timeout" not in response.message
    assert [request.method for request in captured] == ["GET", "POST"]


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


async def test_buying_power_query_uses_requested_currency() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="buying-power", text="달러 주문 가능 금액 알려줘"
    )

    assert response.status is AgentStatus.COMPLETED
    assert response.data is not None
    assert response.data["currency"] == "USD"
    assert fake.buying_power_requests == [(1, "USD")]


async def test_commissions_query_returns_all_markets() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(session_id="commissions", text="내 주식 수수료 알려줘")

    assert response.status is AgentStatus.COMPLETED
    assert "국내 시장" in response.message
    assert "미국 시장" in response.message
    assert fake.commission_requests == [1]


async def test_sellable_quantity_query_resolves_instrument() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="sellable", text="삼성전자 매도 가능 수량 알려줘"
    )

    assert response.status is AgentStatus.COMPLETED
    assert "사 주" in response.message
    assert fake.sellable_quantity_requests == [(1, "005930")]


async def test_closed_order_history_query() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="order-history", text="지난 주문 내역 알려줘"
    )

    assert response.status is AgentStatus.COMPLETED
    assert "최근 종료 주문" in response.message
    assert response.data is not None
    assert response.data["listStatus"] == "CLOSED"


async def test_all_order_history_combines_open_and_closed_orders() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="all-order-history", text="전체 주문 내역 알려줘"
    )

    assert response.status is AgentStatus.COMPLETED
    assert "미체결 주문" in response.message
    assert "최근 종료 주문" in response.message
    assert response.data is not None
    assert response.data["open"]["listStatus"] == "OPEN"
    assert response.data["closed"]["listStatus"] == "CLOSED"


async def test_order_detail_query() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="order-detail", text="주문번호 order-123 상태 알려줘"
    )

    assert response.status is AgentStatus.COMPLETED
    assert "order-123" in response.message
    assert response.data is not None
    assert response.data["orderId"] == "order-123"


async def test_conditional_order_detail_query() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="conditional-detail",
        text="조건주문번호 conditional-123 상세 알려줘",
    )

    assert response.status is AgentStatus.COMPLETED
    assert "conditional-123" in response.message
    assert response.data is not None
    assert response.data["conditionalOrderId"] == "conditional-123"


async def test_execution_status_query_supports_amount_orders() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    response = await service.process_message(
        session_id="execution-status",
        text="금액 주문 실행번호 amount-execution-1 상태 알려줘",
    )

    assert response.status is AgentStatus.COMPLETED
    assert "ACCEPTED" in response.message
    assert fake.execution_status_requests == [("AMOUNT_ORDER", "amount-execution-1")]


async def test_execution_recovery_requires_confirmation() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    preview = await service.process_message(
        session_id="execution-recovery",
        text="실행번호 execution-unknown 복구해줘",
    )
    execution = await service.process_message(session_id="execution-recovery", text="승인")

    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert fake.execution_recovery_requests == [("ORDER", "execution-unknown")]
    assert execution.status is AgentStatus.COMPLETED


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


async def test_missing_stock_and_quantity_are_collected_across_turns() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    stock_prompt = await service.process_message(session_id="slot-buy", text="사줘")
    quantity_prompt = await service.process_message(session_id="slot-buy", text="삼성전자")
    preview = await service.process_message(session_id="slot-buy", text="5주")

    assert stock_prompt.status is AgentStatus.NEEDS_INPUT
    assert "어느 종목" in stock_prompt.message
    assert quantity_prompt.status is AgentStatus.NEEDS_INPUT
    assert "몇 주" in quantity_prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert len(fake.preview_requests) == 1
    assert fake.preview_requests[0].symbol == "005930"
    assert str(fake.preview_requests[0].quantity) == "5"
    assert fake.account_list_calls == 1


async def test_missing_limit_price_is_collected_before_preview() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    prompt = await service.process_message(
        session_id="slot-limit", text="삼성전자 5주 지정가로 사줘"
    )
    preview = await service.process_message(session_id="slot-limit", text="70,000원")

    assert prompt.status is AgentStatus.NEEDS_INPUT
    assert "지정가 주문 가격" in prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert str(fake.preview_requests[0].price) == "70000"


async def test_missing_amount_is_collected_for_us_market_buy() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    prompt = await service.process_message(session_id="slot-amount", text="애플 금액으로 사줘")
    preview = await service.process_message(session_id="slot-amount", text="200달러")

    assert prompt.status is AgentStatus.NEEDS_INPUT
    assert "달러 금액" in prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert str(fake.amount_preview_requests[0].order_amount) == "200"


async def test_missing_order_id_is_collected_for_cancellation() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    prompt = await service.process_message(session_id="slot-cancel", text="주문 취소해줘")
    preview = await service.process_message(session_id="slot-cancel", text="order-123")

    assert prompt.status is AgentStatus.NEEDS_INPUT
    assert "주문번호" in prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert fake.cancellation_preview_requests[0].order_id == "order-123"


async def test_order_modification_collects_id_then_domestic_quantity() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    id_prompt = await service.process_message(
        session_id="slot-modify", text="71,000원으로 주문 정정해줘"
    )
    quantity_prompt = await service.process_message(session_id="slot-modify", text="order-123")
    preview = await service.process_message(session_id="slot-modify", text="7주")

    assert id_prompt.status is AgentStatus.NEEDS_INPUT
    assert "주문번호" in id_prompt.message
    assert quantity_prompt.status is AgentStatus.NEEDS_INPUT
    assert "전체 주문 수량" in quantity_prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    request = fake.modification_preview_requests[0]
    assert request.order_id == "order-123"
    assert str(request.quantity) == "7"
    assert str(request.price) == "71000"


async def test_conditional_cancellation_collects_id() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    prompt = await service.process_message(
        session_id="slot-conditional-cancel", text="조건 주문 취소해줘"
    )
    preview = await service.process_message(
        session_id="slot-conditional-cancel", text="conditional-123"
    )

    assert prompt.status is AgentStatus.NEEDS_INPUT
    assert "조건주문번호" in prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert (
        fake.conditional_cancellation_preview_requests[0].conditional_order_id == "conditional-123"
    )


async def test_slot_collection_can_be_cancelled_without_preview() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    await service.process_message(session_id="slot-decline", text="삼성전자 사줘")
    cancelled = await service.process_message(session_id="slot-decline", text="그만")

    assert cancelled.status is AgentStatus.CANCELLED
    assert fake.preview_requests == []


async def test_single_conditional_order_collects_quantity_and_expiry() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)

    quantity_prompt = await service.process_message(
        session_id="slot-single",
        text="삼성전자 80,000원이 되면 시장가 매도 조건주문",
    )
    expiry_prompt = await service.process_message(session_id="slot-single", text="2주")
    preview = await service.process_message(session_id="slot-single", text="2026-09-30")

    assert quantity_prompt.status is AgentStatus.NEEDS_INPUT
    assert "수량" in quantity_prompt.message
    assert expiry_prompt.status is AgentStatus.NEEDS_INPUT
    assert "만료일" in expiry_prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    request = fake.single_conditional_preview_requests[0]
    assert str(request.quantity) == "2"
    assert request.expire_date.isoformat() == "2026-09-30"


async def test_oco_collects_missing_second_condition() -> None:
    fake = FakeSpringGateway()
    service = create_service(fake)
    command = "삼성전자 2주 OCO 조건주문 첫 조건 감시가 80,000원 주문가 79,000원 만료일 2026-09-30"

    prompt = await service.process_message(session_id="slot-oco", text=command)
    preview = await service.process_message(
        session_id="slot-oco", text="감시가 65,000원 주문가 64,900원"
    )

    assert prompt.status is AgentStatus.NEEDS_INPUT
    assert "둘째 조건" in prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert str(fake.oco_preview_requests[0].second.trigger_price) == "65000"


async def test_multiple_accounts_are_selected_before_preview() -> None:
    fake = MultipleAccountFakeSpringGateway()
    service = create_service(fake)

    prompt = await service.process_message(session_id="slot-account", text="삼성전자 5주 사줘")
    preview = await service.process_message(session_id="slot-account", text="2번")

    assert prompt.status is AgentStatus.NEEDS_INPUT
    assert "1번 ****1234" in prompt.message
    assert "2번 ****5678" in prompt.message
    assert preview.status is AgentStatus.WAITING_CONFIRMATION
    assert fake.preview_requests[0].account_seq == 2


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

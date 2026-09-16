import json
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any

import httpx
import pytest

from jusika_agent.models import (
    AmountOrderPreviewRequest,
    ConditionalOrderCancellationPreviewRequest,
    ConditionalOrderModificationPreviewRequest,
    ConditionalOrderMutationCondition,
    ConditionalOrderType,
    DualConditionalOrderPreviewRequest,
    OrderCancellationPreviewRequest,
    OrderModificationPreviewRequest,
    OrderPreviewRequest,
    OrderSide,
    OrderType,
    SingleConditionalOrderPreviewRequest,
)
from jusika_agent.spring_client import SpringBackendClient, SpringBackendError


def preview_payload() -> dict[str, Any]:
    now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC).isoformat()
    return {
        "previewId": "preview-1",
        "createdAt": now,
        "expiresAt": now,
        "accountSeq": 1,
        "symbol": "005930",
        "side": "BUY",
        "orderType": "MARKET",
        "quantity": 5,
        "requestedPrice": None,
        "referencePrice": 72300,
        "calculationPrice": 72300,
        "currency": "KRW",
        "marketCountry": "KR",
        "estimatedOrderAmount": 361500,
        "estimatedCommission": 10,
        "estimatedAmountAfterCommission": 361510,
        "requiresHighValueConfirmation": False,
        "orderReady": True,
        "status": "PENDING_APPROVAL",
        "approvedAt": None,
    }


def account_payload() -> list[dict[str, Any]]:
    return [
        {
            "accountSeq": 1,
            "maskedAccountNumber": "****1234",
            "accountType": "GENERAL",
        }
    ]


async def test_preview_uses_order_key_and_spring_contract() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=preview_payload())

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        transport=httpx.MockTransport(handler),
    )
    try:
        preview = await client.create_order_preview(
            OrderPreviewRequest(
                account_seq=1,
                symbol="005930",
                side=OrderSide.BUY,
                order_type=OrderType.MARKET,
                quantity=5,
            )
        )
    finally:
        await client.aclose()

    request = captured[0]
    assert request.url.path == "/api/orders/preview"
    assert request.headers["X-Jusika-Api-Key"] == "order-secret"
    assert request.headers["X-Jusika-Request-Id"]
    assert json.loads(request.content)["accountSeq"] == 1
    assert preview.preview_id == "preview-1"


async def test_backend_error_does_not_expose_raw_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(503, json={"detail": "token=super-secret account=123"})

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        read_max_attempts=1,
        transport=httpx.MockTransport(handler),
    )
    try:
        try:
            await client.list_accounts()
        except SpringBackendError as error:
            assert "super-secret" not in str(error)
            assert error.status_code == 503
        else:
            raise AssertionError("SpringBackendError was not raised")
    finally:
        await client.aclose()


async def test_exchange_rate_query_uses_public_spring_endpoint() -> None:
    captured: list[httpx.Request] = []
    now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC).isoformat()

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "baseCurrency": "USD",
                "quoteCurrency": "KRW",
                "rate": 1380.5,
                "midRate": 1375,
                "basisPoint": 40,
                "rateChangeType": "UP",
                "validFrom": now,
                "validUntil": now,
            },
        )

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        transport=httpx.MockTransport(handler),
    )
    try:
        response = await client.get_exchange_rate("USD", "KRW")
    finally:
        await client.aclose()

    request = captured[0]
    assert request.url.path == "/api/market/exchange-rate"
    assert dict(request.url.params) == {"baseCurrency": "USD", "quoteCurrency": "KRW"}
    assert "X-Jusika-Api-Key" not in request.headers
    assert response.rate == Decimal("1380.5")


async def test_new_mutation_clients_use_separate_spring_endpoints_and_order_key() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(503, json={"detail": "disabled for boundary test"})

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        transport=httpx.MockTransport(handler),
    )
    try:
        first = ConditionalOrderMutationCondition(
            side=OrderSide.SELL,
            trigger_price=80000,
            order_price=79000,
        )
        second = ConditionalOrderMutationCondition(
            side=OrderSide.SELL,
            trigger_price=65000,
            order_price=64900,
        )
        dual_request = DualConditionalOrderPreviewRequest(
            account_seq=1,
            symbol="005930",
            quantity=2,
            order_type=OrderType.LIMIT,
            expire_date=date(2026, 9, 30),
            first=first,
            second=second,
        )
        calls = [
            client.create_amount_order_preview(
                AmountOrderPreviewRequest(account_seq=1, symbol="AAPL", order_amount=200)
            ),
            client.create_order_cancellation_preview(
                OrderCancellationPreviewRequest(account_seq=1, order_id="order-123")
            ),
            client.create_order_modification_preview(
                OrderModificationPreviewRequest(
                    account_seq=1,
                    order_id="order-123",
                    order_type=OrderType.LIMIT,
                    quantity=7,
                    price=71000,
                )
            ),
            client.create_single_conditional_order_preview(
                SingleConditionalOrderPreviewRequest(
                    account_seq=1,
                    symbol="005930",
                    side=OrderSide.SELL,
                    order_type=OrderType.MARKET,
                    quantity=2,
                    trigger_price=80000,
                    order_price=None,
                    expire_date=date(2026, 9, 30),
                )
            ),
            client.create_conditional_order_cancellation_preview(
                ConditionalOrderCancellationPreviewRequest(
                    account_seq=1, conditional_order_id="conditional-123"
                )
            ),
            client.create_oco_conditional_order_preview(dual_request),
            client.create_oto_conditional_order_preview(
                dual_request.model_copy(
                    update={"first": first.model_copy(update={"side": OrderSide.BUY})}
                )
            ),
            client.create_conditional_order_modification_preview(
                ConditionalOrderModificationPreviewRequest(
                    account_seq=1,
                    conditional_order_id="conditional-123",
                    type=ConditionalOrderType.OCO,
                    quantity=2,
                    order_type=OrderType.LIMIT,
                    expire_date=date(2026, 9, 30),
                    first=first,
                    second=second,
                )
            ),
        ]
        for call in calls:
            with pytest.raises(SpringBackendError):
                await call
    finally:
        await client.aclose()

    assert [request.url.path for request in captured] == [
        "/api/orders/amount/preview",
        "/api/orders/cancellations/preview",
        "/api/orders/modifications/preview",
        "/api/conditional-orders/single/preview",
        "/api/conditional-orders/cancellations/preview",
        "/api/conditional-orders/oco/preview",
        "/api/conditional-orders/oto/preview",
        "/api/conditional-orders/modifications/preview",
    ]
    assert all(request.headers["X-Jusika-Api-Key"] == "order-secret" for request in captured)
    assert json.loads(captured[0].content) == {
        "accountSeq": 1,
        "symbol": "AAPL",
        "orderAmount": "200",
    }
    assert json.loads(captured[1].content) == {"accountSeq": 1, "orderId": "order-123"}
    assert json.loads(captured[3].content)["triggerPrice"] == "80000"


async def test_open_order_queries_use_read_key_and_open_filter() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(503)

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        read_max_attempts=1,
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(SpringBackendError):
            await client.list_open_orders(1)
        with pytest.raises(SpringBackendError):
            await client.list_open_conditional_orders(1)
    finally:
        await client.aclose()

    assert [request.url.path for request in captured] == [
        "/api/accounts/1/orders",
        "/api/accounts/1/conditional-orders",
    ]
    assert all(request.url.params["status"] == "OPEN" for request in captured)
    assert all(request.headers["X-Jusika-Api-Key"] == "read-secret" for request in captured)


async def test_extended_queries_and_recovery_use_expected_paths_and_authorities() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(503)

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        read_max_attempts=1,
        transport=httpx.MockTransport(handler),
    )
    try:
        calls = [
            client.get_buying_power(1, "USD"),
            client.get_commissions(1),
            client.get_sellable_quantity(1, "005930"),
            client.list_order_history(1),
            client.get_order(1, "order-123"),
            client.get_conditional_order(1, "conditional-123"),
            client.get_order_execution("execution-1"),
            client.get_amount_order_execution("amount-execution-1"),
            client.recover_order_execution("execution-1"),
            client.recover_amount_order_execution("amount-execution-1"),
        ]
        for call in calls:
            with pytest.raises(SpringBackendError):
                await call
    finally:
        await client.aclose()

    assert [request.url.path for request in captured] == [
        "/api/accounts/1/buying-power",
        "/api/accounts/1/commissions",
        "/api/accounts/1/stocks/005930/sellable-quantity",
        "/api/accounts/1/orders",
        "/api/accounts/1/orders/order-123",
        "/api/accounts/1/conditional-orders/conditional-123",
        "/api/orders/executions/execution-1",
        "/api/orders/amount/executions/amount-execution-1",
        "/api/orders/executions/execution-1/recover",
        "/api/orders/amount/executions/amount-execution-1/recover",
    ]
    assert captured[0].url.params["currency"] == "USD"
    assert captured[3].url.params["status"] == "CLOSED"
    assert captured[3].url.params["limit"] == "20"
    assert all(request.headers["X-Jusika-Api-Key"] == "read-secret" for request in captured[:8])
    assert all(request.headers["X-Jusika-Api-Key"] == "order-secret" for request in captured[8:])


async def test_read_request_retries_transient_status_with_same_request_id() -> None:
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        if len(captured) < 3:
            return httpx.Response(503, json={"detail": "private backend detail"})
        return httpx.Response(200, json=account_payload())

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        read_max_attempts=3,
        retry_base_delay_seconds=0,
        transport=httpx.MockTransport(handler),
    )
    try:
        accounts = await client.list_accounts()
    finally:
        await client.aclose()

    assert accounts[0].account_seq == 1
    assert len(captured) == 3
    assert len({request.headers["X-Jusika-Request-Id"] for request in captured}) == 1


async def test_read_request_retries_timeout_then_recovers() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ReadTimeout("private timeout detail", request=request)
        return httpx.Response(200, json=account_payload())

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        retry_base_delay_seconds=0,
        transport=httpx.MockTransport(handler),
    )
    try:
        accounts = await client.list_accounts()
    finally:
        await client.aclose()

    assert accounts[0].account_seq == 1
    assert attempts == 2


async def test_read_request_does_not_retry_permanent_status() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        del request
        attempts += 1
        return httpx.Response(404, json={"detail": "private account detail"})

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        retry_base_delay_seconds=0,
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(SpringBackendError, match="찾지 못했습니다") as captured:
            await client.list_accounts()
    finally:
        await client.aclose()

    assert captured.value.status_code == 404
    assert attempts == 1


async def test_mutation_timeout_is_not_retried_and_marks_unknown_outcome() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.ReadTimeout("private timeout detail", request=request)

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        retry_base_delay_seconds=0,
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(SpringBackendError, match="자동 재전송하지 않았습니다") as captured:
            await client.create_order_preview(
                OrderPreviewRequest(
                    account_seq=1,
                    symbol="005930",
                    side=OrderSide.BUY,
                    order_type=OrderType.MARKET,
                    quantity=5,
                )
            )
    finally:
        await client.aclose()

    assert captured.value.outcome_unknown is True
    assert "private timeout detail" not in str(captured.value)
    assert attempts == 1


async def test_mutation_5xx_is_not_retried_or_exposed() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        del request
        attempts += 1
        return httpx.Response(502, json={"detail": "token=private-secret"})

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="read-secret",
        order_api_key="order-secret",
        retry_base_delay_seconds=0,
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(SpringBackendError, match="주문 상태를 확인") as captured:
            await client.approve_order_preview("preview-1")
    finally:
        await client.aclose()

    assert captured.value.status_code == 502
    assert captured.value.outcome_unknown is True
    assert "private-secret" not in str(captured.value)
    assert attempts == 1


@pytest.mark.parametrize(
    "kwargs",
    [
        {"read_max_attempts": 0},
        {"read_max_attempts": 6},
        {"retry_base_delay_seconds": -1},
    ],
)
def test_spring_client_rejects_unsafe_retry_limits(kwargs: dict[str, Any]) -> None:
    with pytest.raises(ValueError):
        SpringBackendClient(
            base_url="http://spring.test",
            read_api_key="read-secret",
            order_api_key="order-secret",
            **kwargs,
        )

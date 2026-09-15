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

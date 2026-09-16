"""Typed and sanitized HTTP client for the Spring transaction backend."""

import asyncio
from collections.abc import Mapping
from typing import Any, TypeVar
from urllib.parse import quote
from uuid import uuid4

import httpx
from pydantic import BaseModel, TypeAdapter, ValidationError

from jusika_agent.models import (
    AccountResponse,
    AmountOrderExecutionResponse,
    AmountOrderPreviewRequest,
    AmountOrderPreviewResponse,
    BuyingPowerResponse,
    CommissionsResponse,
    ConditionalOrderCancellationExecutionResponse,
    ConditionalOrderCancellationPreviewRequest,
    ConditionalOrderCancellationPreviewResponse,
    ConditionalOrderCreationExecutionResponse,
    ConditionalOrderDetailResponse,
    ConditionalOrderListResponse,
    ConditionalOrderModificationExecutionResponse,
    ConditionalOrderModificationPreviewRequest,
    ConditionalOrderModificationPreviewResponse,
    DualConditionalOrderPreviewRequest,
    ExchangeRateResponse,
    HoldingsResponse,
    OcoConditionalOrderPreviewResponse,
    OrderCancellationExecutionResponse,
    OrderCancellationPreviewRequest,
    OrderCancellationPreviewResponse,
    OrderDetailResponse,
    OrderExecutionResponse,
    OrderListResponse,
    OrderModificationExecutionResponse,
    OrderModificationPreviewRequest,
    OrderModificationPreviewResponse,
    OrderPreviewRequest,
    OrderPreviewResponse,
    OtoConditionalOrderPreviewResponse,
    SellableQuantityResponse,
    SingleConditionalOrderExecutionResponse,
    SingleConditionalOrderPreviewRequest,
    SingleConditionalOrderPreviewResponse,
    StockPriceResponse,
)


class SpringBackendError(RuntimeError):
    """Safe error that never carries raw broker responses or credentials."""

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        outcome_unknown: bool = False,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.outcome_unknown = outcome_unknown


ResponseModel = TypeVar("ResponseModel", bound=BaseModel)


class SpringBackendClient:
    """The only Python boundary allowed to call the Spring backend."""

    def __init__(
        self,
        *,
        base_url: str,
        read_api_key: str,
        order_api_key: str,
        connect_timeout_seconds: float = 3.0,
        read_timeout_seconds: float = 8.0,
        read_max_attempts: int = 3,
        retry_base_delay_seconds: float = 0.25,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not 1 <= read_max_attempts <= 5:
            raise ValueError("Spring 조회 최대 시도 횟수는 1에서 5 사이여야 합니다.")
        if retry_base_delay_seconds < 0:
            raise ValueError("Spring 재시도 대기 시간은 0 이상이어야 합니다.")
        timeout = httpx.Timeout(
            connect=connect_timeout_seconds,
            read=read_timeout_seconds,
            write=read_timeout_seconds,
            pool=connect_timeout_seconds,
        )
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=timeout,
            transport=transport,
        )
        self._read_api_key = read_api_key
        self._order_api_key = order_api_key
        self._read_max_attempts = read_max_attempts
        self._retry_base_delay_seconds = retry_base_delay_seconds

    async def aclose(self) -> None:
        await self._client.aclose()

    async def list_accounts(self) -> list[AccountResponse]:
        data = await self._request_json("GET", "/api/accounts", authority="read")
        try:
            return TypeAdapter(list[AccountResponse]).validate_python(data)
        except ValidationError as exc:
            raise SpringBackendError("계좌 응답 형식이 예상과 다릅니다.") from exc

    async def get_stock_price(self, symbol: str) -> StockPriceResponse:
        data = await self._request_json(
            "GET",
            f"/api/stocks/{quote(symbol, safe='')}/price",
            authority=None,
        )
        return self._validate(StockPriceResponse, data, "현재가")

    async def get_exchange_rate(
        self, base_currency: str, quote_currency: str
    ) -> ExchangeRateResponse:
        data = await self._request_json(
            "GET",
            "/api/market/exchange-rate",
            authority=None,
            params={
                "baseCurrency": base_currency,
                "quoteCurrency": quote_currency,
            },
        )
        return self._validate(ExchangeRateResponse, data, "환율")

    async def get_holdings(self, account_seq: int) -> HoldingsResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/holdings",
            authority="read",
        )
        return self._validate(HoldingsResponse, data, "보유자산")

    async def get_buying_power(self, account_seq: int, currency: str) -> BuyingPowerResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/buying-power",
            authority="read",
            params={"currency": currency},
        )
        return self._validate(BuyingPowerResponse, data, "매수 가능 금액")

    async def get_commissions(self, account_seq: int) -> CommissionsResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/commissions",
            authority="read",
        )
        return self._validate(CommissionsResponse, data, "수수료")

    async def get_sellable_quantity(
        self, account_seq: int, symbol: str
    ) -> SellableQuantityResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/stocks/{quote(symbol, safe='')}/sellable-quantity",
            authority="read",
        )
        return self._validate(SellableQuantityResponse, data, "매도 가능 수량")

    async def create_order_preview(self, request: OrderPreviewRequest) -> OrderPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/orders/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(OrderPreviewResponse, data, "주문 미리보기")

    async def approve_order_preview(self, preview_id: str) -> OrderPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(OrderPreviewResponse, data, "주문 승인")

    async def execute_order_preview(self, preview_id: str) -> OrderExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(OrderExecutionResponse, data, "주문 실행")

    async def create_amount_order_preview(
        self, request: AmountOrderPreviewRequest
    ) -> AmountOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/orders/amount/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(AmountOrderPreviewResponse, data, "금액 주문 미리보기")

    async def approve_amount_order_preview(self, preview_id: str) -> AmountOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/amount/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(AmountOrderPreviewResponse, data, "금액 주문 승인")

    async def execute_amount_order_preview(self, preview_id: str) -> AmountOrderExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/amount/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(AmountOrderExecutionResponse, data, "금액 주문 실행")

    async def list_open_orders(self, account_seq: int) -> OrderListResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/orders",
            authority="read",
            params={"status": "OPEN"},
        )
        return self._validate(OrderListResponse, data, "미체결 주문 목록")

    async def list_order_history(self, account_seq: int) -> OrderListResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/orders",
            authority="read",
            params={"status": "CLOSED", "limit": "20"},
        )
        return self._validate(OrderListResponse, data, "주문 내역")

    async def get_order(self, account_seq: int, order_id: str) -> OrderDetailResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/orders/{quote(order_id, safe='')}",
            authority="read",
        )
        return self._validate(OrderDetailResponse, data, "주문 상세")

    async def get_order_execution(self, execution_id: str) -> OrderExecutionResponse:
        data = await self._request_json(
            "GET",
            f"/api/orders/executions/{quote(execution_id, safe='')}",
            authority="read",
        )
        return self._validate(OrderExecutionResponse, data, "주문 실행 상태")

    async def recover_order_execution(self, execution_id: str) -> OrderExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/executions/{quote(execution_id, safe='')}/recover",
            authority="order",
        )
        return self._validate(OrderExecutionResponse, data, "주문 실행 복구")

    async def get_amount_order_execution(self, execution_id: str) -> AmountOrderExecutionResponse:
        data = await self._request_json(
            "GET",
            f"/api/orders/amount/executions/{quote(execution_id, safe='')}",
            authority="read",
        )
        return self._validate(AmountOrderExecutionResponse, data, "금액 주문 실행 상태")

    async def recover_amount_order_execution(
        self, execution_id: str
    ) -> AmountOrderExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/amount/executions/{quote(execution_id, safe='')}/recover",
            authority="order",
        )
        return self._validate(AmountOrderExecutionResponse, data, "금액 주문 실행 복구")

    async def create_order_cancellation_preview(
        self, request: OrderCancellationPreviewRequest
    ) -> OrderCancellationPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/orders/cancellations/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(OrderCancellationPreviewResponse, data, "주문 취소 미리보기")

    async def approve_order_cancellation_preview(
        self, preview_id: str
    ) -> OrderCancellationPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/cancellations/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(OrderCancellationPreviewResponse, data, "주문 취소 승인")

    async def execute_order_cancellation_preview(
        self, preview_id: str
    ) -> OrderCancellationExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/cancellations/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(OrderCancellationExecutionResponse, data, "주문 취소 실행")

    async def create_order_modification_preview(
        self, request: OrderModificationPreviewRequest
    ) -> OrderModificationPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/orders/modifications/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(OrderModificationPreviewResponse, data, "주문 정정 미리보기")

    async def approve_order_modification_preview(
        self, preview_id: str
    ) -> OrderModificationPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/modifications/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(OrderModificationPreviewResponse, data, "주문 정정 승인")

    async def execute_order_modification_preview(
        self, preview_id: str
    ) -> OrderModificationExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/orders/modifications/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(OrderModificationExecutionResponse, data, "주문 정정 실행")

    async def list_open_conditional_orders(self, account_seq: int) -> ConditionalOrderListResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/conditional-orders",
            authority="read",
            params={"status": "OPEN"},
        )
        return self._validate(ConditionalOrderListResponse, data, "조건 주문 목록")

    async def get_conditional_order(
        self, account_seq: int, conditional_order_id: str
    ) -> ConditionalOrderDetailResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/conditional-orders/"
            f"{quote(conditional_order_id, safe='')}",
            authority="read",
        )
        return self._validate(ConditionalOrderDetailResponse, data, "조건 주문 상세")

    async def create_single_conditional_order_preview(
        self, request: SingleConditionalOrderPreviewRequest
    ) -> SingleConditionalOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/conditional-orders/single/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(
            SingleConditionalOrderPreviewResponse, data, "단일 조건 주문 미리보기"
        )

    async def approve_single_conditional_order_preview(
        self, preview_id: str
    ) -> SingleConditionalOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/single/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(SingleConditionalOrderPreviewResponse, data, "단일 조건 주문 승인")

    async def execute_single_conditional_order_preview(
        self, preview_id: str
    ) -> SingleConditionalOrderExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/single/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(SingleConditionalOrderExecutionResponse, data, "단일 조건 주문 실행")

    async def create_conditional_order_cancellation_preview(
        self, request: ConditionalOrderCancellationPreviewRequest
    ) -> ConditionalOrderCancellationPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/conditional-orders/cancellations/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(
            ConditionalOrderCancellationPreviewResponse, data, "조건 주문 취소 미리보기"
        )

    async def approve_conditional_order_cancellation_preview(
        self, preview_id: str
    ) -> ConditionalOrderCancellationPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/cancellations/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(
            ConditionalOrderCancellationPreviewResponse, data, "조건 주문 취소 승인"
        )

    async def execute_conditional_order_cancellation_preview(
        self, preview_id: str
    ) -> ConditionalOrderCancellationExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/cancellations/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(
            ConditionalOrderCancellationExecutionResponse, data, "조건 주문 취소 실행"
        )

    async def create_oco_conditional_order_preview(
        self, request: DualConditionalOrderPreviewRequest
    ) -> OcoConditionalOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/conditional-orders/oco/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(OcoConditionalOrderPreviewResponse, data, "OCO 조건 주문 미리보기")

    async def approve_oco_conditional_order_preview(
        self, preview_id: str
    ) -> OcoConditionalOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/oco/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(OcoConditionalOrderPreviewResponse, data, "OCO 조건 주문 승인")

    async def execute_oco_conditional_order_preview(
        self, preview_id: str
    ) -> ConditionalOrderCreationExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/oco/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(ConditionalOrderCreationExecutionResponse, data, "OCO 조건 주문 실행")

    async def create_oto_conditional_order_preview(
        self, request: DualConditionalOrderPreviewRequest
    ) -> OtoConditionalOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/conditional-orders/oto/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(OtoConditionalOrderPreviewResponse, data, "OTO 조건 주문 미리보기")

    async def approve_oto_conditional_order_preview(
        self, preview_id: str
    ) -> OtoConditionalOrderPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/oto/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(OtoConditionalOrderPreviewResponse, data, "OTO 조건 주문 승인")

    async def execute_oto_conditional_order_preview(
        self, preview_id: str
    ) -> ConditionalOrderCreationExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/oto/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(ConditionalOrderCreationExecutionResponse, data, "OTO 조건 주문 실행")

    async def create_conditional_order_modification_preview(
        self, request: ConditionalOrderModificationPreviewRequest
    ) -> ConditionalOrderModificationPreviewResponse:
        data = await self._request_json(
            "POST",
            "/api/conditional-orders/modifications/preview",
            authority="order",
            content=request.model_dump_json(by_alias=True),
        )
        return self._validate(
            ConditionalOrderModificationPreviewResponse, data, "조건 주문 정정 미리보기"
        )

    async def approve_conditional_order_modification_preview(
        self, preview_id: str
    ) -> ConditionalOrderModificationPreviewResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/modifications/previews/{quote(preview_id, safe='')}/approve",
            authority="order",
        )
        return self._validate(
            ConditionalOrderModificationPreviewResponse, data, "조건 주문 정정 승인"
        )

    async def execute_conditional_order_modification_preview(
        self, preview_id: str
    ) -> ConditionalOrderModificationExecutionResponse:
        data = await self._request_json(
            "POST",
            f"/api/conditional-orders/modifications/previews/{quote(preview_id, safe='')}/execute",
            authority="order",
        )
        return self._validate(
            ConditionalOrderModificationExecutionResponse, data, "조건 주문 정정 실행"
        )

    async def _request_json(
        self,
        method: str,
        path: str,
        *,
        authority: str | None,
        content: str | None = None,
        params: Mapping[str, str] | None = None,
    ) -> Any:
        normalized_method = method.upper()
        headers = self._headers(authority)
        if content is not None:
            headers["Content-Type"] = "application/json"
        max_attempts = self._read_max_attempts if normalized_method == "GET" else 1

        for attempt in range(max_attempts):
            try:
                response = await self._client.request(
                    normalized_method,
                    path,
                    headers=headers,
                    content=content,
                    params=params,
                )
            except httpx.TimeoutException as exc:
                if attempt + 1 < max_attempts:
                    await asyncio.sleep(self._retry_delay(attempt))
                    continue
                raise SpringBackendError(
                    self._transport_error_message(normalized_method, timed_out=True),
                    outcome_unknown=normalized_method != "GET",
                ) from exc
            except httpx.RequestError as exc:
                if attempt + 1 < max_attempts:
                    await asyncio.sleep(self._retry_delay(attempt))
                    continue
                raise SpringBackendError(
                    self._transport_error_message(normalized_method, timed_out=False),
                    outcome_unknown=normalized_method != "GET",
                ) from exc

            if response.is_error:
                if attempt + 1 < max_attempts and response.status_code in {
                    408,
                    429,
                    500,
                    502,
                    503,
                    504,
                }:
                    await asyncio.sleep(self._retry_delay(attempt))
                    continue
                raise SpringBackendError(
                    self._safe_status_message(response.status_code, normalized_method),
                    status_code=response.status_code,
                    outcome_unknown=(
                        normalized_method != "GET"
                        and (
                            response.status_code == 408
                            or (response.status_code >= 500 and response.status_code != 503)
                        )
                    ),
                )
            try:
                return response.json()
            except ValueError as exc:
                raise SpringBackendError("금융 백엔드 응답을 읽지 못했습니다.") from exc

        raise AssertionError("Spring 조회 재시도 루프가 예상하지 못하게 종료되었습니다.")

    def _headers(self, authority: str | None) -> dict[str, str]:
        headers = {"X-Jusika-Request-Id": str(uuid4())}
        if authority == "read":
            headers["X-Jusika-Api-Key"] = self._read_api_key
        elif authority == "order":
            headers["X-Jusika-Api-Key"] = self._order_api_key
        return headers

    @staticmethod
    def _validate(model: type[ResponseModel], data: Any, label: str) -> ResponseModel:
        try:
            return model.model_validate(data)
        except ValidationError as exc:
            raise SpringBackendError(f"{label} 응답 형식이 예상과 다릅니다.") from exc

    @staticmethod
    def _transport_error_message(method: str, *, timed_out: bool) -> str:
        if method != "GET":
            return (
                "금융 변경 요청 결과를 확인하지 못했습니다. 안전을 위해 자동 재전송하지 "
                "않았습니다. 잠시 후 주문 상태를 확인해주세요."
            )
        if timed_out:
            return "금융 백엔드 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."
        return "금융 백엔드에 연결하지 못했습니다. 잠시 후 다시 시도해주세요."

    def _retry_delay(self, attempt: int) -> float:
        return float(self._retry_base_delay_seconds * (2**attempt))

    @staticmethod
    def _safe_status_message(status_code: int, method: str) -> str:
        messages: Mapping[int, str] = {
            400: "요청한 금융 정보가 올바르지 않습니다.",
            401: "금융 백엔드 인증에 실패했습니다.",
            403: "이 금융 요청을 수행할 권한이 없습니다.",
            404: "요청한 금융 정보를 찾지 못했습니다.",
            408: "금융 백엔드 응답 시간이 초과되었습니다.",
            409: "이미 처리되었거나 현재 상태에서 처리할 수 없는 주문입니다.",
            410: "주문 승인 시간이 만료되었습니다. 주문 내용을 다시 확인해주세요.",
            429: "금융 요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
            422: "최종 금융 검증을 통과하지 못했습니다.",
            502: "증권사 응답을 확인하지 못했습니다.",
            503: "금융 기능이 현재 안전하게 차단되어 있습니다.",
            504: "증권사 응답 시간이 초과되었습니다.",
        }
        message = messages.get(status_code, "금융 요청을 완료하지 못했습니다.")
        if method != "GET" and status_code in {408, 500, 502, 504}:
            return f"{message} 안전을 위해 자동 재전송하지 않았으니 주문 상태를 확인해주세요."
        return message

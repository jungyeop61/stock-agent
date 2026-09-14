"""Typed and sanitized HTTP client for the Spring transaction backend."""

from collections.abc import Mapping
from typing import Any, TypeVar
from urllib.parse import quote
from uuid import uuid4

import httpx
from pydantic import BaseModel, TypeAdapter, ValidationError

from jusika_agent.models import (
    AccountResponse,
    ConditionalOrderCancellationExecutionResponse,
    ConditionalOrderCancellationPreviewRequest,
    ConditionalOrderCancellationPreviewResponse,
    ConditionalOrderCreationExecutionResponse,
    ConditionalOrderListResponse,
    ConditionalOrderModificationExecutionResponse,
    ConditionalOrderModificationPreviewRequest,
    ConditionalOrderModificationPreviewResponse,
    DualConditionalOrderPreviewRequest,
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
    SingleConditionalOrderExecutionResponse,
    SingleConditionalOrderPreviewRequest,
    SingleConditionalOrderPreviewResponse,
    StockPriceResponse,
)


class SpringBackendError(RuntimeError):
    """Safe error that never carries raw broker responses or credentials."""

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


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
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
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

    async def get_holdings(self, account_seq: int) -> HoldingsResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/holdings",
            authority="read",
        )
        return self._validate(HoldingsResponse, data, "보유자산")

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

    async def list_open_orders(self, account_seq: int) -> OrderListResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/orders",
            authority="read",
            params={"status": "OPEN"},
        )
        return self._validate(OrderListResponse, data, "미체결 주문 목록")

    async def get_order(self, account_seq: int, order_id: str) -> OrderDetailResponse:
        data = await self._request_json(
            "GET",
            f"/api/accounts/{account_seq}/orders/{quote(order_id, safe='')}",
            authority="read",
        )
        return self._validate(OrderDetailResponse, data, "주문 상세")

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
        headers = self._headers(authority)
        if content is not None:
            headers["Content-Type"] = "application/json"
        try:
            response = await self._client.request(
                method, path, headers=headers, content=content, params=params
            )
        except httpx.RequestError as exc:
            raise SpringBackendError("금융 백엔드에 연결하지 못했습니다.") from exc
        if response.is_error:
            raise SpringBackendError(
                self._safe_status_message(response.status_code),
                status_code=response.status_code,
            )
        try:
            return response.json()
        except ValueError as exc:
            raise SpringBackendError("금융 백엔드 응답을 읽지 못했습니다.") from exc

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
    def _safe_status_message(status_code: int) -> str:
        messages: Mapping[int, str] = {
            400: "요청한 금융 정보가 올바르지 않습니다.",
            401: "금융 백엔드 인증에 실패했습니다.",
            403: "이 금융 요청을 수행할 권한이 없습니다.",
            404: "요청한 금융 정보를 찾지 못했습니다.",
            409: "이미 처리되었거나 현재 상태에서 처리할 수 없는 주문입니다.",
            410: "주문 승인 시간이 만료되었습니다. 주문 내용을 다시 확인해주세요.",
            422: "최종 금융 검증을 통과하지 못했습니다.",
            502: "증권사 응답을 확인하지 못했습니다.",
            503: "금융 기능이 현재 안전하게 차단되어 있습니다.",
            504: "증권사 응답 시간이 초과되었습니다.",
        }
        return messages.get(status_code, "금융 요청을 완료하지 못했습니다.")

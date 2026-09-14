"""Deterministic financial boundary used by agent tests."""

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

from jusika_agent.models import (
    AccountResponse,
    AmountOrderExecutionResponse,
    AmountOrderPreviewRequest,
    AmountOrderPreviewResponse,
    ConditionalOrderCancellationExecutionResponse,
    ConditionalOrderCancellationPreviewRequest,
    ConditionalOrderCancellationPreviewResponse,
    ConditionalOrderCondition,
    ConditionalOrderCreationExecutionResponse,
    ConditionalOrderDetailResponse,
    ConditionalOrderListResponse,
    ConditionalOrderModificationExecutionResponse,
    ConditionalOrderModificationPreviewRequest,
    ConditionalOrderModificationPreviewResponse,
    ConditionalOrderMutationCondition,
    ConditionalOrderType,
    DualConditionalOrderPreviewCondition,
    DualConditionalOrderPreviewRequest,
    HoldingItem,
    HoldingsResponse,
    OcoConditionalOrderPreviewResponse,
    OrderCancellationExecutionResponse,
    OrderCancellationPreviewRequest,
    OrderCancellationPreviewResponse,
    OrderDetailResponse,
    OrderExecutionDetail,
    OrderExecutionResponse,
    OrderListResponse,
    OrderModificationExecutionResponse,
    OrderModificationPreviewRequest,
    OrderModificationPreviewResponse,
    OrderPreviewRequest,
    OrderPreviewResponse,
    OrderSide,
    OrderType,
    OtoConditionalOrderPreviewResponse,
    SingleConditionalOrderExecutionResponse,
    SingleConditionalOrderPreviewRequest,
    SingleConditionalOrderPreviewResponse,
    StockPriceResponse,
)


class FakeSpringGateway:
    def __init__(self) -> None:
        self.preview_requests: list[OrderPreviewRequest] = []
        self.approved_preview_ids: list[str] = []
        self.executed_preview_ids: list[str] = []
        self.last_preview: OrderPreviewResponse | None = None
        self.amount_preview_requests: list[AmountOrderPreviewRequest] = []
        self.approved_amount_preview_ids: list[str] = []
        self.executed_amount_preview_ids: list[str] = []
        self.last_amount_preview: AmountOrderPreviewResponse | None = None
        self.cancellation_preview_requests: list[OrderCancellationPreviewRequest] = []
        self.modification_preview_requests: list[OrderModificationPreviewRequest] = []
        self.single_conditional_preview_requests: list[SingleConditionalOrderPreviewRequest] = []
        self.conditional_cancellation_preview_requests: list[
            ConditionalOrderCancellationPreviewRequest
        ] = []
        self.approved_cancellation_preview_ids: list[str] = []
        self.executed_cancellation_preview_ids: list[str] = []
        self.approved_modification_preview_ids: list[str] = []
        self.executed_modification_preview_ids: list[str] = []
        self.approved_single_conditional_preview_ids: list[str] = []
        self.executed_single_conditional_preview_ids: list[str] = []
        self.approved_conditional_cancellation_preview_ids: list[str] = []
        self.executed_conditional_cancellation_preview_ids: list[str] = []
        self.oco_preview_requests: list[DualConditionalOrderPreviewRequest] = []
        self.oto_preview_requests: list[DualConditionalOrderPreviewRequest] = []
        self.conditional_modification_preview_requests: list[
            ConditionalOrderModificationPreviewRequest
        ] = []
        self.approved_oco_preview_ids: list[str] = []
        self.executed_oco_preview_ids: list[str] = []
        self.approved_oto_preview_ids: list[str] = []
        self.executed_oto_preview_ids: list[str] = []
        self.approved_conditional_modification_preview_ids: list[str] = []
        self.executed_conditional_modification_preview_ids: list[str] = []

    async def list_accounts(self) -> list[AccountResponse]:
        return [
            AccountResponse(
                account_seq=1,
                masked_account_number="****1234",
                account_type="GENERAL",
            )
        ]

    async def get_stock_price(self, symbol: str) -> StockPriceResponse:
        return StockPriceResponse(
            symbol=symbol,
            price=Decimal("72300"),
            currency="KRW",
            timestamp=datetime(2026, 9, 14, 7, 0, tzinfo=UTC),
        )

    async def get_holdings(self, account_seq: int) -> HoldingsResponse:
        return HoldingsResponse(
            account_seq=account_seq,
            items=[
                HoldingItem(
                    symbol="005930",
                    name="삼성전자",
                    market_country="KR",
                    currency="KRW",
                    quantity=Decimal("5"),
                    last_price=Decimal("72300"),
                )
            ],
        )

    async def create_order_preview(self, request: OrderPreviewRequest) -> OrderPreviewResponse:
        self.preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        price = request.price or Decimal("72300")
        amount = price * request.quantity
        self.last_preview = OrderPreviewResponse(
            preview_id="preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            symbol=request.symbol,
            side=request.side,
            order_type=request.order_type,
            quantity=request.quantity,
            requested_price=request.price,
            reference_price=Decimal("72300"),
            calculation_price=price,
            currency="KRW",
            market_country="KR",
            estimated_order_amount=amount,
            estimated_commission=Decimal("10"),
            estimated_amount_after_commission=amount + Decimal("10"),
            requires_high_value_confirmation=False,
            order_ready=True,
            status="PENDING_APPROVAL",
            approved_at=None,
        )
        return self.last_preview

    async def approve_order_preview(self, preview_id: str) -> OrderPreviewResponse:
        self.approved_preview_ids.append(preview_id)
        preview = self.last_preview
        if preview is None:
            raise AssertionError("preview must be created before approval")
        return preview.model_copy(update={"status": "APPROVED", "approved_at": preview.created_at})

    async def execute_order_preview(self, preview_id: str) -> OrderExecutionResponse:
        self.executed_preview_ids.append(preview_id)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OrderExecutionResponse(
            execution_id="execution-1",
            preview_id=preview_id,
            client_order_id="client-order-1",
            broker_mode="MOCK",
            status="ACCEPTED",
            broker_order_id="mock-client-order-1",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            recovery_attempted_at=None,
            completed_at=now,
        )

    async def create_amount_order_preview(
        self, request: AmountOrderPreviewRequest
    ) -> AmountOrderPreviewResponse:
        self.amount_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        commission = Decimal("0.10")
        self.last_amount_preview = AmountOrderPreviewResponse(
            preview_id="amount-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            symbol=request.symbol,
            side=OrderSide.BUY,
            order_type=OrderType.MARKET,
            order_amount=request.order_amount,
            currency="USD",
            market_country="US",
            reference_price=Decimal("200"),
            estimated_quantity=request.order_amount / Decimal("200"),
            commission_rate=Decimal("0.0005"),
            estimated_commission=commission,
            estimated_total_cost=request.order_amount + commission,
            exchange_rate=Decimal("1400"),
            exchange_rate_valid_from=now - timedelta(minutes=1),
            exchange_rate_valid_until=now + timedelta(minutes=1),
            estimated_order_amount_krw=request.order_amount * Decimal("1400"),
            requires_high_value_confirmation=False,
            order_ready=True,
            status="PENDING_APPROVAL",
            approved_at=None,
        )
        return self.last_amount_preview

    async def approve_amount_order_preview(self, preview_id: str) -> AmountOrderPreviewResponse:
        self.approved_amount_preview_ids.append(preview_id)
        preview = self.last_amount_preview
        if preview is None:
            raise AssertionError("amount preview must be created before approval")
        return preview.model_copy(update={"status": "APPROVED", "approved_at": preview.created_at})

    async def execute_amount_order_preview(self, preview_id: str) -> AmountOrderExecutionResponse:
        self.executed_amount_preview_ids.append(preview_id)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return AmountOrderExecutionResponse(
            execution_id="amount-execution-1",
            preview_id=preview_id,
            client_order_id="amount-client-order-1",
            broker_mode="MOCK",
            status="ACCEPTED",
            broker_order_id="mock-amount-client-order-1",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            recovery_attempted_at=None,
            completed_at=now,
        )

    async def list_open_orders(self, account_seq: int) -> OrderListResponse:
        return OrderListResponse(
            account_seq=account_seq,
            list_status="OPEN",
            **{"from": None},
            to=None,
            symbol=None,
            orders=[await self.get_order(account_seq, "order-123")],
            next_cursor=None,
            has_next=False,
        )

    async def get_order(self, account_seq: int, order_id: str) -> OrderDetailResponse:
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OrderDetailResponse(
            account_seq=account_seq,
            order_id=order_id,
            symbol="005930",
            side=OrderSide.BUY,
            order_type_code="00",
            time_in_force_code="0",
            status="PENDING",
            broker_status_code="01",
            price=Decimal("70000"),
            quantity=Decimal("5"),
            order_amount=None,
            currency="KRW",
            ordered_at=now,
            canceled_at=None,
            execution=OrderExecutionDetail(
                filled_quantity=Decimal("0"),
                average_filled_price=None,
                filled_amount=None,
                commission=None,
                tax=None,
                filled_at=None,
                settlement_date=None,
            ),
        )

    async def create_order_cancellation_preview(
        self, request: OrderCancellationPreviewRequest
    ) -> OrderCancellationPreviewResponse:
        self.cancellation_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OrderCancellationPreviewResponse(
            preview_id="cancel-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            order_id=request.order_id,
            symbol="005930",
            side=OrderSide.BUY,
            order_type_code="00",
            original_status="PENDING",
            price=Decimal("70000"),
            quantity=Decimal("5"),
            filled_quantity=Decimal("1"),
            remaining_quantity=Decimal("4"),
            order_amount=None,
            currency="KRW",
            status="PENDING_APPROVAL",
            approved_at=None,
        )

    async def approve_order_cancellation_preview(
        self, preview_id: str
    ) -> OrderCancellationPreviewResponse:
        self.approved_cancellation_preview_ids.append(preview_id)
        return await self.create_order_cancellation_preview(
            OrderCancellationPreviewRequest(account_seq=1, order_id="order-123")
        )

    async def execute_order_cancellation_preview(
        self, preview_id: str
    ) -> OrderCancellationExecutionResponse:
        self.executed_cancellation_preview_ids.append(preview_id)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OrderCancellationExecutionResponse(
            execution_id="cancel-execution-1",
            preview_id=preview_id,
            order_id="order-123",
            operation_order_id="cancel-operation-1",
            broker_mode="MOCK",
            status="ACCEPTED",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            completed_at=now,
        )

    async def create_order_modification_preview(
        self, request: OrderModificationPreviewRequest
    ) -> OrderModificationPreviewResponse:
        self.modification_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OrderModificationPreviewResponse(
            preview_id="modify-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            original_order_id=request.order_id,
            symbol="005930",
            side=OrderSide.BUY,
            original_order_type_code="00",
            original_time_in_force_code="0",
            original_price=Decimal("70000"),
            original_quantity=Decimal("5"),
            original_order_amount=None,
            filled_quantity=Decimal("0"),
            currency="KRW",
            requested_order_type=request.order_type,
            requested_quantity=request.quantity,
            requested_price=request.price,
            reference_price=Decimal("72300"),
            estimated_order_amount=(request.price or Decimal("72300"))
            * (request.quantity or Decimal("5")),
            requires_high_value_confirmation=False,
            status="PENDING_APPROVAL",
            approved_at=None,
        )

    async def approve_order_modification_preview(
        self, preview_id: str
    ) -> OrderModificationPreviewResponse:
        self.approved_modification_preview_ids.append(preview_id)
        return await self.create_order_modification_preview(
            OrderModificationPreviewRequest(
                account_seq=1,
                order_id="order-123",
                order_type=OrderType.LIMIT,
                quantity=Decimal("7"),
                price=Decimal("71000"),
            )
        )

    async def execute_order_modification_preview(
        self, preview_id: str
    ) -> OrderModificationExecutionResponse:
        self.executed_modification_preview_ids.append(preview_id)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OrderModificationExecutionResponse(
            execution_id="modify-execution-1",
            preview_id=preview_id,
            original_order_id="order-123",
            operation_order_id="modified-order-1",
            broker_mode="MOCK",
            status="ACCEPTED",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            completed_at=now,
        )

    async def list_open_conditional_orders(self, account_seq: int) -> ConditionalOrderListResponse:
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        condition = ConditionalOrderCondition(
            type="PRICE",
            status="WATCHING",
            trigger_price=Decimal("80000"),
            target_profit_rate=None,
            order_price=None,
            triggered_order_id=None,
        )
        return ConditionalOrderListResponse(
            account_seq=account_seq,
            list_status="OPEN",
            symbol=None,
            conditional_orders=[
                ConditionalOrderDetailResponse(
                    account_seq=account_seq,
                    conditional_order_id="conditional-123",
                    type="SINGLE",
                    status="WATCHING",
                    symbol="005930",
                    market="KR",
                    quantity=Decimal("2"),
                    order_type=OrderType.MARKET,
                    expire_date=date(2026, 9, 30),
                    first=condition,
                    second=None,
                    created_at=now,
                )
            ],
            next_cursor=None,
            has_next=False,
        )

    async def create_single_conditional_order_preview(
        self, request: SingleConditionalOrderPreviewRequest
    ) -> SingleConditionalOrderPreviewResponse:
        self.single_conditional_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        calculation_price = request.order_price or Decimal("72300")
        amount = calculation_price * request.quantity
        return SingleConditionalOrderPreviewResponse(
            preview_id="single-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            symbol=request.symbol,
            conditional_order_type="SINGLE",
            side=request.side,
            order_type=request.order_type,
            quantity=request.quantity,
            trigger_price=request.trigger_price,
            order_price=request.order_price,
            expire_date=request.expire_date,
            reference_price=Decimal("72300"),
            calculation_price=calculation_price,
            currency="KRW",
            market_country="KR",
            commission_rate=Decimal("0.001"),
            estimated_order_amount=amount,
            estimated_commission=Decimal("10"),
            estimated_amount_after_commission=amount + Decimal("10"),
            sell_tax_excluded=request.side is OrderSide.SELL,
            requires_high_value_confirmation=False,
            status="PENDING_APPROVAL",
            approved_at=None,
        )

    async def approve_single_conditional_order_preview(
        self, preview_id: str
    ) -> SingleConditionalOrderPreviewResponse:
        self.approved_single_conditional_preview_ids.append(preview_id)
        return await self.create_single_conditional_order_preview(
            SingleConditionalOrderPreviewRequest(
                account_seq=1,
                symbol="005930",
                side=OrderSide.SELL,
                order_type=OrderType.MARKET,
                quantity=Decimal("2"),
                trigger_price=Decimal("80000"),
                order_price=None,
                expire_date=date(2026, 9, 30),
            )
        )

    async def execute_single_conditional_order_preview(
        self, preview_id: str
    ) -> SingleConditionalOrderExecutionResponse:
        self.executed_single_conditional_preview_ids.append(preview_id)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return SingleConditionalOrderExecutionResponse(
            execution_id="single-execution-1",
            preview_id=preview_id,
            client_order_id="single-client-1",
            conditional_order_id="conditional-123",
            broker_mode="MOCK",
            status="ACCEPTED",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            completed_at=now,
        )

    async def create_conditional_order_cancellation_preview(
        self, request: ConditionalOrderCancellationPreviewRequest
    ) -> ConditionalOrderCancellationPreviewResponse:
        self.conditional_cancellation_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        condition = ConditionalOrderCondition(
            type="PRICE",
            status="WATCHING",
            trigger_price=Decimal("80000"),
            target_profit_rate=None,
            order_price=None,
            triggered_order_id=None,
        )
        return ConditionalOrderCancellationPreviewResponse(
            preview_id="conditional-cancel-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            conditional_order_id=request.conditional_order_id,
            conditional_order_type="SINGLE",
            original_status="WATCHING",
            symbol="005930",
            market="KR",
            quantity=Decimal("2"),
            order_type=OrderType.MARKET,
            expire_date=date(2026, 9, 30),
            first=condition,
            second=None,
            conditional_order_created_at=now,
            status="PENDING_APPROVAL",
            approved_at=None,
        )

    async def approve_conditional_order_cancellation_preview(
        self, preview_id: str
    ) -> ConditionalOrderCancellationPreviewResponse:
        self.approved_conditional_cancellation_preview_ids.append(preview_id)
        return await self.create_conditional_order_cancellation_preview(
            ConditionalOrderCancellationPreviewRequest(
                account_seq=1, conditional_order_id="conditional-123"
            )
        )

    async def execute_conditional_order_cancellation_preview(
        self, preview_id: str
    ) -> ConditionalOrderCancellationExecutionResponse:
        self.executed_conditional_cancellation_preview_ids.append(preview_id)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return ConditionalOrderCancellationExecutionResponse(
            execution_id="conditional-cancel-execution-1",
            preview_id=preview_id,
            account_seq=1,
            conditional_order_id="conditional-123",
            broker_mode="MOCK",
            status="ACCEPTED",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            completed_at=now,
        )

    async def create_oco_conditional_order_preview(
        self, request: DualConditionalOrderPreviewRequest
    ) -> OcoConditionalOrderPreviewResponse:
        self.oco_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OcoConditionalOrderPreviewResponse(
            preview_id="oco-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            symbol=request.symbol,
            conditional_order_type=ConditionalOrderType.OCO,
            quantity=request.quantity,
            order_type=OrderType.LIMIT,
            expire_date=request.expire_date,
            reference_price=Decimal("72000"),
            currency="KRW",
            market_country="KR",
            commission_rate=Decimal("0.00015"),
            first=self._dual_condition(request.first),
            second=self._dual_condition(request.second),
            sell_tax_excluded=True,
            requires_high_value_confirmation=False,
            status="PENDING_APPROVAL",
            approved_at=None,
        )

    async def approve_oco_conditional_order_preview(
        self, preview_id: str
    ) -> OcoConditionalOrderPreviewResponse:
        self.approved_oco_preview_ids.append(preview_id)
        return await self.create_oco_conditional_order_preview(self._oco_request())

    async def execute_oco_conditional_order_preview(
        self, preview_id: str
    ) -> ConditionalOrderCreationExecutionResponse:
        self.executed_oco_preview_ids.append(preview_id)
        return self._conditional_creation_execution(preview_id, "oco")

    async def create_oto_conditional_order_preview(
        self, request: DualConditionalOrderPreviewRequest
    ) -> OtoConditionalOrderPreviewResponse:
        self.oto_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return OtoConditionalOrderPreviewResponse(
            preview_id="oto-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            symbol=request.symbol,
            conditional_order_type=ConditionalOrderType.OTO,
            quantity=request.quantity,
            order_type=OrderType.LIMIT,
            expire_date=request.expire_date,
            reference_price=Decimal("72000"),
            currency="KRW",
            market_country="KR",
            commission_rate=Decimal("0.00015"),
            first=self._dual_condition(request.first),
            second=self._dual_condition(request.second),
            buying_power_checked=True,
            sell_tax_excluded=True,
            requires_high_value_confirmation=False,
            status="PENDING_APPROVAL",
            approved_at=None,
        )

    async def approve_oto_conditional_order_preview(
        self, preview_id: str
    ) -> OtoConditionalOrderPreviewResponse:
        self.approved_oto_preview_ids.append(preview_id)
        return await self.create_oto_conditional_order_preview(self._oto_request())

    async def execute_oto_conditional_order_preview(
        self, preview_id: str
    ) -> ConditionalOrderCreationExecutionResponse:
        self.executed_oto_preview_ids.append(preview_id)
        return self._conditional_creation_execution(preview_id, "oto")

    async def create_conditional_order_modification_preview(
        self, request: ConditionalOrderModificationPreviewRequest
    ) -> ConditionalOrderModificationPreviewResponse:
        self.conditional_modification_preview_requests.append(request)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        original = ConditionalOrderCondition(
            type="STOP",
            status="WATCHING",
            trigger_price=Decimal("80000"),
            target_profit_rate=None,
            order_price=None,
            triggered_order_id=None,
        )
        return ConditionalOrderModificationPreviewResponse(
            preview_id="conditional-modify-preview-1",
            created_at=now,
            expires_at=now + timedelta(minutes=2),
            account_seq=request.account_seq,
            original_conditional_order_id=request.conditional_order_id,
            original_type=ConditionalOrderType.SINGLE,
            original_status="WATCHING",
            symbol="005930",
            market="KR",
            original_quantity=Decimal("2"),
            original_order_type=OrderType.MARKET,
            original_expire_date=date(2026, 9, 30),
            original_first=original,
            original_second=None,
            original_created_at=now,
            requested_type=request.type,
            requested_quantity=request.quantity,
            requested_order_type=request.order_type,
            requested_expire_date=request.expire_date,
            requested_first=request.first,
            requested_second=request.second,
            reference_price=Decimal("72000"),
            currency="KRW",
            requires_high_value_confirmation=False,
            status="PENDING_APPROVAL",
            approved_at=None,
        )

    async def approve_conditional_order_modification_preview(
        self, preview_id: str
    ) -> ConditionalOrderModificationPreviewResponse:
        self.approved_conditional_modification_preview_ids.append(preview_id)
        return await self.create_conditional_order_modification_preview(
            ConditionalOrderModificationPreviewRequest(
                account_seq=1,
                conditional_order_id="conditional-123",
                type=ConditionalOrderType.OCO,
                quantity=Decimal("2"),
                order_type=OrderType.LIMIT,
                expire_date=date(2026, 9, 30),
                first=self._oco_request().first,
                second=self._oco_request().second,
            )
        )

    async def execute_conditional_order_modification_preview(
        self, preview_id: str
    ) -> ConditionalOrderModificationExecutionResponse:
        self.executed_conditional_modification_preview_ids.append(preview_id)
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return ConditionalOrderModificationExecutionResponse(
            execution_id="conditional-modify-execution-1",
            preview_id=preview_id,
            account_seq=1,
            original_conditional_order_id="conditional-123",
            replacement_conditional_order_id="conditional-replacement-123",
            broker_mode="MOCK",
            status="ACCEPTED",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            completed_at=now,
        )

    @staticmethod
    def _dual_condition(
        condition: ConditionalOrderMutationCondition,
    ) -> DualConditionalOrderPreviewCondition:
        assert condition.order_price is not None
        amount = condition.order_price * Decimal("2")
        return DualConditionalOrderPreviewCondition(
            side=condition.side,
            trigger_price=condition.trigger_price,
            order_price=condition.order_price,
            estimated_order_amount=amount,
            estimated_commission=Decimal("10"),
            estimated_amount_after_commission=amount - Decimal("10"),
            estimated_proceeds_after_commission=amount - Decimal("10"),
        )

    @staticmethod
    def _oco_request() -> DualConditionalOrderPreviewRequest:
        return DualConditionalOrderPreviewRequest(
            account_seq=1,
            symbol="005930",
            quantity=Decimal("2"),
            order_type=OrderType.LIMIT,
            expire_date=date(2026, 9, 30),
            first=ConditionalOrderMutationCondition(
                side=OrderSide.SELL,
                trigger_price=Decimal("80000"),
                order_price=Decimal("79000"),
            ),
            second=ConditionalOrderMutationCondition(
                side=OrderSide.SELL,
                trigger_price=Decimal("65000"),
                order_price=Decimal("64900"),
            ),
        )

    @staticmethod
    def _oto_request() -> DualConditionalOrderPreviewRequest:
        request = FakeSpringGateway._oco_request()
        return request.model_copy(
            update={
                "first": request.first.model_copy(update={"side": OrderSide.BUY}),
            }
        )

    @staticmethod
    def _conditional_creation_execution(
        preview_id: str, kind: str
    ) -> ConditionalOrderCreationExecutionResponse:
        now = datetime(2026, 9, 14, 7, 0, tzinfo=UTC)
        return ConditionalOrderCreationExecutionResponse(
            execution_id=f"{kind}-execution-1",
            preview_id=preview_id,
            client_order_id=f"{kind}-client-1",
            conditional_order_id=f"{kind}-conditional-1",
            broker_mode="MOCK",
            status="ACCEPTED",
            failure_type=None,
            created_at=now,
            updated_at=now,
            submitted_at=now,
            completed_at=now,
        )

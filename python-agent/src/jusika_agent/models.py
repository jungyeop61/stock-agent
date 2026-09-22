"""Typed contracts shared by the HTTP API, graph, and Spring client."""

from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator
from pydantic.alias_generators import to_camel


class Intent(StrEnum):
    """Commands supported by the first agent MVP."""

    PRICE_QUERY = "PRICE_QUERY"
    EXCHANGE_RATE_QUERY = "EXCHANGE_RATE_QUERY"
    CURRENCY_EXCHANGE = "CURRENCY_EXCHANGE"
    HOLDINGS_QUERY = "HOLDINGS_QUERY"
    BUYING_POWER_QUERY = "BUYING_POWER_QUERY"
    COMMISSIONS_QUERY = "COMMISSIONS_QUERY"
    SELLABLE_QUANTITY_QUERY = "SELLABLE_QUANTITY_QUERY"
    BUY = "BUY"
    SELL = "SELL"
    AMOUNT_BUY = "AMOUNT_BUY"
    ORDER_LIST = "ORDER_LIST"
    ORDER_HISTORY_QUERY = "ORDER_HISTORY_QUERY"
    ORDER_DETAIL_QUERY = "ORDER_DETAIL_QUERY"
    ORDER_CANCEL = "ORDER_CANCEL"
    ORDER_MODIFY = "ORDER_MODIFY"
    CONDITIONAL_ORDER_LIST = "CONDITIONAL_ORDER_LIST"
    CONDITIONAL_ORDER_DETAIL_QUERY = "CONDITIONAL_ORDER_DETAIL_QUERY"
    SINGLE_CONDITIONAL_ORDER = "SINGLE_CONDITIONAL_ORDER"
    OCO_CONDITIONAL_ORDER = "OCO_CONDITIONAL_ORDER"
    OTO_CONDITIONAL_ORDER = "OTO_CONDITIONAL_ORDER"
    CONDITIONAL_ORDER_CANCEL = "CONDITIONAL_ORDER_CANCEL"
    CONDITIONAL_ORDER_MODIFY = "CONDITIONAL_ORDER_MODIFY"
    EXECUTION_STATUS_QUERY = "EXECUTION_STATUS_QUERY"
    EXECUTION_RECOVER = "EXECUTION_RECOVER"
    UNKNOWN = "UNKNOWN"


class OrderType(StrEnum):
    """Order types understood by the Spring quantity-order API."""

    MARKET = "MARKET"
    LIMIT = "LIMIT"


class OrderSide(StrEnum):
    """Broker order direction."""

    BUY = "BUY"
    SELL = "SELL"


class Currency(StrEnum):
    KRW = "KRW"
    USD = "USD"


class ConditionalOrderType(StrEnum):
    SINGLE = "SINGLE"
    OCO = "OCO"
    OTO = "OTO"


class ExecutionKind(StrEnum):
    """Backend execution families that support status lookup and safe recovery."""

    ORDER = "ORDER"
    AMOUNT_ORDER = "AMOUNT_ORDER"


class ParsedCondition(BaseModel):
    model_config = ConfigDict(extra="forbid")

    side: OrderSide
    trigger_price: Decimal = Field(gt=0)
    order_price: Decimal | None = Field(default=None, gt=0)


class ParsedIntent(BaseModel):
    """Strict LLM or rule-based interpretation of one user command."""

    model_config = ConfigDict(extra="forbid")

    intent: Intent
    stock_name: str | None = None
    symbol: str | None = None
    quantity: Decimal | None = Field(default=None, gt=0)
    order_amount: Decimal | None = Field(default=None, gt=0)
    amount_currency: Currency | None = None
    exchange_amount: Decimal | None = Field(default=None, gt=0)
    base_currency: Currency | None = None
    quote_currency: Currency | None = None
    price: Decimal | None = Field(default=None, gt=0)
    trigger_price: Decimal | None = Field(default=None, gt=0)
    order_type: OrderType = OrderType.MARKET
    side: OrderSide | None = None
    order_id: str | None = None
    conditional_order_id: str | None = None
    execution_id: str | None = None
    execution_kind: ExecutionKind | None = None
    conditional_order_type: ConditionalOrderType | None = None
    first_condition: ParsedCondition | None = None
    second_condition: ParsedCondition | None = None
    expire_date: date | None = None

    @field_validator("stock_name", "symbol", "order_id", "conditional_order_id", "execution_id")
    @classmethod
    def blank_text_is_none(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None

    @field_validator("symbol")
    @classmethod
    def normalize_symbol(cls, value: str | None) -> str | None:
        return value.upper() if value else None


class AgentStatus(StrEnum):
    """Externally visible state of one agent turn."""

    COMPLETED = "COMPLETED"
    WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
    CANCELLED = "CANCELLED"
    NEEDS_INPUT = "NEEDS_INPUT"
    ERROR = "ERROR"


class AgentMessageRequest(BaseModel):
    """Text produced by STT or a local text client."""

    text: str = Field(min_length=1, max_length=500)

    @field_validator("text")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("명령은 비어 있을 수 없습니다.")
        return normalized


class AgentTurnResponse(BaseModel):
    """One response for Android TTS and optional structured display."""

    session_id: str
    status: AgentStatus
    message: str
    requires_confirmation: bool = False
    preview_id: str | None = None
    data: dict[str, Any] | None = None


class AgentVoiceMessageRequest(AgentMessageRequest):
    """A mobile voice turn; consent is bound to the preview actually read aloud."""

    confirmation_preview_id: str | None = Field(default=None, min_length=1, max_length=256)


class SpringModel(BaseModel):
    """Base model matching Spring's camelCase JSON contracts."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )


class BrokerSafetyStatusResponse(SpringModel):
    """Non-secret broker mutation safety state exposed by Spring."""

    mode: Literal["MOCK", "LIVE"]
    live_enabled: StrictBool
    kill_switch_active: StrictBool
    live_safety_gate_open: StrictBool
    live_mutation_available: StrictBool
    block_reason: Literal[
        "MOCK_MODE",
        "LIVE_FEATURE_DISABLED",
        "KILL_SWITCH_ACTIVE",
        "LIVE_ADAPTER_NOT_CONNECTED",
        "LIVE_ACCOUNT_ALLOWLIST_EMPTY",
        "LIVE_INSTRUMENT_ALLOWLIST_EMPTY",
        "LIVE_ORDER_LIMITS_NOT_CONFIGURED",
        "LIVE_DAILY_ORDER_LIMITS_NOT_CONFIGURED",
        "LIVE_OPEN_ORDER_LIMITS_NOT_CONFIGURED",
        "LIVE_ORDER_RATE_LIMITS_NOT_CONFIGURED",
        "LIVE_UNKNOWN_INCIDENT_HALT_ACTIVE",
        "NONE",
    ]


class AccountResponse(SpringModel):
    account_seq: int
    masked_account_number: str
    account_type: str


class StockPriceResponse(SpringModel):
    symbol: str
    price: Decimal
    currency: str
    timestamp: datetime


class ExchangeRateResponse(SpringModel):
    base_currency: Currency
    quote_currency: Currency
    rate: Decimal
    mid_rate: Decimal
    basis_point: Decimal
    rate_change_type: str
    valid_from: datetime
    valid_until: datetime


class HoldingItem(SpringModel):
    symbol: str
    name: str
    market_country: str
    currency: str
    quantity: Decimal
    last_price: Decimal


class HoldingsResponse(SpringModel):
    account_seq: int
    items: list[HoldingItem]


class BuyingPowerResponse(SpringModel):
    account_seq: int
    currency: Currency
    cash_buying_power: Decimal


class CommissionItem(SpringModel):
    market_country: str
    commission_rate: Decimal
    start_date: date | None
    end_date: date | None


class CommissionsResponse(SpringModel):
    account_seq: int
    commissions: list[CommissionItem]


class SellableQuantityResponse(SpringModel):
    account_seq: int
    symbol: str
    sellable_quantity: Decimal


class OrderPreviewRequest(SpringModel):
    account_seq: int
    symbol: str
    side: OrderSide
    order_type: OrderType
    quantity: Decimal
    price: Decimal | None = None


class OrderPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    symbol: str
    side: OrderSide
    order_type: OrderType
    quantity: Decimal
    requested_price: Decimal | None
    reference_price: Decimal
    calculation_price: Decimal
    currency: str
    market_country: str
    estimated_order_amount: Decimal
    estimated_commission: Decimal
    estimated_amount_after_commission: Decimal
    requires_high_value_confirmation: bool
    order_ready: bool
    status: str
    approved_at: datetime | None


class OrderExecutionResponse(SpringModel):
    execution_id: str
    preview_id: str
    client_order_id: str
    broker_mode: str
    status: str
    broker_order_id: str | None
    failure_type: str | None
    created_at: datetime
    updated_at: datetime
    submitted_at: datetime | None
    recovery_attempted_at: datetime | None
    completed_at: datetime | None


class AmountOrderPreviewRequest(SpringModel):
    account_seq: int
    symbol: str
    order_amount: Decimal


class AmountOrderPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    symbol: str
    side: OrderSide
    order_type: OrderType
    order_amount: Decimal
    currency: str
    market_country: str
    reference_price: Decimal
    estimated_quantity: Decimal
    commission_rate: Decimal
    estimated_commission: Decimal
    estimated_total_cost: Decimal
    exchange_rate: Decimal
    exchange_rate_valid_from: datetime
    exchange_rate_valid_until: datetime
    estimated_order_amount_krw: Decimal
    requires_high_value_confirmation: bool
    order_ready: bool
    status: str
    approved_at: datetime | None


class AmountOrderExecutionResponse(OrderExecutionResponse):
    pass


class OrderExecutionDetail(SpringModel):
    filled_quantity: Decimal
    average_filled_price: Decimal | None
    filled_amount: Decimal | None
    commission: Decimal | None
    tax: Decimal | None
    filled_at: datetime | None
    settlement_date: date | None


class OrderDetailResponse(SpringModel):
    account_seq: int
    order_id: str
    symbol: str
    side: OrderSide
    order_type_code: str
    time_in_force_code: str
    status: str
    broker_status_code: str
    price: Decimal | None
    quantity: Decimal | None
    order_amount: Decimal | None
    currency: str
    ordered_at: datetime
    canceled_at: datetime | None
    execution: OrderExecutionDetail


class OrderListResponse(SpringModel):
    account_seq: int
    list_status: str
    symbol: str | None
    from_: date | None = Field(alias="from")
    to: date | None
    orders: list[OrderDetailResponse]
    next_cursor: str | None
    has_next: bool


class OrderCancellationPreviewRequest(SpringModel):
    account_seq: int
    order_id: str


class OrderCancellationPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    order_id: str
    symbol: str
    side: OrderSide
    order_type_code: str
    original_status: str
    price: Decimal | None
    quantity: Decimal | None
    filled_quantity: Decimal
    remaining_quantity: Decimal | None
    order_amount: Decimal | None
    currency: str
    status: str
    approved_at: datetime | None


class OrderCancellationExecutionResponse(SpringModel):
    execution_id: str
    preview_id: str
    order_id: str
    operation_order_id: str | None
    broker_mode: str
    status: str
    failure_type: str | None
    created_at: datetime
    updated_at: datetime
    submitted_at: datetime | None
    completed_at: datetime | None


class OrderModificationPreviewRequest(SpringModel):
    account_seq: int
    order_id: str
    order_type: OrderType
    quantity: Decimal | None
    price: Decimal | None


class OrderModificationPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    original_order_id: str
    symbol: str
    side: OrderSide
    original_order_type_code: str
    original_time_in_force_code: str
    original_price: Decimal | None
    original_quantity: Decimal | None
    original_order_amount: Decimal | None
    filled_quantity: Decimal
    currency: str
    requested_order_type: OrderType
    requested_quantity: Decimal | None
    requested_price: Decimal | None
    reference_price: Decimal | None
    estimated_order_amount: Decimal
    requires_high_value_confirmation: bool
    status: str
    approved_at: datetime | None


class OrderModificationExecutionResponse(SpringModel):
    execution_id: str
    preview_id: str
    original_order_id: str
    operation_order_id: str | None
    broker_mode: str
    status: str
    failure_type: str | None
    created_at: datetime
    updated_at: datetime
    submitted_at: datetime | None
    completed_at: datetime | None


class ConditionalOrderCondition(SpringModel):
    type: str
    status: str
    trigger_price: Decimal | None
    target_profit_rate: Decimal | None
    order_price: Decimal | None
    triggered_order_id: str | None


class ConditionalOrderDetailResponse(SpringModel):
    account_seq: int
    conditional_order_id: str
    type: str
    status: str
    symbol: str
    market: str
    quantity: Decimal
    order_type: OrderType
    expire_date: date | None
    first: ConditionalOrderCondition
    second: ConditionalOrderCondition | None
    created_at: datetime


class ConditionalOrderListResponse(SpringModel):
    account_seq: int
    list_status: str
    symbol: str | None
    conditional_orders: list[ConditionalOrderDetailResponse]
    next_cursor: str | None
    has_next: bool


class SingleConditionalOrderPreviewRequest(SpringModel):
    account_seq: int
    symbol: str
    side: OrderSide
    order_type: OrderType
    quantity: Decimal
    trigger_price: Decimal
    order_price: Decimal | None
    expire_date: date


class SingleConditionalOrderPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    symbol: str
    conditional_order_type: str
    side: OrderSide
    order_type: OrderType
    quantity: Decimal
    trigger_price: Decimal
    order_price: Decimal | None
    expire_date: date
    reference_price: Decimal
    calculation_price: Decimal
    currency: str
    market_country: str
    commission_rate: Decimal
    estimated_order_amount: Decimal
    estimated_commission: Decimal
    estimated_amount_after_commission: Decimal
    sell_tax_excluded: bool
    requires_high_value_confirmation: bool
    status: str
    approved_at: datetime | None


class SingleConditionalOrderExecutionResponse(SpringModel):
    execution_id: str
    preview_id: str
    client_order_id: str
    conditional_order_id: str | None
    broker_mode: str
    status: str
    failure_type: str | None
    created_at: datetime
    updated_at: datetime
    submitted_at: datetime | None
    completed_at: datetime | None


class ConditionalOrderCancellationPreviewRequest(SpringModel):
    account_seq: int
    conditional_order_id: str


class ConditionalOrderCancellationPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    conditional_order_id: str
    conditional_order_type: str
    original_status: str
    symbol: str
    market: str
    quantity: Decimal
    order_type: OrderType
    expire_date: date | None
    first: ConditionalOrderCondition
    second: ConditionalOrderCondition | None
    conditional_order_created_at: datetime
    status: str
    approved_at: datetime | None


class ConditionalOrderCancellationExecutionResponse(SpringModel):
    execution_id: str
    preview_id: str
    account_seq: int
    conditional_order_id: str
    broker_mode: str
    status: str
    failure_type: str | None
    created_at: datetime
    updated_at: datetime
    submitted_at: datetime | None
    completed_at: datetime | None


class ConditionalOrderMutationCondition(SpringModel):
    side: OrderSide
    trigger_price: Decimal
    order_price: Decimal | None


class DualConditionalOrderPreviewRequest(SpringModel):
    account_seq: int
    symbol: str
    quantity: Decimal
    order_type: OrderType
    expire_date: date
    first: ConditionalOrderMutationCondition
    second: ConditionalOrderMutationCondition


class DualConditionalOrderPreviewCondition(ConditionalOrderMutationCondition):
    order_price: Decimal
    estimated_order_amount: Decimal
    estimated_commission: Decimal
    estimated_amount_after_commission: Decimal | None = None
    estimated_proceeds_after_commission: Decimal | None = None


class OcoConditionalOrderPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    symbol: str
    conditional_order_type: ConditionalOrderType
    quantity: Decimal
    order_type: OrderType
    expire_date: date
    reference_price: Decimal
    currency: str
    market_country: str
    commission_rate: Decimal
    first: DualConditionalOrderPreviewCondition
    second: DualConditionalOrderPreviewCondition
    sell_tax_excluded: bool
    requires_high_value_confirmation: bool
    status: str
    approved_at: datetime | None


class OtoConditionalOrderPreviewResponse(OcoConditionalOrderPreviewResponse):
    buying_power_checked: bool


class ConditionalOrderCreationExecutionResponse(SpringModel):
    execution_id: str
    preview_id: str
    client_order_id: str
    conditional_order_id: str | None
    broker_mode: str
    status: str
    failure_type: str | None
    created_at: datetime
    updated_at: datetime
    submitted_at: datetime | None
    completed_at: datetime | None


class ConditionalOrderModificationPreviewRequest(SpringModel):
    account_seq: int
    conditional_order_id: str
    type: ConditionalOrderType
    quantity: Decimal
    order_type: OrderType
    expire_date: date
    first: ConditionalOrderMutationCondition
    second: ConditionalOrderMutationCondition | None


class ConditionalOrderModificationPreviewResponse(SpringModel):
    preview_id: str
    created_at: datetime
    expires_at: datetime
    account_seq: int
    original_conditional_order_id: str
    original_type: ConditionalOrderType
    original_status: str
    symbol: str
    market: str
    original_quantity: Decimal
    original_order_type: OrderType
    original_expire_date: date | None
    original_first: ConditionalOrderCondition
    original_second: ConditionalOrderCondition | None
    original_created_at: datetime
    requested_type: ConditionalOrderType
    requested_quantity: Decimal
    requested_order_type: OrderType
    requested_expire_date: date
    requested_first: ConditionalOrderMutationCondition
    requested_second: ConditionalOrderMutationCondition | None
    reference_price: Decimal
    currency: str
    requires_high_value_confirmation: bool
    status: str
    approved_at: datetime | None


class ConditionalOrderModificationExecutionResponse(SpringModel):
    execution_id: str
    preview_id: str
    account_seq: int
    original_conditional_order_id: str
    replacement_conditional_order_id: str | None
    broker_mode: str
    status: str
    failure_type: str | None
    created_at: datetime
    updated_at: datetime
    submitted_at: datetime | None
    completed_at: datetime | None

"""Korean speech formatting for financial values."""

from decimal import Decimal

from jusika_agent.models import (
    AmountOrderExecutionResponse,
    AmountOrderPreviewResponse,
    BuyingPowerResponse,
    CommissionsResponse,
    ConditionalOrderCancellationPreviewResponse,
    ConditionalOrderDetailResponse,
    ConditionalOrderListResponse,
    ConditionalOrderModificationPreviewResponse,
    ExchangeRateResponse,
    HoldingsResponse,
    OcoConditionalOrderPreviewResponse,
    OrderCancellationPreviewResponse,
    OrderDetailResponse,
    OrderExecutionResponse,
    OrderListResponse,
    OrderModificationPreviewResponse,
    OrderPreviewResponse,
    OrderSide,
    OrderType,
    OtoConditionalOrderPreviewResponse,
    SellableQuantityResponse,
    SingleConditionalOrderPreviewResponse,
    StockPriceResponse,
)

_DIGITS = ("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
_SMALL_UNITS = ("", "십", "백", "천")
_LARGE_UNITS = ("", "만", "억", "조")


def integer_to_korean(value: int) -> str:
    """Render a non-negative integer with Sino-Korean number words."""

    if value == 0:
        return _DIGITS[0]
    if value < 0:
        return f"마이너스 {integer_to_korean(-value)}"

    groups: list[str] = []
    group_index = 0
    remaining = value
    while remaining:
        group = remaining % 10_000
        if group:
            rendered = _render_under_ten_thousand(group)
            groups.append(f"{rendered}{_LARGE_UNITS[group_index]}")
        remaining //= 10_000
        group_index += 1
    return "".join(reversed(groups))


def _render_under_ten_thousand(value: int) -> str:
    parts: list[str] = []
    for place in range(3, -1, -1):
        divisor = 10**place
        digit = value // divisor
        value %= divisor
        if digit == 0:
            continue
        if digit != 1 or place == 0:
            parts.append(_DIGITS[digit])
        parts.append(_SMALL_UNITS[place])
    return "".join(parts)


def decimal_to_korean(value: Decimal) -> str:
    """Render a decimal without silently rounding financial values."""

    normalized = format(value, "f")
    integer_part, dot, fractional_part = normalized.partition(".")
    rendered = integer_to_korean(int(integer_part))
    fractional_part = fractional_part.rstrip("0")
    if dot and fractional_part:
        rendered += " 점 " + " ".join(_DIGITS[int(digit)] for digit in fractional_part)
    return rendered


def currency_unit(currency: str) -> str:
    """Return the Korean unit for a supported broker currency."""

    return {"KRW": "원", "USD": "달러"}.get(currency.upper(), currency)


def format_price_message(display_name: str, price: StockPriceResponse) -> str:
    unit = currency_unit(price.currency)
    return f"{display_name} 현재 가격은 {decimal_to_korean(price.price)} {unit}입니다."


def format_exchange_rate_message(
    response: ExchangeRateResponse, amount: Decimal | None = None
) -> str:
    base_unit = currency_unit(response.base_currency.value)
    quote_unit = currency_unit(response.quote_currency.value)
    rate = decimal_to_korean(response.rate)
    if amount is None:
        return (
            f"현재 참고 환율은 일 {base_unit}당 {rate} {quote_unit}입니다. "
            "실제 환전 거래에 적용되는 환율과 다를 수 있습니다."
        )
    converted = amount * response.rate
    return (
        f"{decimal_to_korean(amount)} {base_unit}는 현재 참고 환율로 약 "
        f"{decimal_to_korean(converted)} {quote_unit}입니다. "
        "실제 환전 거래에 적용되는 금액과 다를 수 있습니다."
    )


def format_holdings_message(holdings: HoldingsResponse) -> str:
    if not holdings.items:
        return "현재 보유 중인 주식이 없습니다."
    item_messages = [
        f"{item.name} {decimal_to_korean(item.quantity)} 주" for item in holdings.items[:5]
    ]
    prefix = f"보유 종목은 {integer_to_korean(len(holdings.items))}개입니다. "
    suffix = "입니다."
    if len(holdings.items) > 5:
        suffix = f" 외 {integer_to_korean(len(holdings.items) - 5)}개입니다."
    return prefix + ", ".join(item_messages) + suffix


def format_buying_power_message(response: BuyingPowerResponse) -> str:
    return (
        f"현재 현금 매수 가능 금액은 {decimal_to_korean(response.cash_buying_power)} "
        f"{currency_unit(response.currency.value)}입니다."
    )


def format_commissions_message(response: CommissionsResponse) -> str:
    if not response.commissions:
        return "현재 적용 중인 매매 수수료 정보를 찾지 못했습니다."
    rendered = []
    for item in response.commissions:
        market = {"KR": "국내", "US": "미국"}.get(item.market_country, item.market_country)
        percent = item.commission_rate * Decimal("100")
        rendered.append(f"{market} 시장 {decimal_to_korean(percent)} 퍼센트")
    return "현재 매매 수수료율은 " + ", ".join(rendered) + "입니다."


def format_sellable_quantity_message(display_name: str, response: SellableQuantityResponse) -> str:
    return (
        f"{display_name}의 현재 매도 가능 수량은 "
        f"{decimal_to_korean(response.sellable_quantity)} 주입니다."
    )


def format_preview_message(display_name: str, preview: OrderPreviewResponse) -> str:
    side = "매수" if preview.side.value == "BUY" else "매도"
    if preview.order_type is OrderType.MARKET:
        order_description = "시장가로"
    else:
        assert preview.requested_price is not None
        order_description = (
            f"{decimal_to_korean(preview.requested_price)} {currency_unit(preview.currency)}에"
        )
    quantity = decimal_to_korean(preview.quantity)
    amount = decimal_to_korean(preview.estimated_amount_after_commission)
    unit = currency_unit(preview.currency)
    high_value_notice = (
        " 고액 주문이므로 특히 주의해주세요." if preview.requires_high_value_confirmation else ""
    )
    return (
        f"{display_name} {quantity} 주를 {order_description} {side}합니다. "
        f"예상 금액은 약 {amount} {unit}입니다. 수량은 {quantity} 주입니다."
        f"{high_value_notice} 주문하시려면 승인, 그만두려면 취소라고 말씀해주세요."
    )


def format_amount_preview_message(display_name: str, preview: AmountOrderPreviewResponse) -> str:
    amount = decimal_to_korean(preview.order_amount)
    quantity = decimal_to_korean(preview.estimated_quantity)
    total = decimal_to_korean(preview.estimated_total_cost)
    high_value_notice = (
        " 고액 주문이므로 특히 주의해주세요." if preview.requires_high_value_confirmation else ""
    )
    return (
        f"{display_name}, 시장가로 {amount} 달러어치 매수합니다. "
        f"현재가 기준 예상 수량은 약 {quantity} 주이고, 예상 수수료 포함 필요 금액은 "
        f"{total} 달러입니다.{high_value_notice} "
        "주문하시려면 승인, 그만두려면 취소라고 말씀해주세요."
    )


def format_order_list_message(response: OrderListResponse) -> str:
    if not response.orders:
        return (
            "최근 종료된 주문 내역이 없습니다."
            if response.list_status == "CLOSED"
            else "현재 미체결 주문이 없습니다."
        )
    rendered: list[str] = []
    for order in response.orders[:5]:
        side = "매수" if order.side.value == "BUY" else "매도"
        if order.quantity is not None:
            size = f"{decimal_to_korean(order.quantity)} 주"
        elif order.order_amount is not None:
            size = f"{decimal_to_korean(order.order_amount)} {currency_unit(order.currency)} 금액"
        else:
            size = "수량 미확인"
        rendered.append(
            f"{order.symbol} {side} {size}, 상태 {order.status}, 주문번호 {order.order_id}"
        )
    suffix = ""
    if len(response.orders) > 5 or response.has_next:
        suffix = " 나머지 주문도 있습니다."
    label = "최근 종료 주문" if response.list_status == "CLOSED" else "미체결 주문"
    return (
        f"{label}은 {integer_to_korean(len(response.orders))}건입니다. "
        + ". ".join(rendered)
        + suffix
    )


def format_order_detail_message(response: OrderDetailResponse) -> str:
    side = "매수" if response.side is OrderSide.BUY else "매도"
    if response.quantity is not None:
        size = f"{decimal_to_korean(response.quantity)} 주"
    elif response.order_amount is not None:
        size = f"{decimal_to_korean(response.order_amount)} {currency_unit(response.currency)} 금액"
    else:
        size = "수량 미확인"
    filled = decimal_to_korean(response.execution.filled_quantity)
    return (
        f"주문번호 {response.order_id}, {response.symbol} {side} {size} 주문은 "
        f"현재 {response.status} 상태이고 누적 체결 수량은 {filled} 주입니다."
    )


def format_cancellation_preview_message(
    preview: OrderCancellationPreviewResponse,
) -> str:
    side = "매수" if preview.side.value == "BUY" else "매도"
    remaining = (
        f"남은 수량 {decimal_to_korean(preview.remaining_quantity)} 주"
        if preview.remaining_quantity is not None
        else "남은 금액 주문"
    )
    return (
        f"주문번호 {preview.order_id}, {preview.symbol} {side} 주문의 {remaining}을 "
        "취소합니다. 실행하려면 승인, 그만두려면 취소라고 말씀해주세요."
    )


def format_modification_preview_message(
    preview: OrderModificationPreviewResponse,
) -> str:
    side = "매수" if preview.side.value == "BUY" else "매도"
    quantity = (
        f"{decimal_to_korean(preview.requested_quantity)} 주"
        if preview.requested_quantity is not None
        else "기존 수량"
    )
    if preview.requested_order_type is OrderType.MARKET:
        terms = "시장가"
    else:
        assert preview.requested_price is not None
        terms = (
            f"{decimal_to_korean(preview.requested_price)} {currency_unit(preview.currency)} 지정가"
        )
    high_value_notice = (
        " 고액 정정이므로 특히 주의해주세요." if preview.requires_high_value_confirmation else ""
    )
    return (
        f"주문번호 {preview.original_order_id}, {preview.symbol} {side} 주문을 "
        f"{quantity}, {terms}로 정정합니다.{high_value_notice} "
        "실행하려면 승인, 그만두려면 취소라고 말씀해주세요."
    )


def format_conditional_order_list_message(
    response: ConditionalOrderListResponse,
) -> str:
    if not response.conditional_orders:
        return "현재 감시 중인 조건 주문이 없습니다."
    rendered = [
        (
            f"{order.symbol} {order.type}, 상태 {order.status}, "
            f"조건주문번호 {order.conditional_order_id}"
        )
        for order in response.conditional_orders[:5]
    ]
    suffix = ""
    if len(response.conditional_orders) > 5 or response.has_next:
        suffix = " 나머지 조건 주문도 있습니다."
    return (
        f"감시 중인 조건 주문은 {integer_to_korean(len(response.conditional_orders))}건입니다. "
        + ". ".join(rendered)
        + suffix
    )


def format_conditional_order_detail_message(
    response: ConditionalOrderDetailResponse,
) -> str:
    first_trigger = (
        f"감시가격 {decimal_to_korean(response.first.trigger_price)}"
        if response.first.trigger_price is not None
        else (
            "목표 수익률 "
            f"{decimal_to_korean(response.first.target_profit_rate or Decimal(0))} 퍼센트"
        )
    )
    second = ""
    if response.second is not None:
        second_trigger = (
            f"감시가격 {decimal_to_korean(response.second.trigger_price)}"
            if response.second.trigger_price is not None
            else "수익률 조건"
        )
        second = f" 둘째 조건은 {second_trigger}, 상태 {response.second.status}입니다."
    return (
        f"조건주문번호 {response.conditional_order_id}, {response.symbol} {response.type} 주문은 "
        f"현재 {response.status} 상태입니다. 수량은 {decimal_to_korean(response.quantity)} 주, "
        f"첫 조건은 {first_trigger}, 상태 {response.first.status}입니다.{second}"
    )


def format_execution_status_message(
    response: OrderExecutionResponse | AmountOrderExecutionResponse,
) -> str:
    message = f"실행번호 {response.execution_id}의 현재 상태는 {response.status}입니다."
    if response.failure_type is not None:
        message += f" 실패 유형은 {response.failure_type}입니다."
    if response.broker_order_id is not None:
        message += f" 증권사 주문번호는 {response.broker_order_id}입니다."
    return message


def format_single_conditional_preview_message(
    display_name: str, preview: SingleConditionalOrderPreviewResponse
) -> str:
    side = "매수" if preview.side.value == "BUY" else "매도"
    if preview.order_type is OrderType.MARKET:
        terms = "시장가"
    else:
        assert preview.order_price is not None
        terms = f"{decimal_to_korean(preview.order_price)} {currency_unit(preview.currency)} 지정가"
    return (
        f"{display_name} 가격이 {decimal_to_korean(preview.trigger_price)} "
        f"{currency_unit(preview.currency)}에 도달하면 {decimal_to_korean(preview.quantity)} 주를 "
        f"{terms}로 {side}하는 조건 주문입니다. 만료일은 {preview.expire_date.isoformat()}입니다. "
        "실행하려면 승인, 그만두려면 취소라고 말씀해주세요."
    )


def format_conditional_cancellation_preview_message(
    preview: ConditionalOrderCancellationPreviewResponse,
) -> str:
    return (
        f"조건주문번호 {preview.conditional_order_id}, {preview.symbol} "
        f"{preview.conditional_order_type} 조건 주문을 취소합니다. "
        "실행하려면 승인, 그만두려면 취소라고 말씀해주세요."
    )


def format_dual_conditional_preview_message(
    display_name: str,
    preview: OcoConditionalOrderPreviewResponse | OtoConditionalOrderPreviewResponse,
) -> str:
    first_side = "매수" if preview.first.side is OrderSide.BUY else "매도"
    second_side = "매수" if preview.second.side is OrderSide.BUY else "매도"
    unit = currency_unit(preview.currency)
    return (
        f"{display_name} {decimal_to_korean(preview.quantity)} 주 "
        f"{preview.conditional_order_type.value} "
        f"조건 주문입니다. 첫 조건은 감시가격 {decimal_to_korean(preview.first.trigger_price)} "
        f"{unit}, 주문가격 {decimal_to_korean(preview.first.order_price)} {unit} {first_side}, "
        f"둘째 조건은 감시가격 {decimal_to_korean(preview.second.trigger_price)} {unit}, "
        f"주문가격 {decimal_to_korean(preview.second.order_price)} {unit} {second_side}입니다. "
        f"만료일은 {preview.expire_date.isoformat()}입니다. "
        "실행하려면 승인, 그만두려면 취소라고 말씀해주세요."
    )


def format_conditional_modification_preview_message(
    preview: ConditionalOrderModificationPreviewResponse,
) -> str:
    first_side = "매수" if preview.requested_first.side is OrderSide.BUY else "매도"
    unit = currency_unit(preview.currency)
    first_price = (
        "시장가"
        if preview.requested_first.order_price is None
        else f"주문가격 {decimal_to_korean(preview.requested_first.order_price)} {unit}"
    )
    second = ""
    if preview.requested_second is not None:
        second_side = "매수" if preview.requested_second.side is OrderSide.BUY else "매도"
        assert preview.requested_second.order_price is not None
        second = (
            f" 둘째 조건은 감시가격 "
            f"{decimal_to_korean(preview.requested_second.trigger_price)} {unit}, "
            f"주문가격 {decimal_to_korean(preview.requested_second.order_price)} "
            f"{unit} {second_side}입니다."
        )
    return (
        f"조건주문번호 {preview.original_conditional_order_id}를 "
        f"{preview.requested_type.value} 유형, "
        f"{decimal_to_korean(preview.requested_quantity)} 주로 "
        f"정정합니다. 첫 조건은 감시가격 "
        f"{decimal_to_korean(preview.requested_first.trigger_price)} {unit}, "
        f"{first_price} {first_side}입니다.{second} "
        f"만료일은 {preview.requested_expire_date.isoformat()}입니다. "
        "실행하려면 승인, 그만두려면 취소라고 말씀해주세요."
    )

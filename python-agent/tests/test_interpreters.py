from decimal import Decimal

import pytest

from jusika_agent.interpreters import RuleBasedCommandInterpreter
from jusika_agent.models import Intent, OrderType


@pytest.mark.parametrize(
    ("text", "intent"),
    [
        ("삼성전자 지금 얼마야", Intent.PRICE_QUERY),
        ("내 보유 주식 알려줘", Intent.HOLDINGS_QUERY),
        ("삼성전자 5주 사줘", Intent.BUY),
        ("삼성전자 2주 팔아", Intent.SELL),
        ("미체결 주문 알려줘", Intent.ORDER_LIST),
        ("조건 주문 목록 알려줘", Intent.CONDITIONAL_ORDER_LIST),
        ("주문번호 order-123 취소해줘", Intent.ORDER_CANCEL),
        ("주문번호 order-123 정정해줘", Intent.ORDER_MODIFY),
        ("조건주문번호 conditional-123 취소해줘", Intent.CONDITIONAL_ORDER_CANCEL),
        ("오늘 날씨 알려줘", Intent.UNKNOWN),
    ],
)
async def test_rule_interpreter_detects_mvp_intents(text: str, intent: Intent) -> None:
    parsed = await RuleBasedCommandInterpreter().interpret(text)

    assert parsed.intent is intent


async def test_rule_interpreter_extracts_limit_order_values() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("삼성전자 5주를 70,000원에 사줘")

    assert parsed.intent is Intent.BUY
    assert parsed.stock_name == "삼성전자"
    assert parsed.quantity == Decimal("5")
    assert parsed.price == Decimal("70000")
    assert parsed.order_type is OrderType.LIMIT


async def test_rule_interpreter_understands_spoken_quantity() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("삼성전자 다섯 주 사줘")

    assert parsed.intent is Intent.BUY
    assert parsed.quantity == Decimal("5")


async def test_order_verb_wins_over_holdings_context() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("보유 중인 삼성전자 두 주 팔아")

    assert parsed.intent is Intent.SELL
    assert parsed.quantity == Decimal("2")


async def test_rule_interpreter_extracts_modification_target_and_values() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret(
        "주문번호 order-123을 7주 71,000원으로 정정해줘"
    )

    assert parsed.intent is Intent.ORDER_MODIFY
    assert parsed.order_id == "order-123"
    assert parsed.quantity == Decimal("7")
    assert parsed.price == Decimal("71000")
    assert parsed.order_type is OrderType.LIMIT


async def test_rule_interpreter_keeps_conditional_id_separate() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("조건주문번호 conditional-123 취소해줘")

    assert parsed.intent is Intent.CONDITIONAL_ORDER_CANCEL
    assert parsed.conditional_order_id == "conditional-123"


async def test_rule_interpreter_extracts_single_conditional_order() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret(
        "삼성전자 80,000원이 되면 시장가로 2주 매도 조건주문 만료일 2026-09-30"
    )

    assert parsed.intent is Intent.SINGLE_CONDITIONAL_ORDER
    assert parsed.trigger_price == Decimal("80000")
    assert parsed.price is None
    assert parsed.quantity == Decimal("2")
    assert parsed.side is not None and parsed.side.value == "SELL"
    assert parsed.order_type is OrderType.MARKET
    assert parsed.expire_date is not None
    assert parsed.expire_date.isoformat() == "2026-09-30"


@pytest.mark.parametrize(
    ("kind", "expected_intent", "first_side"),
    [
        ("OCO", Intent.OCO_CONDITIONAL_ORDER, "SELL"),
        ("OTO", Intent.OTO_CONDITIONAL_ORDER, "BUY"),
    ],
)
async def test_rule_interpreter_keeps_dual_conditions_separate(
    kind: str, expected_intent: Intent, first_side: str
) -> None:
    parsed = await RuleBasedCommandInterpreter().interpret(
        f"삼성전자 2주 {kind} 조건주문 "
        "첫 조건 감시가 80,000원 주문가 79,000원 "
        "둘째 조건 감시가 65,000원 주문가 64,900원 "
        "만료일 2026-09-30"
    )

    assert parsed.intent is expected_intent
    assert parsed.first_condition is not None
    assert parsed.second_condition is not None
    assert parsed.first_condition.side.value == first_side
    assert parsed.first_condition.trigger_price == Decimal("80000")
    assert parsed.first_condition.order_price == Decimal("79000")
    assert parsed.second_condition.side.value == "SELL"
    assert parsed.second_condition.trigger_price == Decimal("65000")
    assert parsed.second_condition.order_price == Decimal("64900")


async def test_rule_interpreter_extracts_full_conditional_modification() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret(
        "조건주문번호 conditional-123을 OCO 조건주문으로 정정 2주 "
        "첫 조건 감시가 80,000원 주문가 79,000원 "
        "둘째 조건 감시가 65,000원 주문가 64,900원 "
        "만료일 2026-09-30"
    )

    assert parsed.intent is Intent.CONDITIONAL_ORDER_MODIFY
    assert parsed.conditional_order_id == "conditional-123"
    assert parsed.conditional_order_type is not None
    assert parsed.conditional_order_type.value == "OCO"
    assert parsed.first_condition is not None
    assert parsed.second_condition is not None

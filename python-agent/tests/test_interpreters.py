from decimal import Decimal
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from openai import APIConnectionError, APIStatusError

from jusika_agent.interpreters import (
    IntentInterpretationError,
    OpenAICommandInterpreter,
    RuleBasedCommandInterpreter,
)
from jusika_agent.models import Currency, ExecutionKind, Intent, OrderSide, OrderType, ParsedIntent


class FakeResponses:
    def __init__(self, outcomes: list[object]) -> None:
        self._outcomes = outcomes
        self.calls: list[dict[str, Any]] = []

    async def parse(self, **kwargs: Any) -> object:
        self.calls.append(kwargs)
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return SimpleNamespace(output_parsed=outcome)


class FakeOpenAIClient:
    def __init__(self, outcomes: list[object]) -> None:
        self.responses = FakeResponses(outcomes)

    async def close(self) -> None:
        return None


def api_status_error(status_code: int) -> APIStatusError:
    request = httpx.Request("POST", "https://api.openai.com/v1/responses")
    response = httpx.Response(status_code, request=request)
    return APIStatusError("OpenAI API error", response=response, body=None)


@pytest.mark.parametrize(
    ("text", "intent"),
    [
        ("삼성전자 지금 얼마야", Intent.PRICE_QUERY),
        ("지금 달러 환율 알려줘", Intent.EXCHANGE_RATE_QUERY),
        ("10만 원을 달러로 환전해줘", Intent.CURRENCY_EXCHANGE),
        ("내 보유 주식 알려줘", Intent.HOLDINGS_QUERY),
        ("달러 주문 가능 금액 알려줘", Intent.BUYING_POWER_QUERY),
        ("내 주식 수수료 알려줘", Intent.COMMISSIONS_QUERY),
        ("삼성전자 매도 가능 수량 알려줘", Intent.SELLABLE_QUANTITY_QUERY),
        ("삼성전자 5주 사줘", Intent.BUY),
        ("애플 200달러어치 매수해줘", Intent.AMOUNT_BUY),
        ("삼성전자 2주 팔아", Intent.SELL),
        ("미체결 주문 알려줘", Intent.ORDER_LIST),
        ("지난 주문 내역 알려줘", Intent.ORDER_HISTORY_QUERY),
        ("주문번호 order-123 상태 알려줘", Intent.ORDER_DETAIL_QUERY),
        ("조건 주문 목록 알려줘", Intent.CONDITIONAL_ORDER_LIST),
        ("조건주문번호 conditional-123 상세 알려줘", Intent.CONDITIONAL_ORDER_DETAIL_QUERY),
        ("실행번호 execution-123 상태 알려줘", Intent.EXECUTION_STATUS_QUERY),
        ("금액 주문 실행번호 execution-123 복구해줘", Intent.EXECUTION_RECOVER),
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


async def test_rule_interpreter_extracts_dollar_conversion_query() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("100달러는 원화로 얼마야")

    assert parsed.intent is Intent.EXCHANGE_RATE_QUERY
    assert parsed.exchange_amount == Decimal("100")
    assert parsed.base_currency is Currency.USD
    assert parsed.quote_currency is Currency.KRW


async def test_rule_interpreter_keeps_actual_exchange_separate_from_estimate() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("10만 원을 달러로 환전해줘")

    assert parsed.intent is Intent.CURRENCY_EXCHANGE
    assert parsed.exchange_amount == Decimal("100000")
    assert parsed.base_currency is Currency.KRW
    assert parsed.quote_currency is Currency.USD


async def test_rule_interpreter_extracts_amount_execution_kind() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret(
        "금액 주문 실행번호 amount-execution-1 상태 알려줘"
    )

    assert parsed.intent is Intent.EXECUTION_STATUS_QUERY
    assert parsed.execution_id == "amount-execution-1"
    assert parsed.execution_kind is ExecutionKind.AMOUNT_ORDER


async def test_rule_interpreter_understands_spoken_quantity() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("삼성전자 다섯 주 사줘")

    assert parsed.intent is Intent.BUY
    assert parsed.quantity == Decimal("5")


async def test_rule_interpreter_extracts_usd_amount_order() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("애플 200달러어치 매수해줘")

    assert parsed.intent is Intent.AMOUNT_BUY
    assert parsed.stock_name == "애플"
    assert parsed.order_amount == Decimal("200")
    assert parsed.amount_currency is Currency.USD
    assert parsed.quantity is None
    assert parsed.price is None
    assert parsed.order_type is OrderType.MARKET


async def test_rule_interpreter_extracts_won_amount_for_explicit_rejection() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("삼성전자 10만 원어치 사줘")

    assert parsed.intent is Intent.AMOUNT_BUY
    assert parsed.order_amount == Decimal("100000")
    assert parsed.amount_currency is Currency.KRW


async def test_rule_interpreter_preserves_unsupported_amount_sell() -> None:
    parsed = await RuleBasedCommandInterpreter().interpret("애플 200달러어치 팔아")

    assert parsed.intent is Intent.SELL
    assert parsed.order_amount == Decimal("200")
    assert parsed.amount_currency is Currency.USD


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


async def test_openai_interpreter_uses_structured_outputs_without_storage() -> None:
    expected = ParsedIntent(
        intent=Intent.BUY,
        stock_name="삼성전자",
        quantity=Decimal("5"),
        side=OrderSide.BUY,
    )
    client = FakeOpenAIClient([expected])
    interpreter = OpenAICommandInterpreter(
        api_key="test-key",
        model="test-model",
        client=client,
    )

    parsed = await interpreter.interpret("삼성전자 다섯 주 사줘")

    assert parsed == expected
    assert len(client.responses.calls) == 1
    request = client.responses.calls[0]
    assert request["model"] == "test-model"
    assert request["input"] == "삼성전자 다섯 주 사줘"
    assert request["text_format"] is ParsedIntent
    assert request["max_output_tokens"] == 1000
    assert request["reasoning"] == {"effort": "none"}
    assert request["store"] is False
    assert "수량, 가격, 주문번호 또는 종목을 추측" in request["instructions"]


async def test_openai_interpreter_retries_a_transient_connection_error() -> None:
    request = httpx.Request("POST", "https://api.openai.com/v1/responses")
    expected = ParsedIntent(intent=Intent.HOLDINGS_QUERY)
    client = FakeOpenAIClient([APIConnectionError(request=request), expected])
    interpreter = OpenAICommandInterpreter(
        api_key="test-key",
        model="test-model",
        max_attempts=3,
        retry_base_delay_seconds=0,
        client=client,
    )

    parsed = await interpreter.interpret("내 보유 주식 알려줘")

    assert parsed == expected
    assert len(client.responses.calls) == 2


@pytest.mark.parametrize("status_code", [408, 409, 429, 500, 503])
async def test_openai_interpreter_retries_transient_api_statuses(status_code: int) -> None:
    expected = ParsedIntent(intent=Intent.ORDER_LIST)
    client = FakeOpenAIClient([api_status_error(status_code), expected])
    interpreter = OpenAICommandInterpreter(
        api_key="test-key",
        model="test-model",
        retry_base_delay_seconds=0,
        client=client,
    )

    parsed = await interpreter.interpret("미체결 주문 알려줘")

    assert parsed == expected
    assert len(client.responses.calls) == 2


async def test_openai_interpreter_does_not_retry_a_bad_request() -> None:
    client = FakeOpenAIClient([api_status_error(400)])
    interpreter = OpenAICommandInterpreter(
        api_key="test-key",
        model="test-model",
        retry_base_delay_seconds=0,
        client=client,
    )

    with pytest.raises(IntentInterpretationError, match="다시 말씀해주세요"):
        await interpreter.interpret("삼성전자 사줘")

    assert len(client.responses.calls) == 1


async def test_openai_interpreter_stops_after_bounded_retries() -> None:
    request = httpx.Request("POST", "https://api.openai.com/v1/responses")
    client = FakeOpenAIClient(
        [
            APIConnectionError(request=request),
            APIConnectionError(request=request),
        ]
    )
    interpreter = OpenAICommandInterpreter(
        api_key="test-key",
        model="test-model",
        max_attempts=2,
        retry_base_delay_seconds=0,
        client=client,
    )

    with pytest.raises(IntentInterpretationError, match="다시 말씀해주세요"):
        await interpreter.interpret("삼성전자 사줘")

    assert len(client.responses.calls) == 2


async def test_openai_interpreter_rejects_an_unparsed_response_without_retry() -> None:
    client = FakeOpenAIClient([None])
    interpreter = OpenAICommandInterpreter(
        api_key="test-key",
        model="test-model",
        client=client,
    )

    with pytest.raises(IntentInterpretationError, match="안전한 형식"):
        await interpreter.interpret("삼성전자 사줘")

    assert len(client.responses.calls) == 1


@pytest.mark.parametrize(
    "kwargs",
    [
        {"timeout_seconds": 0},
        {"max_output_tokens": 255},
        {"max_output_tokens": 4097},
        {"max_attempts": 0},
        {"max_attempts": 6},
        {"retry_base_delay_seconds": -1},
    ],
)
def test_openai_interpreter_rejects_unsafe_runtime_limits(kwargs: dict[str, Any]) -> None:
    with pytest.raises(ValueError):
        OpenAICommandInterpreter(
            api_key="test-key",
            model="test-model",
            client=FakeOpenAIClient([]),
            **kwargs,
        )

"""Natural-language command interpreters."""

import re
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Protocol

from openai import AsyncOpenAI

from jusika_agent.models import (
    ConditionalOrderType,
    Currency,
    Intent,
    OrderSide,
    OrderType,
    ParsedCondition,
    ParsedIntent,
)


class IntentInterpretationError(RuntimeError):
    """Raised when natural language cannot be converted into the strict intent schema."""


class CommandInterpreter(Protocol):
    """Converts one user utterance into a typed intent."""

    async def interpret(self, text: str) -> ParsedIntent: ...


class OpenAICommandInterpreter:
    """Responses API interpreter using Pydantic Structured Outputs."""

    _instructions = """
당신은 시각장애 사용자의 한국어 주식 명령을 구조화하는 파서입니다.
투자 판단이나 추천을 하지 말고 사용자가 명시한 내용만 추출하세요.
지원 intent는 PRICE_QUERY, HOLDINGS_QUERY, BUY, SELL, AMOUNT_BUY, ORDER_LIST, ORDER_CANCEL,
ORDER_MODIFY, CONDITIONAL_ORDER_LIST, SINGLE_CONDITIONAL_ORDER,
CONDITIONAL_ORDER_CANCEL, UNKNOWN입니다.
시장가라는 말이 없더라도 가격을 명시하지 않은 매수/매도는 MARKET입니다.
가격을 명시한 주문은 LIMIT이며 price에 숫자만 기록하세요.
금액으로 매수하려는 명령은 AMOUNT_BUY이며 order_amount와 사용자가 말한
amount_currency=USD 또는 KRW를 기록하세요. 수량과 주문 금액을 혼동하지 마세요.
금액 매도는 SELL로 기록하되 말한 order_amount와 amount_currency를 보존하세요.
일반 주문 취소·정정은 사용자가 명시한 order_id만 기록하고 추측하지 마세요.
조건 주문 취소는 conditional_order_id만 기록하고 일반 order_id와 혼동하지 마세요.
SINGLE_CONDITIONAL_ORDER, OCO_CONDITIONAL_ORDER, OTO_CONDITIONAL_ORDER,
CONDITIONAL_ORDER_MODIFY도 지원합니다. 조건 주문 유형은 conditional_order_type에 기록하세요.
두 조건 주문은 first_condition과 second_condition을 서로 바꾸지 말고 각각 기록하세요.
SINGLE에서 trigger_price는 감시가격, price는 발동 후 지정가이며 시장가이면 null입니다.
조건 주문 정정은 정정 후 전체 조건만 기록하고 빠진 값을 추측하지 마세요.
expire_date도 사용자가 명시한 값만 기록하세요.
수량과 가격이 명시되지 않았다면 추측하지 말고 null로 두세요.
종목 코드가 확실하지 않으면 symbol을 null로 두고 stock_name만 기록하세요.
""".strip()

    def __init__(self, *, api_key: str, model: str) -> None:
        if not api_key:
            raise ValueError("OPENAI_API_KEY가 설정되지 않았습니다.")
        self._client = AsyncOpenAI(api_key=api_key)
        self._model = model

    async def interpret(self, text: str) -> ParsedIntent:
        try:
            response = await self._client.responses.parse(
                model=self._model,
                instructions=self._instructions,
                input=text,
                text_format=ParsedIntent,
                store=False,
            )
        except Exception as exc:
            raise IntentInterpretationError("명령 해석 서비스에 연결하지 못했습니다.") from exc

        parsed = response.output_parsed
        if parsed is None:
            raise IntentInterpretationError("명령을 안전한 형식으로 해석하지 못했습니다.")
        return parsed


class RuleBasedCommandInterpreter:
    """Offline MVP interpreter for deterministic local and CI testing."""

    _quantity_pattern = re.compile(r"(?P<quantity>\d+(?:\.\d+)?)\s*(?:주|개)")
    _spoken_quantity_pattern = re.compile(
        r"(?P<quantity>한|하나|두|둘|세|셋|네|넷|다섯|여섯|일곱|여덟|아홉|열)\s*(?:주|개)"
    )
    _spoken_quantities = {
        "한": Decimal(1),
        "하나": Decimal(1),
        "두": Decimal(2),
        "둘": Decimal(2),
        "세": Decimal(3),
        "셋": Decimal(3),
        "네": Decimal(4),
        "넷": Decimal(4),
        "다섯": Decimal(5),
        "여섯": Decimal(6),
        "일곱": Decimal(7),
        "여덟": Decimal(8),
        "아홉": Decimal(9),
        "열": Decimal(10),
    }
    _won_price_pattern = re.compile(r"(?P<price>\d[\d,]*(?:\.\d+)?)\s*원(?:에|으로)?")
    _dollar_price_pattern = re.compile(r"(?P<price>\d[\d,]*(?:\.\d+)?)\s*(?:달러|불)(?:에|로)?")
    _kr_symbol_pattern = re.compile(r"(?<!\d)(?P<symbol>\d{6})(?!\d)")
    _us_symbol_pattern = re.compile(r"\b(?P<symbol>[A-Za-z][A-Za-z0-9.-]{0,9})\b")
    _known_names = ("삼성전자", "삼전", "애플")
    _order_id_pattern = re.compile(
        r"(?:주문\s*번호|주문번호)\s*(?:는|은|:)?\s*"
        r"(?P<id>[A-Za-z0-9][A-Za-z0-9._:-]{0,127})"
    )
    _conditional_order_id_pattern = re.compile(
        r"(?:조건\s*주문\s*번호|조건주문번호)\s*(?:는|은|:)?\s*"
        r"(?P<id>[A-Za-z0-9][A-Za-z0-9._:-]{0,127})"
    )
    _trigger_price_pattern = re.compile(
        r"(?P<price>\d[\d,]*(?:\.\d+)?)\s*(?:원|달러|불)(?:이|가)?\s*"
        r"(?:되면|도달하면|도달\s*시|이상이면|이하면)"
    )
    _order_price_pattern = re.compile(
        r"(?:주문\s*가격|지정가|발동\s*후)\s*"
        r"(?P<price>\d[\d,]*(?:\.\d+)?)\s*(?:원|달러|불)"
    )
    _expire_date_pattern = re.compile(
        r"(?:만료일|만료|까지)?\s*(?P<date>20\d{2}-\d{2}-\d{2})(?:까지)?"
    )
    _condition_trigger_pattern = re.compile(
        r"(?:감시가|감시가격|발동가|발동가격)\s*(?:는|은|:)?\s*"
        r"(?P<price>\d[\d,]*(?:\.\d+)?)\s*(?:원|달러|불)?"
    )
    _condition_order_pattern = re.compile(
        r"(?:주문가|주문가격|지정가)\s*(?:는|은|:)?\s*"
        r"(?P<price>\d[\d,]*(?:\.\d+)?)\s*(?:원|달러|불)?"
    )
    _first_condition_segment = re.compile(
        r"(?:첫(?:째|\s*번째)?|1번)\s*조건(?P<body>.*?)(?=(?:둘째|두\s*번째|2번)\s*조건|만료일|$)"
    )
    _second_condition_segment = re.compile(
        r"(?:둘째|두\s*번째|2번)\s*조건(?P<body>.*?)(?=만료일|$)"
    )
    _dollar_amount_pattern = re.compile(
        r"(?P<amount>\d[\d,]*(?:\.\d+)?)\s*(?:달러|불)\s*(?:어치|만큼|금액|로)"
    )
    _won_amount_pattern = re.compile(
        r"(?P<amount>\d[\d,]*(?:\.\d+)?)\s*(?P<unit>만|천)?\s*원\s*"
        r"(?:어치|만큼|금액)"
    )

    async def interpret(self, text: str) -> ParsedIntent:
        normalized = " ".join(text.strip().split())
        intent = self._detect_intent(normalized)
        stock_name = next((name for name in self._known_names if name in normalized), None)
        symbol = self._extract_symbol(normalized)
        quantity = self._extract_quantity(normalized)
        order_amount, amount_currency = self._extract_amount(normalized)
        order_id = self._extract_id(self._order_id_pattern, normalized)
        conditional_order_id = self._extract_id(self._conditional_order_id_pattern, normalized)
        trigger_price = self._extract_decimal(self._trigger_price_pattern, normalized, "price")
        price = self._extract_order_price(normalized, intent, trigger_price)
        order_type = (
            OrderType.MARKET
            if "시장가" in normalized
            else OrderType.LIMIT
            if price is not None
            else OrderType.MARKET
        )
        expire_date = self._extract_date(normalized)
        side = self._extract_side(normalized)
        conditional_order_type = self._extract_conditional_order_type(normalized, intent)
        first_condition, second_condition = self._extract_conditions(
            normalized, intent, conditional_order_type, side, trigger_price, price
        )

        return ParsedIntent(
            intent=intent,
            stock_name=stock_name,
            symbol=symbol,
            quantity=quantity,
            order_amount=order_amount,
            amount_currency=amount_currency,
            price=price,
            trigger_price=trigger_price,
            order_type=order_type,
            side=side,
            order_id=order_id,
            conditional_order_id=conditional_order_id,
            conditional_order_type=conditional_order_type,
            first_condition=first_condition,
            second_condition=second_condition,
            expire_date=expire_date,
        )

    @staticmethod
    def _detect_intent(text: str) -> Intent:
        conditional = "조건" in text and "주문" in text
        if conditional and any(word in text for word in ("취소", "취소해", "취소해줘")):
            return Intent.CONDITIONAL_ORDER_CANCEL
        if conditional and any(word in text for word in ("정정", "변경")):
            return Intent.CONDITIONAL_ORDER_MODIFY
        if conditional and any(word in text for word in ("목록", "조회", "보여", "알려")):
            return Intent.CONDITIONAL_ORDER_LIST
        if conditional and "OCO" in text.upper():
            return Intent.OCO_CONDITIONAL_ORDER
        if conditional and "OTO" in text.upper():
            return Intent.OTO_CONDITIONAL_ORDER
        if conditional and any(
            word in text for word in ("되면", "도달하면", "도달 시", "이상이면", "이하면")
        ):
            return Intent.SINGLE_CONDITIONAL_ORDER
        if any(word in text for word in ("정정", "변경")) and "주문" in text:
            return Intent.ORDER_MODIFY
        if "취소" in text and "주문" in text:
            return Intent.ORDER_CANCEL
        if any(word in text for word in ("미체결 주문", "열린 주문", "진행 중 주문")):
            return Intent.ORDER_LIST
        buy = any(word in text for word in ("매수", "사줘", "사 줘", "살래", "사고 싶"))
        if buy and any(word in text for word in ("어치", "만큼", "금액으로", "달러로", "불로")):
            return Intent.AMOUNT_BUY
        if buy:
            return Intent.BUY
        if any(word in text for word in ("매도", "팔아", "팔래", "팔고 싶")):
            return Intent.SELL
        if any(word in text for word in ("보유", "잔고", "내 주식", "가지고 있")):
            return Intent.HOLDINGS_QUERY
        if any(word in text for word in ("현재가", "가격", "얼마")):
            return Intent.PRICE_QUERY
        return Intent.UNKNOWN

    def _extract_symbol(self, text: str) -> str | None:
        kr_match = self._kr_symbol_pattern.search(text)
        if kr_match:
            return kr_match.group("symbol")
        us_match = self._us_symbol_pattern.search(text)
        return us_match.group("symbol").upper() if us_match else None

    def _extract_price(self, text: str) -> Decimal | None:
        return self._extract_decimal(
            self._won_price_pattern, text, "price"
        ) or self._extract_decimal(
            self._dollar_price_pattern,
            text,
            "price",
        )

    def _extract_order_price(
        self, text: str, intent: Intent, trigger_price: Decimal | None
    ) -> Decimal | None:
        if intent is Intent.AMOUNT_BUY:
            return None
        if intent not in {Intent.SINGLE_CONDITIONAL_ORDER, Intent.CONDITIONAL_ORDER_MODIFY}:
            return self._extract_price(text)
        explicit = self._extract_decimal(self._order_price_pattern, text, "price")
        if explicit is not None:
            return explicit
        prices = [
            Decimal(match.group("price").replace(",", ""))
            for pattern in (self._won_price_pattern, self._dollar_price_pattern)
            for match in pattern.finditer(text)
        ]
        if trigger_price is not None and prices and prices[0] == trigger_price:
            prices = prices[1:]
        return prices[0] if prices else None

    def _extract_quantity(self, text: str) -> Decimal | None:
        numeric = self._extract_decimal(self._quantity_pattern, text, "quantity")
        if numeric is not None:
            return numeric
        spoken = self._spoken_quantity_pattern.search(text)
        if spoken is None:
            return None
        return self._spoken_quantities[spoken.group("quantity")]

    def _extract_amount(self, text: str) -> tuple[Decimal | None, Currency | None]:
        dollars = self._extract_decimal(self._dollar_amount_pattern, text, "amount")
        if dollars is not None:
            return dollars, Currency.USD
        won = self._won_amount_pattern.search(text)
        if won is None:
            return None, None
        try:
            amount = Decimal(won.group("amount").replace(",", ""))
        except InvalidOperation:
            return None, None
        multiplier = {None: Decimal(1), "천": Decimal(1000), "만": Decimal(10000)}
        return amount * multiplier[won.group("unit")], Currency.KRW

    @staticmethod
    def _extract_id(pattern: re.Pattern[str], text: str) -> str | None:
        match = pattern.search(text)
        return match.group("id") if match else None

    def _extract_date(self, text: str) -> date | None:
        match = self._expire_date_pattern.search(text)
        if match is None:
            return None
        try:
            return date.fromisoformat(match.group("date"))
        except ValueError:
            return None

    @staticmethod
    def _extract_conditional_order_type(text: str, intent: Intent) -> ConditionalOrderType | None:
        upper = text.upper()
        if "OCO" in upper or intent is Intent.OCO_CONDITIONAL_ORDER:
            return ConditionalOrderType.OCO
        if "OTO" in upper or intent is Intent.OTO_CONDITIONAL_ORDER:
            return ConditionalOrderType.OTO
        if intent in {Intent.SINGLE_CONDITIONAL_ORDER, Intent.CONDITIONAL_ORDER_MODIFY}:
            if "SINGLE" in upper or "단일" in text or intent is Intent.SINGLE_CONDITIONAL_ORDER:
                return ConditionalOrderType.SINGLE
        return None

    def _extract_conditions(
        self,
        text: str,
        intent: Intent,
        conditional_type: ConditionalOrderType | None,
        side: OrderSide | None,
        trigger_price: Decimal | None,
        order_price: Decimal | None,
    ) -> tuple[ParsedCondition | None, ParsedCondition | None]:
        if conditional_type is ConditionalOrderType.OCO:
            return (
                self._extract_labeled_condition(
                    self._first_condition_segment, text, OrderSide.SELL
                ),
                self._extract_labeled_condition(
                    self._second_condition_segment, text, OrderSide.SELL
                ),
            )
        if conditional_type is ConditionalOrderType.OTO:
            return (
                self._extract_labeled_condition(self._first_condition_segment, text, OrderSide.BUY),
                self._extract_labeled_condition(
                    self._second_condition_segment, text, OrderSide.SELL
                ),
            )
        if intent in {Intent.SINGLE_CONDITIONAL_ORDER, Intent.CONDITIONAL_ORDER_MODIFY}:
            if side is not None and trigger_price is not None:
                return (
                    ParsedCondition(
                        side=side,
                        trigger_price=trigger_price,
                        order_price=order_price,
                    ),
                    None,
                )
        return None, None

    def _extract_labeled_condition(
        self, segment_pattern: re.Pattern[str], text: str, side: OrderSide
    ) -> ParsedCondition | None:
        segment = segment_pattern.search(text)
        if segment is None:
            return None
        body = segment.group("body")
        trigger_price = self._extract_decimal(self._condition_trigger_pattern, body, "price")
        order_price = self._extract_decimal(self._condition_order_pattern, body, "price")
        if trigger_price is None or order_price is None:
            return None
        return ParsedCondition(side=side, trigger_price=trigger_price, order_price=order_price)

    @staticmethod
    def _extract_side(text: str) -> OrderSide | None:
        if any(word in text for word in ("매수", "사줘", "사 줘", "살래", "사고 싶")):
            return OrderSide.BUY
        if any(word in text for word in ("매도", "팔아", "팔래", "팔고 싶")):
            return OrderSide.SELL
        return None

    @staticmethod
    def _extract_decimal(pattern: re.Pattern[str], text: str, group: str) -> Decimal | None:
        match = pattern.search(text)
        if match is None:
            return None
        try:
            return Decimal(match.group(group).replace(",", ""))
        except InvalidOperation:
            return None

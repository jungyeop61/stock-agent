"""LangGraph workflow that enforces preview-before-approval for every mutation."""

from typing import Any, Literal, Protocol, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import interrupt

from jusika_agent.instruments import Instrument, InstrumentCatalog, InstrumentResolutionError
from jusika_agent.interpreters import CommandInterpreter, IntentInterpretationError
from jusika_agent.models import (
    AccountResponse,
    AgentStatus,
    ConditionalOrderCancellationExecutionResponse,
    ConditionalOrderCancellationPreviewRequest,
    ConditionalOrderCancellationPreviewResponse,
    ConditionalOrderCreationExecutionResponse,
    ConditionalOrderListResponse,
    ConditionalOrderModificationExecutionResponse,
    ConditionalOrderModificationPreviewRequest,
    ConditionalOrderModificationPreviewResponse,
    ConditionalOrderMutationCondition,
    ConditionalOrderType,
    DualConditionalOrderPreviewRequest,
    HoldingsResponse,
    Intent,
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
    OrderSide,
    OrderType,
    OtoConditionalOrderPreviewResponse,
    ParsedIntent,
    SingleConditionalOrderExecutionResponse,
    SingleConditionalOrderPreviewRequest,
    SingleConditionalOrderPreviewResponse,
    StockPriceResponse,
)
from jusika_agent.speech import (
    format_cancellation_preview_message,
    format_conditional_cancellation_preview_message,
    format_conditional_modification_preview_message,
    format_conditional_order_list_message,
    format_dual_conditional_preview_message,
    format_holdings_message,
    format_modification_preview_message,
    format_order_list_message,
    format_preview_message,
    format_price_message,
    format_single_conditional_preview_message,
)
from jusika_agent.spring_client import SpringBackendError


class SpringGateway(Protocol):
    """Financial operations the graph may request from Spring."""

    async def list_accounts(self) -> list[AccountResponse]: ...
    async def get_stock_price(self, symbol: str) -> StockPriceResponse: ...
    async def get_holdings(self, account_seq: int) -> HoldingsResponse: ...
    async def create_order_preview(self, request: OrderPreviewRequest) -> OrderPreviewResponse: ...
    async def approve_order_preview(self, preview_id: str) -> OrderPreviewResponse: ...
    async def execute_order_preview(self, preview_id: str) -> OrderExecutionResponse: ...
    async def list_open_orders(self, account_seq: int) -> OrderListResponse: ...
    async def get_order(self, account_seq: int, order_id: str) -> OrderDetailResponse: ...
    async def create_order_cancellation_preview(
        self, request: OrderCancellationPreviewRequest
    ) -> OrderCancellationPreviewResponse: ...
    async def approve_order_cancellation_preview(
        self, preview_id: str
    ) -> OrderCancellationPreviewResponse: ...
    async def execute_order_cancellation_preview(
        self, preview_id: str
    ) -> OrderCancellationExecutionResponse: ...
    async def create_order_modification_preview(
        self, request: OrderModificationPreviewRequest
    ) -> OrderModificationPreviewResponse: ...
    async def approve_order_modification_preview(
        self, preview_id: str
    ) -> OrderModificationPreviewResponse: ...
    async def execute_order_modification_preview(
        self, preview_id: str
    ) -> OrderModificationExecutionResponse: ...
    async def list_open_conditional_orders(
        self, account_seq: int
    ) -> ConditionalOrderListResponse: ...
    async def create_single_conditional_order_preview(
        self, request: SingleConditionalOrderPreviewRequest
    ) -> SingleConditionalOrderPreviewResponse: ...
    async def approve_single_conditional_order_preview(
        self, preview_id: str
    ) -> SingleConditionalOrderPreviewResponse: ...
    async def execute_single_conditional_order_preview(
        self, preview_id: str
    ) -> SingleConditionalOrderExecutionResponse: ...
    async def create_conditional_order_cancellation_preview(
        self, request: ConditionalOrderCancellationPreviewRequest
    ) -> ConditionalOrderCancellationPreviewResponse: ...
    async def approve_conditional_order_cancellation_preview(
        self, preview_id: str
    ) -> ConditionalOrderCancellationPreviewResponse: ...
    async def execute_conditional_order_cancellation_preview(
        self, preview_id: str
    ) -> ConditionalOrderCancellationExecutionResponse: ...
    async def create_oco_conditional_order_preview(
        self, request: DualConditionalOrderPreviewRequest
    ) -> OcoConditionalOrderPreviewResponse: ...
    async def approve_oco_conditional_order_preview(
        self, preview_id: str
    ) -> OcoConditionalOrderPreviewResponse: ...
    async def execute_oco_conditional_order_preview(
        self, preview_id: str
    ) -> ConditionalOrderCreationExecutionResponse: ...
    async def create_oto_conditional_order_preview(
        self, request: DualConditionalOrderPreviewRequest
    ) -> OtoConditionalOrderPreviewResponse: ...
    async def approve_oto_conditional_order_preview(
        self, preview_id: str
    ) -> OtoConditionalOrderPreviewResponse: ...
    async def execute_oto_conditional_order_preview(
        self, preview_id: str
    ) -> ConditionalOrderCreationExecutionResponse: ...
    async def create_conditional_order_modification_preview(
        self, request: ConditionalOrderModificationPreviewRequest
    ) -> ConditionalOrderModificationPreviewResponse: ...
    async def approve_conditional_order_modification_preview(
        self, preview_id: str
    ) -> ConditionalOrderModificationPreviewResponse: ...
    async def execute_conditional_order_modification_preview(
        self, preview_id: str
    ) -> ConditionalOrderModificationExecutionResponse: ...


class AgentState(TypedDict, total=False):
    """Serializable state kept in the LangGraph checkpoint store."""

    session_id: str
    user_text: str
    status: str
    message: str
    parsed_intent: dict[str, Any] | None
    display_name: str | None
    account_seq: int | None
    pending_action: str | None
    preview_id: str | None
    preview: dict[str, Any] | None
    execution: dict[str, Any] | None
    result: dict[str, Any] | None
    confirmation: str | None


class AgentInputError(ValueError):
    """Safe clarification error that can be spoken directly to the user."""


PreviewResponse = (
    OrderPreviewResponse
    | OrderCancellationPreviewResponse
    | OrderModificationPreviewResponse
    | SingleConditionalOrderPreviewResponse
    | ConditionalOrderCancellationPreviewResponse
    | OcoConditionalOrderPreviewResponse
    | OtoConditionalOrderPreviewResponse
    | ConditionalOrderModificationPreviewResponse
)
ExecutionResponse = (
    OrderExecutionResponse
    | OrderCancellationExecutionResponse
    | OrderModificationExecutionResponse
    | SingleConditionalOrderExecutionResponse
    | ConditionalOrderCancellationExecutionResponse
    | ConditionalOrderCreationExecutionResponse
    | ConditionalOrderModificationExecutionResponse
)


class AgentGraphNodes:
    """Node implementations with explicit access to interpreter and Spring gateway."""

    def __init__(
        self,
        *,
        interpreter: CommandInterpreter,
        spring: SpringGateway,
        catalog: InstrumentCatalog,
    ) -> None:
        self._interpreter = interpreter
        self._spring = spring
        self._catalog = catalog

    async def interpret(self, state: AgentState) -> AgentState:
        try:
            parsed = await self._interpreter.interpret(state["user_text"])
        except IntentInterpretationError as exc:
            return self._error(str(exc))
        return {"parsed_intent": parsed.model_dump(mode="json")}

    async def price(self, state: AgentState) -> AgentState:
        try:
            parsed = self._parsed(state)
            instrument = self._instrument(parsed, state["user_text"])
            price = await self._spring.get_stock_price(instrument.symbol)
        except (AgentInputError, InstrumentResolutionError, SpringBackendError) as exc:
            return self._error(str(exc), needs_input=isinstance(exc, InstrumentResolutionError))
        return {
            "status": AgentStatus.COMPLETED.value,
            "message": format_price_message(instrument.display_name, price),
            "display_name": instrument.display_name,
            "result": price.model_dump(mode="json", by_alias=True),
        }

    async def holdings(self, state: AgentState) -> AgentState:
        try:
            account_seq = await self._account_seq()
            holdings = await self._spring.get_holdings(account_seq)
        except (AgentInputError, SpringBackendError) as exc:
            return self._error(str(exc), needs_input=isinstance(exc, AgentInputError))
        return {
            "status": AgentStatus.COMPLETED.value,
            "message": format_holdings_message(holdings),
            "account_seq": account_seq,
            "result": holdings.model_dump(mode="json", by_alias=True),
        }

    async def orders(self, state: AgentState) -> AgentState:
        try:
            account_seq = await self._account_seq()
            orders = await self._spring.list_open_orders(account_seq)
        except (AgentInputError, SpringBackendError) as exc:
            return self._error(str(exc), needs_input=isinstance(exc, AgentInputError))
        return {
            "status": AgentStatus.COMPLETED.value,
            "message": format_order_list_message(orders),
            "account_seq": account_seq,
            "result": orders.model_dump(mode="json", by_alias=True),
        }

    async def conditional_orders(self, state: AgentState) -> AgentState:
        try:
            account_seq = await self._account_seq()
            orders = await self._spring.list_open_conditional_orders(account_seq)
        except (AgentInputError, SpringBackendError) as exc:
            return self._error(str(exc), needs_input=isinstance(exc, AgentInputError))
        return {
            "status": AgentStatus.COMPLETED.value,
            "message": format_conditional_order_list_message(orders),
            "account_seq": account_seq,
            "result": orders.model_dump(mode="json", by_alias=True),
        }

    async def create_mutation_preview(self, state: AgentState) -> AgentState:
        try:
            parsed = self._parsed(state)
            account_seq = await self._account_seq()
            preview, message, display_name = await self._create_preview(
                parsed=parsed,
                source_text=state["user_text"],
                account_seq=account_seq,
            )
        except (AgentInputError, InstrumentResolutionError, SpringBackendError) as exc:
            return self._error(
                str(exc),
                needs_input=isinstance(exc, (AgentInputError, InstrumentResolutionError)),
            )
        return {
            "status": AgentStatus.WAITING_CONFIRMATION.value,
            "message": message,
            "display_name": display_name,
            "account_seq": account_seq,
            "pending_action": parsed.intent.value,
            "preview_id": preview.preview_id,
            "preview": preview.model_dump(mode="json", by_alias=True),
        }

    async def _create_preview(
        self, *, parsed: ParsedIntent, source_text: str, account_seq: int
    ) -> tuple[PreviewResponse, str, str | None]:
        if parsed.intent in {Intent.BUY, Intent.SELL}:
            instrument = self._instrument(parsed, source_text)
            if parsed.quantity is None:
                raise AgentInputError("주문 수량을 다시 말씀해주세요. 예를 들면 다섯 주입니다.")
            if parsed.order_type is OrderType.LIMIT and parsed.price is None:
                raise AgentInputError("지정가 주문 가격을 다시 말씀해주세요.")
            side = OrderSide.BUY if parsed.intent is Intent.BUY else OrderSide.SELL
            order_preview = await self._spring.create_order_preview(
                OrderPreviewRequest(
                    account_seq=account_seq,
                    symbol=instrument.symbol,
                    side=side,
                    order_type=parsed.order_type,
                    quantity=parsed.quantity,
                    price=parsed.price,
                )
            )
            message = format_preview_message(instrument.display_name, order_preview)
            return order_preview, message, instrument.display_name

        if parsed.intent is Intent.ORDER_CANCEL:
            if parsed.order_id is None:
                raise AgentInputError("취소할 주문번호를 정확히 말씀해주세요.")
            cancellation_preview = await self._spring.create_order_cancellation_preview(
                OrderCancellationPreviewRequest(account_seq=account_seq, order_id=parsed.order_id)
            )
            return (
                cancellation_preview,
                format_cancellation_preview_message(cancellation_preview),
                None,
            )

        if parsed.intent is Intent.ORDER_MODIFY:
            return await self._create_modification_preview(parsed, source_text, account_seq)

        if parsed.intent is Intent.SINGLE_CONDITIONAL_ORDER:
            return await self._create_single_conditional_preview(parsed, source_text, account_seq)

        if parsed.intent in {Intent.OCO_CONDITIONAL_ORDER, Intent.OTO_CONDITIONAL_ORDER}:
            return await self._create_dual_conditional_preview(parsed, source_text, account_seq)

        if parsed.intent is Intent.CONDITIONAL_ORDER_CANCEL:
            if parsed.conditional_order_id is None:
                raise AgentInputError("취소할 조건주문번호를 정확히 말씀해주세요.")
            conditional_cancellation_preview = (
                await self._spring.create_conditional_order_cancellation_preview(
                    ConditionalOrderCancellationPreviewRequest(
                        account_seq=account_seq,
                        conditional_order_id=parsed.conditional_order_id,
                    )
                )
            )
            return (
                conditional_cancellation_preview,
                format_conditional_cancellation_preview_message(conditional_cancellation_preview),
                None,
            )

        if parsed.intent is Intent.CONDITIONAL_ORDER_MODIFY:
            return await self._create_conditional_modification_preview(parsed, account_seq)

        raise AgentInputError("지원하지 않는 변경 요청입니다.")

    async def _create_modification_preview(
        self, parsed: ParsedIntent, source_text: str, account_seq: int
    ) -> tuple[OrderModificationPreviewResponse, str, None]:
        if parsed.order_id is None:
            raise AgentInputError("정정할 주문번호를 정확히 말씀해주세요.")
        if parsed.price is None and "시장가" not in source_text:
            raise AgentInputError("정정할 시장가 또는 지정가 가격을 명확히 말씀해주세요.")
        original = await self._spring.get_order(account_seq, parsed.order_id)
        is_domestic = original.symbol.isdigit() and len(original.symbol) == 6
        if is_domestic and parsed.quantity is None:
            raise AgentInputError("국내 주문의 정정 수량을 다시 말씀해주세요.")
        if not is_domestic and parsed.quantity is not None:
            raise AgentInputError("미국 주문 정정은 수량 없이 변경할 가격만 말씀해주세요.")
        preview = await self._spring.create_order_modification_preview(
            OrderModificationPreviewRequest(
                account_seq=account_seq,
                order_id=parsed.order_id,
                order_type=parsed.order_type,
                quantity=parsed.quantity,
                price=parsed.price,
            )
        )
        return preview, format_modification_preview_message(preview), None

    async def _create_single_conditional_preview(
        self, parsed: ParsedIntent, source_text: str, account_seq: int
    ) -> tuple[SingleConditionalOrderPreviewResponse, str, str]:
        instrument = self._instrument(parsed, source_text)
        if parsed.side is None:
            raise AgentInputError("조건 충족 시 매수할지 매도할지 말씀해주세요.")
        if parsed.quantity is None:
            raise AgentInputError("조건 주문 수량을 다시 말씀해주세요.")
        if parsed.trigger_price is None:
            raise AgentInputError("조건을 발동할 감시가격을 다시 말씀해주세요.")
        if parsed.order_type is OrderType.LIMIT and parsed.price is None:
            raise AgentInputError("발동 후 제출할 지정가를 다시 말씀해주세요.")
        if parsed.expire_date is None:
            raise AgentInputError("조건 주문 만료일을 연도-월-일로 말씀해주세요.")
        preview = await self._spring.create_single_conditional_order_preview(
            SingleConditionalOrderPreviewRequest(
                account_seq=account_seq,
                symbol=instrument.symbol,
                side=parsed.side,
                order_type=parsed.order_type,
                quantity=parsed.quantity,
                trigger_price=parsed.trigger_price,
                order_price=parsed.price,
                expire_date=parsed.expire_date,
            )
        )
        message = format_single_conditional_preview_message(instrument.display_name, preview)
        return preview, message, instrument.display_name

    async def _create_dual_conditional_preview(
        self, parsed: ParsedIntent, source_text: str, account_seq: int
    ) -> tuple[OcoConditionalOrderPreviewResponse | OtoConditionalOrderPreviewResponse, str, str]:
        instrument = self._instrument(parsed, source_text)
        if parsed.quantity is None:
            raise AgentInputError("조건 주문 수량을 다시 말씀해주세요.")
        if parsed.expire_date is None:
            raise AgentInputError("조건 주문 만료일을 연도-월-일로 말씀해주세요.")
        if parsed.first_condition is None or parsed.second_condition is None:
            raise AgentInputError(
                "첫 조건과 둘째 조건의 감시가격과 주문가격을 모두 명확히 말씀해주세요."
            )
        request = DualConditionalOrderPreviewRequest(
            account_seq=account_seq,
            symbol=instrument.symbol,
            quantity=parsed.quantity,
            order_type=OrderType.LIMIT,
            expire_date=parsed.expire_date,
            first=ConditionalOrderMutationCondition(
                side=parsed.first_condition.side,
                trigger_price=parsed.first_condition.trigger_price,
                order_price=parsed.first_condition.order_price,
            ),
            second=ConditionalOrderMutationCondition(
                side=parsed.second_condition.side,
                trigger_price=parsed.second_condition.trigger_price,
                order_price=parsed.second_condition.order_price,
            ),
        )
        if parsed.intent is Intent.OCO_CONDITIONAL_ORDER:
            preview = await self._spring.create_oco_conditional_order_preview(request)
        else:
            preview = await self._spring.create_oto_conditional_order_preview(request)
        message = format_dual_conditional_preview_message(instrument.display_name, preview)
        return preview, message, instrument.display_name

    async def _create_conditional_modification_preview(
        self, parsed: ParsedIntent, account_seq: int
    ) -> tuple[ConditionalOrderModificationPreviewResponse, str, None]:
        if parsed.conditional_order_id is None:
            raise AgentInputError("정정할 조건주문번호를 정확히 말씀해주세요.")
        if parsed.conditional_order_type is None:
            raise AgentInputError("정정 후 유형을 단일, OCO 또는 OTO로 말씀해주세요.")
        if parsed.quantity is None:
            raise AgentInputError("정정 후 전체 주문 수량을 다시 말씀해주세요.")
        if parsed.expire_date is None:
            raise AgentInputError("정정 후 만료일을 연도-월-일로 말씀해주세요.")
        if parsed.first_condition is None:
            raise AgentInputError("정정 후 첫 조건을 모두 말씀해주세요.")
        dual = parsed.conditional_order_type in {
            ConditionalOrderType.OCO,
            ConditionalOrderType.OTO,
        }
        if dual and parsed.second_condition is None:
            raise AgentInputError("정정 후 둘째 조건을 모두 말씀해주세요.")
        if not dual and parsed.second_condition is not None:
            raise AgentInputError("단일 조건 주문에는 둘째 조건을 입력할 수 없습니다.")
        first = ConditionalOrderMutationCondition(
            side=parsed.first_condition.side,
            trigger_price=parsed.first_condition.trigger_price,
            order_price=parsed.first_condition.order_price,
        )
        second = None
        if parsed.second_condition is not None:
            second = ConditionalOrderMutationCondition(
                side=parsed.second_condition.side,
                trigger_price=parsed.second_condition.trigger_price,
                order_price=parsed.second_condition.order_price,
            )
        order_type = OrderType.LIMIT if dual else parsed.order_type
        if order_type is OrderType.LIMIT and first.order_price is None:
            raise AgentInputError("지정가 조건의 주문가격을 다시 말씀해주세요.")
        preview = await self._spring.create_conditional_order_modification_preview(
            ConditionalOrderModificationPreviewRequest(
                account_seq=account_seq,
                conditional_order_id=parsed.conditional_order_id,
                type=parsed.conditional_order_type,
                quantity=parsed.quantity,
                order_type=order_type,
                expire_date=parsed.expire_date,
                first=first,
                second=second,
            )
        )
        return preview, format_conditional_modification_preview_message(preview), None

    def await_confirmation(self, state: AgentState) -> AgentState:
        decision = interrupt(
            {
                "preview_id": state["preview_id"],
                "pending_action": state["pending_action"],
                "message": state["message"],
                "allowed_responses": ["APPROVE", "CANCEL"],
            }
        )
        return {"confirmation": str(decision)}

    async def execute(self, state: AgentState) -> AgentState:
        preview_id = state.get("preview_id")
        pending_action = state.get("pending_action")
        if not preview_id or not pending_action:
            return self._error("승인할 금융 요청 미리보기를 찾지 못했습니다.")
        try:
            execution = await self._execute_preview(pending_action, preview_id)
        except SpringBackendError as exc:
            return self._error(str(exc))

        if execution.status == "ACCEPTED":
            action = self._action_label(pending_action)
            if pending_action in {Intent.BUY.value, Intent.SELL.value}:
                message = (
                    "모의 주문을 접수했습니다."
                    if execution.broker_mode == "MOCK"
                    else "주문을 접수했습니다."
                )
            else:
                message = (
                    f"모의 {action} 요청을 접수했습니다."
                    if execution.broker_mode == "MOCK"
                    else f"{action} 요청을 접수했습니다."
                )
            status = AgentStatus.COMPLETED
        elif execution.status == "REJECTED":
            message = "요청이 거절되었습니다. 주문 상태와 요청 조건을 확인해주세요."
            status = AgentStatus.ERROR
        else:
            message = (
                "처리 결과를 확실히 확인하지 못했습니다. 같은 요청을 반복하지 말고 "
                "주문 상태를 확인해주세요."
            )
            status = AgentStatus.ERROR
        return {
            "status": status.value,
            "message": message,
            "execution": execution.model_dump(mode="json", by_alias=True),
        }

    async def _execute_preview(self, pending_action: str, preview_id: str) -> ExecutionResponse:
        if pending_action in {Intent.BUY.value, Intent.SELL.value}:
            await self._spring.approve_order_preview(preview_id)
            return await self._spring.execute_order_preview(preview_id)
        if pending_action == Intent.ORDER_CANCEL.value:
            await self._spring.approve_order_cancellation_preview(preview_id)
            return await self._spring.execute_order_cancellation_preview(preview_id)
        if pending_action == Intent.ORDER_MODIFY.value:
            await self._spring.approve_order_modification_preview(preview_id)
            return await self._spring.execute_order_modification_preview(preview_id)
        if pending_action == Intent.SINGLE_CONDITIONAL_ORDER.value:
            await self._spring.approve_single_conditional_order_preview(preview_id)
            return await self._spring.execute_single_conditional_order_preview(preview_id)
        if pending_action == Intent.CONDITIONAL_ORDER_CANCEL.value:
            await self._spring.approve_conditional_order_cancellation_preview(preview_id)
            return await self._spring.execute_conditional_order_cancellation_preview(preview_id)
        if pending_action == Intent.OCO_CONDITIONAL_ORDER.value:
            await self._spring.approve_oco_conditional_order_preview(preview_id)
            return await self._spring.execute_oco_conditional_order_preview(preview_id)
        if pending_action == Intent.OTO_CONDITIONAL_ORDER.value:
            await self._spring.approve_oto_conditional_order_preview(preview_id)
            return await self._spring.execute_oto_conditional_order_preview(preview_id)
        if pending_action == Intent.CONDITIONAL_ORDER_MODIFY.value:
            await self._spring.approve_conditional_order_modification_preview(preview_id)
            return await self._spring.execute_conditional_order_modification_preview(preview_id)
        raise SpringBackendError("지원하지 않는 금융 요청입니다.")

    async def cancel(self, state: AgentState) -> AgentState:
        del state
        return {
            "status": AgentStatus.CANCELLED.value,
            "message": "요청을 중단했습니다. 실제 변경은 전송하지 않았습니다.",
        }

    async def unsupported(self, state: AgentState) -> AgentState:
        del state
        return {
            "status": AgentStatus.NEEDS_INPUT.value,
            "message": (
                "명령을 이해하지 못했습니다. 현재가, 보유자산, 미체결 주문, 매수, 매도, "
                "주문 취소·정정 또는 단일 조건 주문으로 다시 말씀해주세요."
            ),
        }

    @staticmethod
    def _action_label(pending_action: str) -> str:
        return {
            Intent.BUY.value: "매수",
            Intent.SELL.value: "매도",
            Intent.ORDER_CANCEL.value: "주문 취소",
            Intent.ORDER_MODIFY.value: "주문 정정",
            Intent.SINGLE_CONDITIONAL_ORDER.value: "조건 주문 생성",
            Intent.OCO_CONDITIONAL_ORDER.value: "OCO 조건 주문 생성",
            Intent.OTO_CONDITIONAL_ORDER.value: "OTO 조건 주문 생성",
            Intent.CONDITIONAL_ORDER_CANCEL.value: "조건 주문 취소",
            Intent.CONDITIONAL_ORDER_MODIFY.value: "조건 주문 정정",
        }.get(pending_action, "금융")

    @staticmethod
    def _parsed(state: AgentState) -> ParsedIntent:
        data = state.get("parsed_intent")
        if data is None:
            raise AgentInputError("명령 해석 결과를 찾지 못했습니다.")
        return ParsedIntent.model_validate(data)

    def _instrument(self, parsed: ParsedIntent, source_text: str) -> Instrument:
        return self._catalog.resolve(
            stock_name=parsed.stock_name,
            symbol=parsed.symbol,
            source_text=source_text,
        )

    async def _account_seq(self) -> int:
        accounts = await self._spring.list_accounts()
        if not accounts:
            raise AgentInputError("사용할 수 있는 증권 계좌가 없습니다.")
        if len(accounts) > 1:
            raise AgentInputError("계좌가 여러 개입니다. 사용할 계좌를 명확히 말씀해주세요.")
        return accounts[0].account_seq

    @staticmethod
    def _error(message: str, *, needs_input: bool = False) -> AgentState:
        return {
            "status": AgentStatus.NEEDS_INPUT.value if needs_input else AgentStatus.ERROR.value,
            "message": message,
        }


def build_agent_graph(
    *,
    interpreter: CommandInterpreter,
    spring: SpringGateway,
    checkpointer: Any,
    catalog: InstrumentCatalog | None = None,
) -> CompiledStateGraph[AgentState, None, AgentState, AgentState]:
    """Compile the workflow with a caller-selected checkpoint implementation."""

    nodes = AgentGraphNodes(
        interpreter=interpreter,
        spring=spring,
        catalog=catalog or InstrumentCatalog(),
    )
    graph: StateGraph[AgentState, None, AgentState, AgentState] = StateGraph(AgentState)
    graph.add_node("interpret", nodes.interpret)
    graph.add_node("price", nodes.price)
    graph.add_node("holdings", nodes.holdings)
    graph.add_node("orders", nodes.orders)
    graph.add_node("conditional_orders", nodes.conditional_orders)
    graph.add_node("create_mutation_preview", nodes.create_mutation_preview)
    graph.add_node("await_confirmation", nodes.await_confirmation)
    graph.add_node("execute", nodes.execute)
    graph.add_node("cancel", nodes.cancel)
    graph.add_node("unsupported", nodes.unsupported)

    graph.add_edge(START, "interpret")
    graph.add_conditional_edges(
        "interpret",
        _route_intent,
        {
            "price": "price",
            "holdings": "holdings",
            "orders": "orders",
            "conditional_orders": "conditional_orders",
            "mutation": "create_mutation_preview",
            "unsupported": "unsupported",
            "end": END,
        },
    )
    graph.add_conditional_edges(
        "create_mutation_preview",
        _route_preview,
        {"confirm": "await_confirmation", "end": END},
    )
    graph.add_conditional_edges(
        "await_confirmation",
        _route_confirmation,
        {"execute": "execute", "cancel": "cancel"},
    )
    for node in (
        "price",
        "holdings",
        "orders",
        "conditional_orders",
        "execute",
        "cancel",
        "unsupported",
    ):
        graph.add_edge(node, END)
    return graph.compile(checkpointer=checkpointer)


def _route_intent(
    state: AgentState,
) -> Literal["price", "holdings", "orders", "conditional_orders", "mutation", "unsupported", "end"]:
    if state.get("status") in {AgentStatus.ERROR.value, AgentStatus.NEEDS_INPUT.value}:
        return "end"
    parsed_data = state.get("parsed_intent")
    if parsed_data is None:
        return "end"
    intent = ParsedIntent.model_validate(parsed_data).intent
    if intent is Intent.PRICE_QUERY:
        return "price"
    if intent is Intent.HOLDINGS_QUERY:
        return "holdings"
    if intent is Intent.ORDER_LIST:
        return "orders"
    if intent is Intent.CONDITIONAL_ORDER_LIST:
        return "conditional_orders"
    if intent in {
        Intent.BUY,
        Intent.SELL,
        Intent.ORDER_CANCEL,
        Intent.ORDER_MODIFY,
        Intent.SINGLE_CONDITIONAL_ORDER,
        Intent.OCO_CONDITIONAL_ORDER,
        Intent.OTO_CONDITIONAL_ORDER,
        Intent.CONDITIONAL_ORDER_CANCEL,
        Intent.CONDITIONAL_ORDER_MODIFY,
    }:
        return "mutation"
    return "unsupported"


def _route_preview(state: AgentState) -> Literal["confirm", "end"]:
    return "confirm" if state.get("status") == AgentStatus.WAITING_CONFIRMATION.value else "end"


def _route_confirmation(state: AgentState) -> Literal["execute", "cancel"]:
    return "execute" if state.get("confirmation") == "APPROVE" else "cancel"

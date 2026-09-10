package com.jusika.backend.conditionalordermodification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationSubmissionRequest;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewRequest.Condition;
import com.jusika.backend.orderexecution.OrderExecutionConflictException;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderExecutionSubmissionException;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/** 실제 계좌를 건드리지 않고 조건 주문 정정의 유형 전환과 안전 실행을 검사합니다. */
class ConditionalOrderModificationServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final String ORIGINAL_ID = "original-conditional-id";
	private static final OffsetDateTime NOW = OffsetDateTime.of(
			2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

	private RecordingConditionalOrderClient conditionalOrderClient;
	private FixedPriceClient priceClient;
	private FixedBuyingPowerClient buyingPowerClient;
	private FixedSellableQuantityClient sellableQuantityClient;
	private FixedCommissionsClient commissionsClient;
	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingGateway gateway;
	private ConditionalOrderModificationService service;

	/** 각 테스트가 사용할 고정 시계와 메모리 저장·조회 경계를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_조건_주문_정정_서비스를_준비한다() {
		conditionalOrderClient = new RecordingConditionalOrderClient();
		priceClient = new FixedPriceClient();
		buyingPowerClient = new FixedBuyingPowerClient();
		sellableQuantityClient = new FixedSellableQuantityClient();
		commissionsClient = new FixedCommissionsClient();
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		gateway = new RecordingGateway();
		Clock clock = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
		service = new ConditionalOrderModificationService(
				conditionalOrderClient, priceClient, buyingPowerClient,
				sellableQuantityClient, commissionsClient, previewStore,
				executionStore, gateway, new OrderPreviewProperties(Duration.ofMinutes(2)), clock);
	}

	/** SINGLE 원주문을 OCO 전체 구성으로 전환하는 미리보기를 만드는지 검사합니다. */
	@Test
	@DisplayName("SINGLE 원주문을 OCO 전체 구성으로 정정 미리보기한다")
	void SINGLE_원주문을_OCO_전체_구성으로_정정_미리보기한다() {
		conditionalOrderClient.response = 원조건_주문을_만든다(ConditionalOrderType.SINGLE);

		ConditionalOrderModificationPreviewResponse preview = service.createPreview(
				OCO_정정_요청을_만든다());

		assertThat(preview.originalType()).isEqualTo(ConditionalOrderType.SINGLE);
		assertThat(preview.requestedType()).isEqualTo(ConditionalOrderType.OCO);
		assertThat(preview.requestedSecond()).isNotNull();
		assertThat(preview.status())
				.isEqualTo(ConditionalOrderModificationPreviewStatus.PENDING_APPROVAL);
		assertThat(gateway.callCount).isZero();
	}

	/** OCO 원주문을 매수 후 매도하는 OTO 전체 구성으로 전환하는지 검사합니다. */
	@Test
	@DisplayName("OCO 원주문을 OTO 전체 구성으로 정정 미리보기한다")
	void OCO_원주문을_OTO_전체_구성으로_정정_미리보기한다() {
		conditionalOrderClient.response = 원조건_주문을_만든다(ConditionalOrderType.OCO);

		ConditionalOrderModificationPreviewResponse preview = service.createPreview(
				new ConditionalOrderModificationPreviewRequest(
						ACCOUNT_SEQ, ORIGINAL_ID, ConditionalOrderType.OTO, BigDecimal.TEN,
						OrderType.LIMIT, LocalDate.of(2026, 9, 10),
						new Condition(OrderSide.BUY, new BigDecimal("195"), new BigDecimal("195")),
						new Condition(OrderSide.SELL, new BigDecimal("220"), new BigDecimal("220"))));

		assertThat(preview.requestedType()).isEqualTo(ConditionalOrderType.OTO);
		assertThat(preview.requestedFirst().side()).isEqualTo(OrderSide.BUY);
		assertThat(gateway.callCount).isZero();
	}

	/** OTO 원주문을 소수 수량의 미국 SINGLE 시장가 매도로 전환하는지 검사합니다. */
	@Test
	@DisplayName("OTO 원주문을 SINGLE 시장가 조건으로 정정 미리보기한다")
	void OTO_원주문을_SINGLE_시장가_조건으로_정정_미리보기한다() {
		conditionalOrderClient.response = 원조건_주문을_만든다(ConditionalOrderType.OTO);

		ConditionalOrderModificationPreviewResponse preview = service.createPreview(
				new ConditionalOrderModificationPreviewRequest(
						ACCOUNT_SEQ, ORIGINAL_ID, ConditionalOrderType.SINGLE,
						new BigDecimal("1.25"), OrderType.MARKET, LocalDate.of(2026, 9, 10),
						new Condition(OrderSide.SELL, new BigDecimal("220"), null), null));

		assertThat(preview.requestedType()).isEqualTo(ConditionalOrderType.SINGLE);
		assertThat(preview.requestedSecond()).isNull();
	}

	/** 방향이 유형으로 고정된 OCO에서 원주문과 같은 불필요한 정정을 차단하는지 검사합니다. */
	@Test
	@DisplayName("원주문과 같은 OCO 전체 구성은 정정하지 않는다")
	void 원주문과_같은_OCO_전체_구성은_정정하지_않는다() {
		conditionalOrderClient.response = 원조건_주문을_만든다(ConditionalOrderType.OCO);
		ConditionalOrderModificationPreviewRequest unchanged =
				new ConditionalOrderModificationPreviewRequest(
						ACCOUNT_SEQ, ORIGINAL_ID, ConditionalOrderType.OCO, BigDecimal.TEN,
						OrderType.LIMIT, LocalDate.of(2026, 9, 9),
						new Condition(OrderSide.SELL, new BigDecimal("210"), new BigDecimal("209")),
						new Condition(OrderSide.SELL, new BigDecimal("190"), new BigDecimal("189")));

		assertThatThrownBy(() -> service.createPreview(unchanged))
				.hasMessageContaining("원조건 주문과 정정 후 전체 구성이 같습니다");
		assertThat(previewStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 실행 직전 원주문과 새 조건을 재검증하고 새 조건 주문 식별값을 저장하는지 검사합니다. */
	@Test
	@DisplayName("승인된 조건 주문 정정을 모의 실행하고 새 조건 주문 식별값을 저장한다")
	void 승인된_조건_주문_정정을_모의_실행하고_새_조건_주문_식별값을_저장한다() {
		conditionalOrderClient.response = 원조건_주문을_만든다(ConditionalOrderType.SINGLE);
		ConditionalOrderModificationPreviewResponse preview = 승인된_OCO_미리보기를_저장한다();

		ConditionalOrderModificationExecutionResponse result =
				service.executeApprovedPreview(preview.previewId());

		assertThat(result.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(result.replacementConditionalOrderId()).isEqualTo("replacement-id");
		assertThat(gateway.callCount).isEqualTo(1);
		assertThat(gateway.request.type()).isEqualTo(ConditionalOrderType.OCO);
	}

	/** 승인 뒤 원주문 상태가 바뀌면 실행권과 정정 호출을 만들지 않는지 검사합니다. */
	@Test
	@DisplayName("승인 뒤 원조건 주문이 바뀌면 정정 실행을 차단한다")
	void 승인_뒤_원조건_주문이_바뀌면_정정_실행을_차단한다() {
		ConditionalOrderDetailResponse original = 원조건_주문을_만든다(ConditionalOrderType.SINGLE);
		conditionalOrderClient.response = original;
		ConditionalOrderModificationPreviewResponse preview = 승인된_OCO_미리보기를_저장한다();
		conditionalOrderClient.response = 상태를_바꾼다(original, ConditionalOrderStatus.PAUSED);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessageContaining("원조건 주문 내용이나 상태가 변경");
		assertThat(executionStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** LIVE 가용성 검사가 실패하면 원주문·금융 조회나 미리보기·실행 상태를 변경하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전정책 차단은 조건 주문 정정 재조회와 미리보기 소비 전에 적용된다")
	void LIVE_안전정책_차단은_조건_주문_정정_재조회와_미리보기_소비_전에_적용된다() {
		conditionalOrderClient.response = 원조건_주문을_만든다(ConditionalOrderType.SINGLE);
		ConditionalOrderModificationPreviewResponse preview = 승인된_OCO_미리보기를_저장한다();
		gateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE 조건 주문 정정 차단");
		int previewOrderCalls = conditionalOrderClient.callCount;
		int previewPriceCalls = priceClient.callCount;
		int previewBuyingPowerCalls = buyingPowerClient.callCount;
		int previewSellableQuantityCalls = sellableQuantityClient.callCount;
		int previewCommissionCalls = commissionsClient.callCount;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE 조건 주문 정정 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(ConditionalOrderModificationPreviewStatus.APPROVED);
		assertThat(executionStore.values).isEmpty();
		assertThat(conditionalOrderClient.callCount).isEqualTo(previewOrderCalls);
		assertThat(priceClient.callCount).isEqualTo(previewPriceCalls);
		assertThat(buyingPowerClient.callCount).isEqualTo(previewBuyingPowerCalls);
		assertThat(sellableQuantityClient.callCount).isEqualTo(previewSellableQuantityCalls);
		assertThat(commissionsClient.callCount).isEqualTo(previewCommissionCalls);
		assertThat(gateway.callCount).isZero();
	}

	/** 제출 결과 불명은 UNKNOWN으로 저장하고 정정 경계를 한 번만 호출하는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 정정 결과 불명은 UNKNOWN으로 저장하고 자동 재시도하지 않는다")
	void 조건_주문_정정_결과_불명은_UNKNOWN으로_저장하고_자동_재시도하지_않는다() {
		conditionalOrderClient.response = 원조건_주문을_만든다(ConditionalOrderType.SINGLE);
		ConditionalOrderModificationPreviewResponse preview = 승인된_OCO_미리보기를_저장한다();
		gateway.unknown = true;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessageContaining("자동으로 다시 정정하지 마세요");
		assertThat(gateway.callCount).isEqualTo(1);
		assertThat(executionStore.values.values().iterator().next().status())
				.isEqualTo(OrderExecutionStatus.UNKNOWN);
	}

	/** 테스트에 사용할 OCO 정정 후 전체 구성을 만듭니다. */
	private ConditionalOrderModificationPreviewRequest OCO_정정_요청을_만든다() {
		return new ConditionalOrderModificationPreviewRequest(
				ACCOUNT_SEQ, ORIGINAL_ID, ConditionalOrderType.OCO, BigDecimal.TEN,
				OrderType.LIMIT, LocalDate.of(2026, 9, 10),
				new Condition(OrderSide.SELL, new BigDecimal("210"), new BigDecimal("209")),
				new Condition(OrderSide.SELL, new BigDecimal("190"), new BigDecimal("189")));
	}

	/** 지정한 유형과 발동 전 상태를 가진 원조건 주문 상세를 만듭니다. */
	private ConditionalOrderDetailResponse 원조건_주문을_만든다(ConditionalOrderType type) {
		ConditionalOrderDetailResponse.Condition first = new ConditionalOrderDetailResponse.Condition(
				ConditionalOrderConditionType.STOP, ConditionalOrderConditionStatus.WATCHING,
				new BigDecimal("210"), null, new BigDecimal("209"), null);
		ConditionalOrderDetailResponse.Condition second = type == ConditionalOrderType.SINGLE ? null
				: new ConditionalOrderDetailResponse.Condition(
						ConditionalOrderConditionType.STOP, ConditionalOrderConditionStatus.HOLDING,
						new BigDecimal("190"), null, new BigDecimal("189"), null);
		return new ConditionalOrderDetailResponse(
				ACCOUNT_SEQ, ORIGINAL_ID, type, ConditionalOrderStatus.WATCHING, "AAPL",
				ConditionalOrderMarket.US, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.of(2026, 9, 9), first, second, NOW.minusMinutes(5));
	}

	/** 원주문 상태만 바꿔 실행 직전 충돌 상황을 만듭니다. */
	private ConditionalOrderDetailResponse 상태를_바꾼다(
			ConditionalOrderDetailResponse value, ConditionalOrderStatus status) {
		return new ConditionalOrderDetailResponse(
				value.accountSeq(), value.conditionalOrderId(), value.type(), status, value.symbol(),
				value.market(), value.quantity(), value.orderType(), value.expireDate(), value.first(),
				value.second(), value.createdAt());
	}

	/** 실제 미리보기 생성과 승인 단계를 거쳐 승인된 OCO 전환 사본을 저장합니다. */
	private ConditionalOrderModificationPreviewResponse 승인된_OCO_미리보기를_저장한다() {
		ConditionalOrderModificationPreviewResponse pending = service.createPreview(OCO_정정_요청을_만든다());
		return service.approvePreview(pending.previewId());
	}

	/** 한 건의 조건 주문 상세 조회 응답을 반환합니다. */
	private static final class RecordingConditionalOrderClient extends TossConditionalOrderClient {
		private ConditionalOrderDetailResponse response;
		private int callCount;

		/** 실제 REST 의존 객체 없이 기록용 조건 주문 클라이언트를 초기화합니다. */
		private RecordingConditionalOrderClient() { super(null, null); }

		/** 준비된 조건 주문 상세를 반환합니다. */
		@Override
		public ConditionalOrderDetailResponse getConditionalOrder(long accountSeq, String id) {
			callCount++;
			return response;
		}
	}

	/** AAPL의 고정된 미국 달러 현재가를 반환합니다. */
	private static final class FixedPriceClient extends TossPriceClient {
		private int callCount;

		/** 실제 REST 의존 객체 없이 현재가 클라이언트를 초기화합니다. */
		private FixedPriceClient() { super(null, null); }

		/** 테스트에 사용할 고정 현재가를 반환합니다. */
		@Override
		public StockPriceResponse getCurrentPrice(String symbol) {
			callCount++;
			return new StockPriceResponse("AAPL", new BigDecimal("200"), "USD", NOW);
		}
	}

	/** 충분한 달러 매수 가능 금액을 반환합니다. */
	private static final class FixedBuyingPowerClient extends TossBuyingPowerClient {
		private int callCount;

		/** 실제 REST 의존 객체 없이 매수 가능 금액 클라이언트를 초기화합니다. */
		private FixedBuyingPowerClient() { super(null, null); }

		/** 테스트에 충분한 매수 가능 금액을 반환합니다. */
		@Override
		public BuyingPowerResponse getBuyingPower(long accountSeq, String currency) {
			callCount++;
			return new BuyingPowerResponse(accountSeq, currency, new BigDecimal("100000"));
		}
	}

	/** 충분한 AAPL 매도 가능 수량을 반환합니다. */
	private static final class FixedSellableQuantityClient extends TossSellableQuantityClient {
		private int callCount;

		/** 실제 REST 의존 객체 없이 매도 가능 수량 클라이언트를 초기화합니다. */
		private FixedSellableQuantityClient() { super(null, null); }

		/** 테스트에 충분한 매도 가능 수량을 반환합니다. */
		@Override
		public SellableQuantityResponse getSellableQuantity(long accountSeq, String symbol) {
			callCount++;
			return new SellableQuantityResponse(accountSeq, symbol, new BigDecimal("100"));
		}
	}

	/** 미국 시장의 고정 수수료율을 반환합니다. */
	private static final class FixedCommissionsClient extends TossCommissionsClient {
		private int callCount;

		/** 실제 REST 의존 객체 없이 수수료 클라이언트를 초기화합니다. */
		private FixedCommissionsClient() { super(null, null); }

		/** 테스트에 사용할 미국 시장 수수료율을 반환합니다. */
		@Override
		public CommissionsResponse getCommissions(long accountSeq) {
			callCount++;
			return new CommissionsResponse(accountSeq, List.of(
					new CommissionItem("US", new BigDecimal("0.001"), null, null)));
		}
	}

	/** 미리보기와 승인 상태를 메모리에서 원자적으로 흉내 냅니다. */
	private static final class MemoryPreviewStore implements ConditionalOrderModificationPreviewStore {
		private final Map<String, ConditionalOrderModificationPreviewResponse> values = new HashMap<>();

		/** 새 미리보기를 메모리에 저장합니다. */
		@Override
		public ConditionalOrderModificationPreviewResponse save(
				ConditionalOrderModificationPreviewResponse value) {
			values.put(value.previewId(), value);
			return value;
		}

		/** 승인 대기 미리보기를 승인 상태로 바꿉니다. */
		@Override
		public boolean approvePending(String id, OffsetDateTime at) {
			ConditionalOrderModificationPreviewResponse value = values.get(id);
			if (value == null
					|| value.status() != ConditionalOrderModificationPreviewStatus.PENDING_APPROVAL
					|| !value.expiresAt().isAfter(at)) return false;
			values.put(id, 상태를_바꾼다(
					value, ConditionalOrderModificationPreviewStatus.APPROVED, at));
			return true;
		}

		/** 테스트 고정 시각에서는 아직 만료되지 않았으므로 상태를 바꾸지 않습니다. */
		@Override
		public boolean expirePending(String id, OffsetDateTime at) { return false; }

		/** 승인된 미리보기를 사용 완료 상태로 바꿉니다. */
		@Override
		public boolean consumeApproved(String id, OffsetDateTime at) {
			ConditionalOrderModificationPreviewResponse value = values.get(id);
			if (value == null
					|| value.status() != ConditionalOrderModificationPreviewStatus.APPROVED) return false;
			values.put(id, 상태를_바꾼다(
					value, ConditionalOrderModificationPreviewStatus.CONSUMED, value.approvedAt()));
			return true;
		}

		/** 식별값으로 메모리 미리보기를 조회합니다. */
		@Override
		public Optional<ConditionalOrderModificationPreviewResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 기존 미리보기 값은 유지하고 승인 상태와 시각만 바꿉니다. */
		private ConditionalOrderModificationPreviewResponse 상태를_바꾼다(
				ConditionalOrderModificationPreviewResponse value,
				ConditionalOrderModificationPreviewStatus status,
				OffsetDateTime approvedAt) {
			return new ConditionalOrderModificationPreviewResponse(
					value.previewId(), value.createdAt(), value.expiresAt(), value.accountSeq(),
					value.originalConditionalOrderId(), value.originalType(), value.originalStatus(),
					value.symbol(), value.market(), value.originalQuantity(), value.originalOrderType(),
					value.originalExpireDate(), value.originalFirst(), value.originalSecond(),
					value.originalCreatedAt(), value.requestedType(), value.requestedQuantity(),
					value.requestedOrderType(), value.requestedExpireDate(), value.requestedFirst(),
					value.requestedSecond(), value.referencePrice(), value.currency(),
					value.requiresHighValueConfirmation(), status, approvedAt);
		}
	}

	/** 정정 실행의 선점과 상태 전이를 메모리에서 재현합니다. */
	private static final class MemoryExecutionStore implements ConditionalOrderModificationExecutionStore {
		private final Map<String, ConditionalOrderModificationExecutionResponse> values = new HashMap<>();
		private final Map<String, String> targets = new HashMap<>();

		/** 원조건 주문별 첫 실행만 선점합니다. */
		@Override
		public boolean claim(ConditionalOrderModificationExecutionResponse value) {
			String target = value.accountSeq() + ":" + value.originalConditionalOrderId();
			if (targets.putIfAbsent(target, value.executionId()) != null) return false;
			values.put(value.executionId(), value);
			return true;
		}

		/** 준비된 실행을 제출 중으로 바꿉니다. */
		@Override
		public boolean markSubmitting(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.SUBMITTING, null, null, at, null);
		}

		/** 준비 실패를 내부 오류 거절로 바꿉니다. */
		@Override
		public boolean markPreparationFailed(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.INTERNAL_STATE, null, null, at);
		}

		/** 성공과 새 조건 주문 식별값을 저장합니다. */
		@Override
		public boolean markAccepted(String id, String replacementId, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.ACCEPTED, null, replacementId, null, at);
		}

		/** 증권사 거절 상태를 저장합니다. */
		@Override
		public boolean markRejected(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.BROKER_REJECTED, null, null, at);
		}

		/** 결과 불명 상태를 완료 시각 없이 저장합니다. */
		@Override
		public boolean markUnknown(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.UNKNOWN,
					OrderExecutionFailureType.SUBMISSION_UNKNOWN, null, null, null);
		}

		/** 실행 식별값으로 메모리 결과를 조회합니다. */
		@Override
		public Optional<ConditionalOrderModificationExecutionResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 계좌와 원조건 주문으로 메모리 결과를 조회합니다. */
		@Override
		public Optional<ConditionalOrderModificationExecutionResponse> findByTarget(
				long accountSeq, String id) {
			String executionId = targets.get(accountSeq + ":" + id);
			return executionId == null ? Optional.empty() : findById(executionId);
		}

		/** 저장된 실행의 상태와 결과 필드만 변경합니다. */
		private boolean 변경한다(
				String id,
				OrderExecutionStatus status,
				OrderExecutionFailureType failure,
				String replacementId,
				OffsetDateTime submittedAt,
				OffsetDateTime completedAt) {
			ConditionalOrderModificationExecutionResponse value = values.get(id);
			if (value == null) return false;
			values.put(id, new ConditionalOrderModificationExecutionResponse(
					value.executionId(), value.previewId(), value.accountSeq(),
					value.originalConditionalOrderId(), replacementId, value.brokerMode(), status,
					failure, value.createdAt(), NOW,
					submittedAt == null ? value.submittedAt() : submittedAt, completedAt));
			return true;
		}
	}

	/** 정정 호출을 기록하고 정상 또는 결과 불명 응답을 재현합니다. */
	private static final class RecordingGateway implements ConditionalOrderModificationGateway {
		private int callCount;
		private boolean unknown;
		private ConditionalOrderModificationSubmissionRequest request;
		private RuntimeException availabilityFailure;

		/** 준비한 LIVE 안전 차단을 재현하거나 MOCK 조건 주문 정정 사용 가능 상태를 유지합니다. */
		@Override
		public void requireModificationAvailable() {
			if (availabilityFailure != null) {
				throw availabilityFailure;
			}
		}

		/** 정정 호출을 기록하고 새 식별값 또는 결과 불명 오류를 반환합니다. */
		@Override
		public ConditionalOrderModificationResponse modify(
				long accountSeq,
				String originalId,
				ConditionalOrderModificationSubmissionRequest request) {
			callCount++;
			this.request = request;
			if (unknown) throw new OrderSubmissionException("결과 불명", true);
			return new ConditionalOrderModificationResponse("replacement-id");
		}

		/** 테스트 실행 모드가 MOCK임을 반환합니다. */
		@Override
		public String mode() { return "MOCK"; }
	}
}

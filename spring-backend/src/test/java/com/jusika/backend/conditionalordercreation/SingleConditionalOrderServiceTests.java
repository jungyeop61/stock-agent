package com.jusika.backend.conditionalordercreation;

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
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalorder.SingleConditionalOrderSubmissionRequest;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderExecutionSubmissionException;
import com.jusika.backend.orderexecution.OrderExecutionValidationException;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewException;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderPreviewStateException;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 실제 증권사를 호출하지 않고 단일 조건 주문의 계산·승인·최종 검증·모의 실행을 검사합니다.
 */
class SingleConditionalOrderServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final OffsetDateTime NOW = OffsetDateTime.of(
			2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

	private RecordingPriceClient priceClient;
	private RecordingBuyingPowerClient buyingPowerClient;
	private RecordingSellableQuantityClient sellableQuantityClient;
	private RecordingCommissionsClient commissionsClient;
	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingGateway gateway;
	private SingleConditionalOrderService service;

	/** 각 테스트에서 사용할 고정 시계와 메모리 가짜 객체를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_단일_조건_주문_서비스를_준비한다() {
		priceClient = new RecordingPriceClient();
		buyingPowerClient = new RecordingBuyingPowerClient();
		sellableQuantityClient = new RecordingSellableQuantityClient();
		commissionsClient = new RecordingCommissionsClient();
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		gateway = new RecordingGateway();
		Clock clock = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
		service = new SingleConditionalOrderService(
				priceClient, buyingPowerClient, sellableQuantityClient, commissionsClient,
				previewStore, executionStore, gateway,
				new OrderPreviewProperties(Duration.ofMinutes(2)), clock);
		국내_기본_조회_응답을_준비한다();
	}

	/** 국내 지정가 매수의 주문금액·수수료·고액 여부를 계산해 저장하는지 검사합니다. */
	@Test
	@DisplayName("국내 지정가 매수 단일 조건 주문 미리보기를 만든다")
	void 국내_지정가_매수_단일_조건_주문_미리보기를_만든다() {
		SingleConditionalOrderPreviewResponse preview = service.createPreview(
				국내_지정가_매수_요청을_만든다());

		assertThat(preview.conditionalOrderType()).isEqualTo(ConditionalOrderType.SINGLE);
		assertThat(preview.symbol()).isEqualTo("005930");
		assertThat(preview.referencePrice()).isEqualByComparingTo("70000");
		assertThat(preview.calculationPrice()).isEqualByComparingTo("71000");
		assertThat(preview.estimatedOrderAmount()).isEqualByComparingTo("710000");
		assertThat(preview.estimatedCommission()).isEqualByComparingTo("710");
		assertThat(preview.estimatedAmountAfterCommission()).isEqualByComparingTo("710710");
		assertThat(preview.requiresHighValueConfirmation()).isFalse();
		assertThat(preview.status()).isEqualTo(OrderPreviewStatus.PENDING_APPROVAL);
		assertThat(buyingPowerClient.callCount).isEqualTo(1);
		assertThat(sellableQuantityClient.callCount).isZero();
		assertThat(gateway.callCount).isZero();
	}

	/** 미국 시장가 매도의 소수 수량과 매도 가능 수량 검증을 허용하는지 검사합니다. */
	@Test
	@DisplayName("미국 시장가 매도 조건 주문의 소수 수량을 검증한다")
	void 미국_시장가_매도_조건_주문의_소수_수량을_검증한다() {
		priceClient.response = new StockPriceResponse(
				"AAPL", new BigDecimal("200"), "USD", NOW);
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ, List.of(new CommissionItem("US", new BigDecimal("0.002"), null, null)));
		sellableQuantityClient.response = new SellableQuantityResponse(
				ACCOUNT_SEQ, "AAPL", new BigDecimal("2"));

		SingleConditionalOrderPreviewResponse preview = service.createPreview(
				new SingleConditionalOrderPreviewRequest(
						ACCOUNT_SEQ, "aapl", OrderSide.SELL, OrderType.MARKET,
						new BigDecimal("1.5"), new BigDecimal("190"), null,
						LocalDate.parse("2026-09-10")));

		assertThat(preview.currency()).isEqualTo("USD");
		assertThat(preview.estimatedOrderAmount()).isEqualByComparingTo("300");
		assertThat(preview.sellTaxExcluded()).isTrue();
		assertThat(sellableQuantityClient.callCount).isEqualTo(1);
		assertThat(buyingPowerClient.callCount).isZero();
	}

	/** 지난 만료일과 국내 소수 수량을 실제 제출 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("잘못된 단일 조건 주문 날짜와 수량을 차단한다")
	void 잘못된_단일_조건_주문_날짜와_수량을_차단한다() {
		SingleConditionalOrderPreviewRequest expired = new SingleConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.TEN, new BigDecimal("72000"), new BigDecimal("71000"),
				LocalDate.parse("2026-09-06"));
		SingleConditionalOrderPreviewRequest fractional = new SingleConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				new BigDecimal("1.5"), new BigDecimal("72000"), new BigDecimal("71000"),
				LocalDate.parse("2026-09-10"));

		assertThatThrownBy(() -> service.createPreview(expired))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("조건 주문 만료일은 오늘 또는 이후 날짜여야 합니다.");
		assertThatThrownBy(() -> service.createPreview(fractional))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("소수점 수량");
		assertThat(previewStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 미리보기를 한 번 승인하고 같은 미리보기의 재승인을 차단하는지 검사합니다. */
	@Test
	@DisplayName("단일 조건 주문 미리보기를 한 번만 승인한다")
	void 단일_조건_주문_미리보기를_한_번만_승인한다() {
		SingleConditionalOrderPreviewResponse preview = service.createPreview(
				국내_지정가_매수_요청을_만든다());

		SingleConditionalOrderPreviewResponse approved = service.approvePreview(preview.previewId());

		assertThat(approved.status()).isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(approved.approvedAt()).isEqualTo(NOW);
		assertThatThrownBy(() -> service.approvePreview(preview.previewId()))
				.isInstanceOf(OrderPreviewStateException.class)
				.hasMessage("이미 승인한 조건 주문 미리보기입니다.");
		assertThat(gateway.callCount).isZero();
	}

	/** 승인된 조건 주문을 재검증해 모의 식별값과 함께 접수 상태로 저장하는지 검사합니다. */
	@Test
	@DisplayName("승인된 단일 조건 주문을 모의 실행하고 중복 실행을 막는다")
	void 승인된_단일_조건_주문을_모의_실행하고_중복_실행을_막는다() {
		SingleConditionalOrderPreviewResponse preview = service.createPreview(
				국내_지정가_매수_요청을_만든다());
		service.approvePreview(preview.previewId());

		SingleConditionalOrderExecutionResponse result =
				service.executeApprovedPreview(preview.previewId());

		assertThat(result.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(result.conditionalOrderId()).isEqualTo("mock-conditional-id");
		assertThat(result.brokerMode()).isEqualTo("MOCK");
		assertThat(gateway.callCount).isEqualTo(1);
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderPreviewStateException.class);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** LIVE 가용성 검사가 실패하면 금융 조회나 미리보기·실행 상태를 변경하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전정책 차단은 SINGLE 조건 주문 재조회와 미리보기 소비 전에 적용된다")
	void LIVE_안전정책_차단은_SINGLE_조건_주문_재조회와_미리보기_소비_전에_적용된다() {
		SingleConditionalOrderPreviewResponse preview = service.createPreview(
				국내_지정가_매수_요청을_만든다());
		service.approvePreview(preview.previewId());
		gateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE SINGLE 조건 주문 차단");
		int previewPriceCalls = priceClient.callCount;
		int previewBuyingPowerCalls = buyingPowerClient.callCount;
		int previewCommissionCalls = commissionsClient.callCount;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE SINGLE 조건 주문 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(executionStore.values).isEmpty();
		assertThat(priceClient.callCount).isEqualTo(previewPriceCalls);
		assertThat(buyingPowerClient.callCount).isEqualTo(previewBuyingPowerCalls);
		assertThat(commissionsClient.callCount).isEqualTo(previewCommissionCalls);
		assertThat(gateway.callCount).isZero();
	}

	/** 시장가 상승으로 새로 고액 주문이 되면 승인 내용을 재확인하게 하는지 검사합니다. */
	@Test
	@DisplayName("시장가 상승으로 1억원 이상이 되면 실행을 차단한다")
	void 시장가_상승으로_1억원_이상이_되면_실행을_차단한다() {
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("40000"), "KRW", NOW);
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "KRW", new BigDecimal("500000000"));
		SingleConditionalOrderPreviewResponse preview = service.createPreview(
				new SingleConditionalOrderPreviewRequest(
						ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.MARKET,
						new BigDecimal("2000"), new BigDecimal("45000"), null,
						LocalDate.parse("2026-09-10")));
		assertThat(preview.requiresHighValueConfirmation()).isFalse();
		service.approvePreview(preview.previewId());
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("60000"), "KRW", NOW.plusSeconds(10));

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionValidationException.class)
				.hasMessageContaining("새 미리보기");
		assertThat(executionStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 제출 결과를 알 수 없으면 UNKNOWN으로 저장하고 자동 재생성을 금지하는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 결과 불명은 UNKNOWN으로 저장하고 자동 재시도하지 않는다")
	void 조건_주문_결과_불명은_UNKNOWN으로_저장하고_자동_재시도하지_않는다() {
		SingleConditionalOrderPreviewResponse preview = service.createPreview(
				국내_지정가_매수_요청을_만든다());
		service.approvePreview(preview.previewId());
		gateway.unknown = true;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessageContaining("자동으로 다시 생성하지 마세요");
		SingleConditionalOrderExecutionResponse stored =
				executionStore.values.values().iterator().next();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 국내 지정가 매수 미리보기에 필요한 정상 조회 응답을 준비합니다. */
	private void 국내_기본_조회_응답을_준비한다() {
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("70000"), "KRW", NOW);
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "KRW", new BigDecimal("10000000"));
		sellableQuantityClient.response = new SellableQuantityResponse(
				ACCOUNT_SEQ, "005930", new BigDecimal("100"));
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ, List.of(new CommissionItem(
						"KR", new BigDecimal("0.001"), null, null)));
	}

	/** 반복 테스트에 사용할 국내 지정가 매수 요청을 만듭니다. */
	private SingleConditionalOrderPreviewRequest 국내_지정가_매수_요청을_만든다() {
		return new SingleConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.TEN, new BigDecimal("72000"), new BigDecimal("71000"),
				LocalDate.parse("2026-09-10"));
	}

	/** 준비한 현재가를 반환하고 호출 횟수를 기록합니다. */
	private static final class RecordingPriceClient extends TossPriceClient {
		private StockPriceResponse response;
		private int callCount;

		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingPriceClient() {
			super(null, null);
		}

		/** 현재가 조회 횟수를 기록하고 준비된 응답을 반환합니다. */
		@Override
		public StockPriceResponse getCurrentPrice(String symbol) {
			callCount++;
			return response;
		}
	}

	/** 준비한 매수 가능 금액을 반환하고 호출 횟수를 기록합니다. */
	private static final class RecordingBuyingPowerClient extends TossBuyingPowerClient {
		private BuyingPowerResponse response;
		private int callCount;

		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingBuyingPowerClient() {
			super(null, null);
		}

		/** 매수 가능 금액 조회 횟수를 기록하고 준비된 응답을 반환합니다. */
		@Override
		public BuyingPowerResponse getBuyingPower(long accountSeq, String currency) {
			callCount++;
			return response;
		}
	}

	/** 준비한 매도 가능 수량을 반환하고 호출 횟수를 기록합니다. */
	private static final class RecordingSellableQuantityClient extends TossSellableQuantityClient {
		private SellableQuantityResponse response;
		private int callCount;

		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingSellableQuantityClient() {
			super(null, null);
		}

		/** 매도 가능 수량 조회 횟수를 기록하고 준비된 응답을 반환합니다. */
		@Override
		public SellableQuantityResponse getSellableQuantity(long accountSeq, String symbol) {
			callCount++;
			return response;
		}
	}

	/** 준비한 수수료 목록을 반환하고 호출 횟수를 기록합니다. */
	private static final class RecordingCommissionsClient extends TossCommissionsClient {
		private CommissionsResponse response;
		private int callCount;

		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingCommissionsClient() {
			super(null, null);
		}

		/** 수수료 조회 횟수를 기록하고 준비된 응답을 반환합니다. */
		@Override
		public CommissionsResponse getCommissions(long accountSeq) {
			callCount++;
			return response;
		}
	}

	/** 단일 조건 주문 미리보기의 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryPreviewStore implements SingleConditionalOrderPreviewStore {
		private final Map<String, SingleConditionalOrderPreviewResponse> values = new HashMap<>();

		/** 새 미리보기를 메모리에 저장합니다. */
		@Override
		public SingleConditionalOrderPreviewResponse save(
				SingleConditionalOrderPreviewResponse preview) {
			values.put(preview.previewId(), preview);
			return preview;
		}

		/** 유효한 승인 대기 미리보기만 승인 상태로 변경합니다. */
		@Override
		public boolean approvePending(String id, OffsetDateTime at) {
			SingleConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| !value.expiresAt().isAfter(at)) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.APPROVED, at));
			return true;
		}

		/** 유효시간이 지난 승인 대기 미리보기만 만료 상태로 변경합니다. */
		@Override
		public boolean expirePending(String id, OffsetDateTime now) {
			SingleConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| value.expiresAt().isAfter(now)) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.EXPIRED, null));
			return true;
		}

		/** 승인된 미리보기만 사용 상태로 변경합니다. */
		@Override
		public boolean consumeApproved(String id, OffsetDateTime at) {
			SingleConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.APPROVED) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.CONSUMED, value.approvedAt()));
			return true;
		}

		/** 식별값으로 저장된 미리보기를 조회합니다. */
		@Override
		public Optional<SingleConditionalOrderPreviewResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 금융값을 유지하면서 미리보기 상태와 승인 시각만 변경합니다. */
		private SingleConditionalOrderPreviewResponse 상태를_바꾼다(
				SingleConditionalOrderPreviewResponse value,
				OrderPreviewStatus status,
				OffsetDateTime approvedAt) {
			return new SingleConditionalOrderPreviewResponse(
					value.previewId(), value.createdAt(), value.expiresAt(), value.accountSeq(),
					value.symbol(), value.conditionalOrderType(), value.side(), value.orderType(),
					value.quantity(), value.triggerPrice(), value.orderPrice(), value.expireDate(),
					value.referencePrice(), value.calculationPrice(), value.currency(),
					value.marketCountry(), value.commissionRate(), value.estimatedOrderAmount(),
					value.estimatedCommission(), value.estimatedAmountAfterCommission(),
					value.sellTaxExcluded(), value.requiresHighValueConfirmation(), status, approvedAt);
		}
	}

	/** 조건 주문 실행의 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryExecutionStore implements SingleConditionalOrderExecutionStore {
		private final Map<String, SingleConditionalOrderExecutionResponse> values = new HashMap<>();

		/** 미리보기당 첫 실행만 저장합니다. */
		@Override
		public boolean claim(SingleConditionalOrderExecutionResponse execution) {
			if (values.values().stream().anyMatch(
					value -> value.previewId().equals(execution.previewId()))) {
				return false;
			}
			values.put(execution.executionId(), execution);
			return true;
		}

		/** 준비 상태 실행을 제출 중으로 변경합니다. */
		@Override
		public boolean markSubmitting(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.SUBMITTING, null, null, at, null);
		}

		/** 실행을 내부 상태 오류로 종료합니다. */
		@Override
		public boolean markPreparationFailed(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.INTERNAL_STATE, null, null, at);
		}

		/** 실행을 조건 주문 식별값과 함께 접수 상태로 변경합니다. */
		@Override
		public boolean markAccepted(String id, String conditionalOrderId, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.ACCEPTED, null, conditionalOrderId, null, at);
		}

		/** 실행을 증권사 거절 상태로 변경합니다. */
		@Override
		public boolean markRejected(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.BROKER_REJECTED, null, null, at);
		}

		/** 실행을 결과 불명 상태로 변경합니다. */
		@Override
		public boolean markUnknown(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.UNKNOWN,
					OrderExecutionFailureType.SUBMISSION_UNKNOWN, null, null, null);
		}

		/** 실행 식별값으로 저장된 실행을 조회합니다. */
		@Override
		public Optional<SingleConditionalOrderExecutionResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 미리보기 식별값으로 저장된 실행을 조회합니다. */
		@Override
		public Optional<SingleConditionalOrderExecutionResponse> findByPreviewId(String id) {
			return values.values().stream().filter(value -> value.previewId().equals(id)).findFirst();
		}

		/** 저장된 식별값을 유지하면서 실행 상태와 결과값만 변경합니다. */
		private boolean 변경한다(
				String id,
				OrderExecutionStatus status,
				OrderExecutionFailureType failure,
				String conditionalOrderId,
				OffsetDateTime submitted,
				OffsetDateTime completed) {
			SingleConditionalOrderExecutionResponse value = values.get(id);
			if (value == null) {
				return false;
			}
			OffsetDateTime updated = completed != null
					? completed : submitted != null ? submitted : NOW;
			values.put(id, new SingleConditionalOrderExecutionResponse(
					value.executionId(), value.previewId(), value.clientOrderId(),
					conditionalOrderId, value.brokerMode(), status, failure,
					value.createdAt(), updated,
					submitted != null ? submitted : value.submittedAt(), completed));
			return true;
		}
	}

	/** 조건 주문 제출을 기록하고 정상 또는 결과 불명 응답을 재현합니다. */
	private static final class RecordingGateway implements SingleConditionalOrderGateway {
		private int callCount;
		private boolean unknown;
		private RuntimeException availabilityFailure;

		/** 준비한 LIVE 안전 차단을 재현하거나 MOCK 조건 주문 사용 가능 상태를 유지합니다. */
		@Override
		public void requireSubmissionAvailable() {
			if (availabilityFailure != null) {
				throw availabilityFailure;
			}
		}

		/** 호출을 기록하고 모의 조건 주문 식별값 또는 결과 불명 오류를 반환합니다. */
		@Override
		public ConditionalOrderCreationResponse submit(
				long accountSeq,
				SingleConditionalOrderSubmissionRequest request) {
			callCount++;
			if (unknown) {
				throw new OrderSubmissionException("테스트 결과 불명", true);
			}
			return new ConditionalOrderCreationResponse(
					"mock-conditional-id", request.clientOrderId());
		}

		/** 테스트 모의 모드 이름을 반환합니다. */
		@Override
		public String mode() {
			return "MOCK";
		}
	}
}

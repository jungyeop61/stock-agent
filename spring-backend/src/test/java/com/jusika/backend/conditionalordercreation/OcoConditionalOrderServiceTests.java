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
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalorder.OcoConditionalOrderSubmissionRequest;
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
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/** 실제 증권사를 호출하지 않고 OCO의 계산·승인·최종 검증·모의 실행을 검사합니다. */
class OcoConditionalOrderServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final OffsetDateTime NOW = OffsetDateTime.of(
			2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

	private RecordingPriceClient priceClient;
	private RecordingSellableQuantityClient sellableQuantityClient;
	private RecordingCommissionsClient commissionsClient;
	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingGateway gateway;
	private OcoConditionalOrderService service;

	/** 각 테스트에서 사용할 고정 시계와 메모리 가짜 객체를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_OCO_서비스를_준비한다() {
		priceClient = new RecordingPriceClient();
		sellableQuantityClient = new RecordingSellableQuantityClient();
		commissionsClient = new RecordingCommissionsClient();
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		gateway = new RecordingGateway();
		Clock clock = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
		service = new OcoConditionalOrderService(
				priceClient, sellableQuantityClient, commissionsClient,
				previewStore, executionStore, gateway,
				new OrderPreviewProperties(Duration.ofMinutes(2)), clock);
		국내_기본_조회_응답을_준비한다();
	}

	/** 두 조건의 예상 매도금액과 수수료를 각각 계산하고 수량은 한 번만 검사하는지 확인합니다. */
	@Test
	@DisplayName("국내 OCO 조건 주문 미리보기를 만든다")
	void 국내_OCO_조건_주문_미리보기를_만든다() {
		OcoConditionalOrderPreviewResponse preview = service.createPreview(기본_OCO_요청을_만든다());

		assertThat(preview.conditionalOrderType()).isEqualTo(ConditionalOrderType.OCO);
		assertThat(preview.referencePrice()).isEqualByComparingTo("70000");
		assertThat(preview.first().estimatedOrderAmount()).isEqualByComparingTo("790000");
		assertThat(preview.first().estimatedCommission()).isEqualByComparingTo("790");
		assertThat(preview.first().estimatedProceedsAfterCommission())
				.isEqualByComparingTo("789210");
		assertThat(preview.second().estimatedOrderAmount()).isEqualByComparingTo("649000");
		assertThat(preview.second().estimatedCommission()).isEqualByComparingTo("649");
		assertThat(preview.second().estimatedProceedsAfterCommission())
				.isEqualByComparingTo("648351");
		assertThat(preview.sellTaxExcluded()).isTrue();
		assertThat(preview.status()).isEqualTo(OrderPreviewStatus.PENDING_APPROVAL);
		assertThat(sellableQuantityClient.callCount).isEqualTo(1);
		assertThat(commissionsClient.callCount).isEqualTo(1);
		assertThat(gateway.callCount).isZero();
	}

	/** 미국 OCO도 달러로 계산하되 지정가 공통 수량은 정수만 허용하는지 검사합니다. */
	@Test
	@DisplayName("미국 OCO를 달러로 계산하고 소수 수량을 차단한다")
	void 미국_OCO를_달러로_계산하고_소수_수량을_차단한다() {
		priceClient.response = new StockPriceResponse(
				"AAPL", new BigDecimal("200.50"), "USD", NOW);
		sellableQuantityClient.response = new SellableQuantityResponse(
				ACCOUNT_SEQ, "AAPL", new BigDecimal("10"));
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ, List.of(new CommissionItem(
						"US", new BigDecimal("0.002"), null, null)));
		OcoConditionalOrderPreviewRequest request = new OcoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "aapl", new BigDecimal("2"), OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.SELL, "210.25", "209.99"),
				조건(OrderSide.SELL, "190.25", "190.00"));

		OcoConditionalOrderPreviewResponse preview = service.createPreview(request);

		assertThat(preview.symbol()).isEqualTo("AAPL");
		assertThat(preview.currency()).isEqualTo("USD");
		assertThat(preview.marketCountry()).isEqualTo("US");
		assertThat(preview.first().estimatedOrderAmount()).isEqualByComparingTo("419.98");
		assertThat(preview.requiresHighValueConfirmation()).isFalse();

		OcoConditionalOrderPreviewRequest fractional = new OcoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "AAPL", new BigDecimal("1.5"), OrderType.LIMIT,
				LocalDate.parse("2026-09-10"), request.first(), request.second());
		assertThatThrownBy(() -> service.createPreview(fractional))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("OCO 지정가 주문 수량은 정수여야 합니다.");
	}

	/** OCO 공식 규칙인 두 매도 지정가와 엄격한 감시가격 관계를 위반하면 차단합니다. */
	@Test
	@DisplayName("잘못된 OCO 방향 주문 유형과 감시가격 관계를 차단한다")
	void 잘못된_OCO_방향_주문_유형과_감시가격_관계를_차단한다() {
		OcoConditionalOrderPreviewRequest market = new OcoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.MARKET,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.SELL, "80000", "79900"),
				조건(OrderSide.SELL, "65000", "64900"));
		OcoConditionalOrderPreviewRequest buy = new OcoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.BUY, "80000", "79900"),
				조건(OrderSide.SELL, "65000", "64900"));
		OcoConditionalOrderPreviewRequest invalidRelation = new OcoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.SELL, "70000", "69900"),
				조건(OrderSide.SELL, "65000", "64900"));

		assertThatThrownBy(() -> service.createPreview(market))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("지정가");
		assertThatThrownBy(() -> service.createPreview(buy))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("매도만");
		assertThatThrownBy(() -> service.createPreview(invalidRelation))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("첫 감시가격 > 현재가 > 두 번째 감시가격");
		assertThat(previewStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 두 조건의 수량을 합산하지 않고 공통 수량 자체가 부족할 때만 거절하는지 검사합니다. */
	@Test
	@DisplayName("OCO 공통 매도 가능 수량이 부족하면 차단한다")
	void OCO_공통_매도_가능_수량이_부족하면_차단한다() {
		sellableQuantityClient.response = new SellableQuantityResponse(
				ACCOUNT_SEQ, "005930", new BigDecimal("9"));

		assertThatThrownBy(() -> service.createPreview(기본_OCO_요청을_만든다()))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("매도 가능 수량이 부족합니다.");
		assertThat(sellableQuantityClient.callCount).isEqualTo(1);
		assertThat(commissionsClient.callCount).isZero();
	}

	/** 어느 한 조건의 국내 주문금액이라도 1억원 이상이면 고액 확인 대상으로 표시합니다. */
	@Test
	@DisplayName("OCO의 한 조건이 1억원 이상이면 고액 확인 대상으로 표시한다")
	void OCO의_한_조건이_1억원_이상이면_고액_확인_대상으로_표시한다() {
		sellableQuantityClient.response = new SellableQuantityResponse(
				ACCOUNT_SEQ, "005930", new BigDecimal("2000"));
		OcoConditionalOrderPreviewRequest request = new OcoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", new BigDecimal("1500"), OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.SELL, "80000", "79900"),
				조건(OrderSide.SELL, "65000", "64900"));

		OcoConditionalOrderPreviewResponse preview = service.createPreview(request);

		assertThat(preview.first().estimatedOrderAmount())
				.isGreaterThanOrEqualTo(new BigDecimal("100000000"));
		assertThat(preview.requiresHighValueConfirmation()).isTrue();
	}

	/** 승인된 OCO를 재검증해 한 번만 모의 접수하고 동일 미리보기 재실행을 차단합니다. */
	@Test
	@DisplayName("승인된 OCO를 모의 실행하고 중복 실행을 막는다")
	void 승인된_OCO를_모의_실행하고_중복_실행을_막는다() {
		OcoConditionalOrderPreviewResponse preview = service.createPreview(기본_OCO_요청을_만든다());
		service.approvePreview(preview.previewId());

		OcoConditionalOrderExecutionResponse result =
				service.executeApprovedPreview(preview.previewId());

		assertThat(result.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(result.conditionalOrderId()).isEqualTo("mock-oco-id");
		assertThat(result.brokerMode()).isEqualTo("MOCK");
		assertThat(gateway.request.first().side()).isEqualTo(OrderSide.SELL);
		assertThat(gateway.request.second().triggerPrice()).isEqualByComparingTo("65000");
		assertThat(gateway.callCount).isEqualTo(1);
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderPreviewStateException.class);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** LIVE 가용성 검사가 실패하면 금융 조회나 미리보기·실행 상태를 변경하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전정책 차단은 OCO 조건 주문 재조회와 미리보기 소비 전에 적용된다")
	void LIVE_안전정책_차단은_OCO_조건_주문_재조회와_미리보기_소비_전에_적용된다() {
		OcoConditionalOrderPreviewResponse preview = service.createPreview(기본_OCO_요청을_만든다());
		service.approvePreview(preview.previewId());
		gateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE OCO 조건 주문 차단");
		int previewPriceCalls = priceClient.callCount;
		int previewSellableCalls = sellableQuantityClient.callCount;
		int previewCommissionCalls = commissionsClient.callCount;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE OCO 조건 주문 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(executionStore.values).isEmpty();
		assertThat(priceClient.callCount).isEqualTo(previewPriceCalls);
		assertThat(sellableQuantityClient.callCount).isEqualTo(previewSellableCalls);
		assertThat(commissionsClient.callCount).isEqualTo(previewCommissionCalls);
		assertThat(gateway.callCount).isZero();
	}

	/** 승인 후 현재가가 감시가격 범위를 벗어나면 실행권 생성 전에 안전하게 중단합니다. */
	@Test
	@DisplayName("승인 후 현재가가 OCO 범위를 벗어나면 실행을 차단한다")
	void 승인_후_현재가가_OCO_범위를_벗어나면_실행을_차단한다() {
		OcoConditionalOrderPreviewResponse preview = service.createPreview(기본_OCO_요청을_만든다());
		service.approvePreview(preview.previewId());
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("81000"), "KRW", NOW.plusSeconds(10));

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionValidationException.class)
				.hasMessageContaining("첫 감시가격 > 현재가 > 두 번째 감시가격");
		assertThat(executionStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 제출 결과를 알 수 없으면 UNKNOWN으로 저장하고 같은 요청의 자동 재생성을 금지합니다. */
	@Test
	@DisplayName("OCO 결과 불명은 UNKNOWN으로 저장하고 자동 재시도하지 않는다")
	void OCO_결과_불명은_UNKNOWN으로_저장하고_자동_재시도하지_않는다() {
		OcoConditionalOrderPreviewResponse preview = service.createPreview(기본_OCO_요청을_만든다());
		service.approvePreview(preview.previewId());
		gateway.unknown = true;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessageContaining("자동으로 다시 생성하지 마세요");
		OcoConditionalOrderExecutionResponse stored =
				executionStore.values.values().iterator().next();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 국내 OCO 미리보기에 필요한 정상 현재가·수량·수수료 응답을 준비합니다. */
	private void 국내_기본_조회_응답을_준비한다() {
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("70000"), "KRW", NOW);
		sellableQuantityClient.response = new SellableQuantityResponse(
				ACCOUNT_SEQ, "005930", new BigDecimal("100"));
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ, List.of(new CommissionItem(
						"KR", new BigDecimal("0.001"), null, null)));
	}

	/** 반복 테스트에서 사용할 정상적인 국내 OCO 미리보기 요청을 만듭니다. */
	private OcoConditionalOrderPreviewRequest 기본_OCO_요청을_만든다() {
		return new OcoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.SELL, "80000", "79000"),
				조건(OrderSide.SELL, "65000", "64900"));
	}

	/** 문자열 가격들로 OCO 미리보기의 한 조건을 만듭니다. */
	private OcoConditionalOrderPreviewRequest.Condition 조건(
			OrderSide side,
			String triggerPrice,
			String orderPrice) {
		return new OcoConditionalOrderPreviewRequest.Condition(
				side, new BigDecimal(triggerPrice), new BigDecimal(orderPrice));
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

	/** OCO 미리보기의 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryPreviewStore implements OcoConditionalOrderPreviewStore {
		private final Map<String, OcoConditionalOrderPreviewResponse> values = new HashMap<>();

		/** 새 OCO 미리보기를 메모리에 저장합니다. */
		@Override
		public OcoConditionalOrderPreviewResponse save(
				OcoConditionalOrderPreviewResponse preview) {
			values.put(preview.previewId(), preview);
			return preview;
		}

		/** 유효한 승인 대기 OCO 미리보기만 승인 상태로 변경합니다. */
		@Override
		public boolean approvePending(String id, OffsetDateTime at) {
			OcoConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| !value.expiresAt().isAfter(at)) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.APPROVED, at));
			return true;
		}

		/** 유효시간이 지난 승인 대기 OCO 미리보기만 만료 상태로 변경합니다. */
		@Override
		public boolean expirePending(String id, OffsetDateTime now) {
			OcoConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| value.expiresAt().isAfter(now)) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.EXPIRED, null));
			return true;
		}

		/** 승인된 OCO 미리보기만 사용 상태로 변경합니다. */
		@Override
		public boolean consumeApproved(String id, OffsetDateTime at) {
			OcoConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.APPROVED) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.CONSUMED, value.approvedAt()));
			return true;
		}

		/** 식별값으로 저장된 OCO 미리보기를 조회합니다. */
		@Override
		public Optional<OcoConditionalOrderPreviewResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 금융값은 유지하면서 OCO 미리보기 상태와 승인 시각만 변경합니다. */
		private OcoConditionalOrderPreviewResponse 상태를_바꾼다(
				OcoConditionalOrderPreviewResponse value,
				OrderPreviewStatus status,
				OffsetDateTime approvedAt) {
			return new OcoConditionalOrderPreviewResponse(
					value.previewId(), value.createdAt(), value.expiresAt(), value.accountSeq(),
					value.symbol(), value.conditionalOrderType(), value.quantity(), value.orderType(),
					value.expireDate(), value.referencePrice(), value.currency(),
					value.marketCountry(), value.commissionRate(), value.first(), value.second(),
					value.sellTaxExcluded(), value.requiresHighValueConfirmation(), status, approvedAt);
		}
	}

	/** OCO 실행의 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryExecutionStore implements OcoConditionalOrderExecutionStore {
		private final Map<String, OcoConditionalOrderExecutionResponse> values = new HashMap<>();

		/** OCO 미리보기당 첫 실행만 저장합니다. */
		@Override
		public boolean claim(OcoConditionalOrderExecutionResponse execution) {
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

		/** 실행을 OCO 식별값과 함께 접수 상태로 변경합니다. */
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

		/** 실행 식별값으로 저장된 OCO 실행을 조회합니다. */
		@Override
		public Optional<OcoConditionalOrderExecutionResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 미리보기 식별값으로 저장된 OCO 실행을 조회합니다. */
		@Override
		public Optional<OcoConditionalOrderExecutionResponse> findByPreviewId(String id) {
			return values.values().stream().filter(value -> value.previewId().equals(id)).findFirst();
		}

		/** 저장된 식별값은 유지하면서 OCO 실행 상태와 결과값만 변경합니다. */
		private boolean 변경한다(
				String id,
				OrderExecutionStatus status,
				OrderExecutionFailureType failure,
				String conditionalOrderId,
				OffsetDateTime submitted,
				OffsetDateTime completed) {
			OcoConditionalOrderExecutionResponse value = values.get(id);
			if (value == null) {
				return false;
			}
			OffsetDateTime updated = completed != null
					? completed : submitted != null ? submitted : NOW;
			values.put(id, new OcoConditionalOrderExecutionResponse(
					value.executionId(), value.previewId(), value.clientOrderId(),
					conditionalOrderId, value.brokerMode(), status, failure,
					value.createdAt(), updated,
					submitted != null ? submitted : value.submittedAt(), completed));
			return true;
		}
	}

	/** OCO 제출을 기록하고 정상 또는 결과 불명 응답을 재현합니다. */
	private static final class RecordingGateway implements OcoConditionalOrderGateway {
		private int callCount;
		private boolean unknown;
		private OcoConditionalOrderSubmissionRequest request;
		private RuntimeException availabilityFailure;

		/** 준비한 LIVE 안전 차단을 재현하거나 MOCK OCO 사용 가능 상태를 유지합니다. */
		@Override
		public void requireSubmissionAvailable() {
			if (availabilityFailure != null) {
				throw availabilityFailure;
			}
		}

		/** 호출을 기록하고 모의 OCO 식별값 또는 결과 불명 오류를 반환합니다. */
		@Override
		public ConditionalOrderCreationResponse submit(
				long accountSeq,
				OcoConditionalOrderSubmissionRequest request) {
			callCount++;
			this.request = request;
			if (unknown) {
				throw new OrderSubmissionException("테스트 결과 불명", true);
			}
			return new ConditionalOrderCreationResponse("mock-oco-id", request.clientOrderId());
		}

		/** 테스트 모의 모드 이름을 반환합니다. */
		@Override
		public String mode() {
			return "MOCK";
		}
	}
}

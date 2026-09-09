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
import com.jusika.backend.conditionalorder.OtoConditionalOrderSubmissionRequest;
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
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;

/** 실제 증권사를 호출하지 않고 OTO의 계산·승인·최종 검증·모의 실행을 검사합니다. */
class OtoConditionalOrderServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final OffsetDateTime NOW = OffsetDateTime.of(
			2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

	private RecordingPriceClient priceClient;
	private RecordingBuyingPowerClient buyingPowerClient;
	private RecordingCommissionsClient commissionsClient;
	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingGateway gateway;
	private OtoConditionalOrderService service;

	/** 각 테스트에서 사용할 고정 시계와 메모리 가짜 객체를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_OTO_서비스를_준비한다() {
		priceClient = new RecordingPriceClient();
		buyingPowerClient = new RecordingBuyingPowerClient();
		commissionsClient = new RecordingCommissionsClient();
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		gateway = new RecordingGateway();
		Clock clock = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
		service = new OtoConditionalOrderService(
				priceClient, buyingPowerClient, commissionsClient,
				previewStore, executionStore, gateway,
				new OrderPreviewProperties(Duration.ofMinutes(2)), clock);
		국내_기본_조회_응답을_준비한다();
	}

	/** 선행 매수 필요 금액과 후행 매도 수령액을 방향에 맞게 각각 계산하는지 검사합니다. */
	@Test
	@DisplayName("국내 OTO 조건 주문 미리보기를 만든다")
	void 국내_OTO_조건_주문_미리보기를_만든다() {
		OtoConditionalOrderPreviewResponse preview = service.createPreview(기본_OTO_요청을_만든다());

		assertThat(preview.conditionalOrderType()).isEqualTo(ConditionalOrderType.OTO);
		assertThat(preview.referencePrice()).isEqualByComparingTo("70000");
		assertThat(preview.first().side()).isEqualTo(OrderSide.BUY);
		assertThat(preview.first().estimatedOrderAmount()).isEqualByComparingTo("690000");
		assertThat(preview.first().estimatedCommission()).isEqualByComparingTo("690");
		assertThat(preview.first().estimatedAmountAfterCommission())
				.isEqualByComparingTo("690690");
		assertThat(preview.second().side()).isEqualTo(OrderSide.SELL);
		assertThat(preview.second().estimatedOrderAmount()).isEqualByComparingTo("800000");
		assertThat(preview.second().estimatedCommission()).isEqualByComparingTo("800");
		assertThat(preview.second().estimatedAmountAfterCommission())
				.isEqualByComparingTo("799200");
		assertThat(preview.buyingPowerChecked()).isTrue();
		assertThat(preview.sellTaxExcluded()).isTrue();
		assertThat(buyingPowerClient.callCount).isEqualTo(1);
		assertThat(gateway.callCount).isZero();
	}

	/** OTO 공식 규칙인 선행 매수·후행 매도·공통 지정가를 위반하면 차단합니다. */
	@Test
	@DisplayName("잘못된 OTO 방향과 주문 유형을 차단한다")
	void 잘못된_OTO_방향과_주문_유형을_차단한다() {
		OtoConditionalOrderPreviewRequest market = new OtoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.MARKET,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.BUY, "68000", "69000"),
				조건(OrderSide.SELL, "79000", "80000"));
		OtoConditionalOrderPreviewRequest wrongFirst = new OtoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.SELL, "68000", "69000"),
				조건(OrderSide.SELL, "79000", "80000"));
		OtoConditionalOrderPreviewRequest wrongSecond = new OtoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.BUY, "68000", "69000"),
				조건(OrderSide.BUY, "79000", "80000"));

		assertThatThrownBy(() -> service.createPreview(market))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("지정가");
		assertThatThrownBy(() -> service.createPreview(wrongFirst))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("첫 번째 OTO 조건은 매수");
		assertThatThrownBy(() -> service.createPreview(wrongSecond))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("두 번째 OTO 조건은 매도");
		assertThat(previewStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 첫 매수의 주문금액과 수수료를 합친 금액보다 현금이 적으면 차단합니다. */
	@Test
	@DisplayName("OTO 첫 매수 가능 금액이 부족하면 차단한다")
	void OTO_첫_매수_가능_금액이_부족하면_차단한다() {
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "KRW", new BigDecimal("690689"));

		assertThatThrownBy(() -> service.createPreview(기본_OTO_요청을_만든다()))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("첫 OTO 매수에 필요한 금액이 부족합니다.");
		assertThat(buyingPowerClient.callCount).isEqualTo(1);
		assertThat(previewStore.values).isEmpty();
	}

	/** 미국 OTO도 달러로 계산하되 지정가 공통 수량은 정수만 허용하는지 검사합니다. */
	@Test
	@DisplayName("미국 OTO를 달러로 계산하고 소수 수량을 차단한다")
	void 미국_OTO를_달러로_계산하고_소수_수량을_차단한다() {
		priceClient.response = new StockPriceResponse(
				"AAPL", new BigDecimal("200.50"), "USD", NOW);
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "USD", new BigDecimal("1000"));
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ, List.of(new CommissionItem(
						"US", new BigDecimal("0.002"), null, null)));
		OtoConditionalOrderPreviewRequest request = new OtoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "aapl", new BigDecimal("2"), OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.BUY, "195.25", "196.10"),
				조건(OrderSide.SELL, "220.25", "219.90"));

		OtoConditionalOrderPreviewResponse preview = service.createPreview(request);

		assertThat(preview.symbol()).isEqualTo("AAPL");
		assertThat(preview.currency()).isEqualTo("USD");
		assertThat(preview.first().estimatedOrderAmount()).isEqualByComparingTo("392.20");
		assertThat(preview.second().estimatedOrderAmount()).isEqualByComparingTo("439.80");

		OtoConditionalOrderPreviewRequest fractional = new OtoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "AAPL", new BigDecimal("1.5"), OrderType.LIMIT,
				LocalDate.parse("2026-09-10"), request.first(), request.second());
		assertThatThrownBy(() -> service.createPreview(fractional))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("OTO 지정가 주문 수량은 정수여야 합니다.");
	}

	/** 어느 한 국내 조건의 주문금액이라도 1억원 이상이면 고액 확인 대상으로 표시합니다. */
	@Test
	@DisplayName("OTO의 한 조건이 1억원 이상이면 고액 확인 대상으로 표시한다")
	void OTO의_한_조건이_1억원_이상이면_고액_확인_대상으로_표시한다() {
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "KRW", new BigDecimal("200000000"));
		OtoConditionalOrderPreviewRequest request = new OtoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", new BigDecimal("1500"), OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.BUY, "68000", "69000"),
				조건(OrderSide.SELL, "79000", "80000"));

		OtoConditionalOrderPreviewResponse preview = service.createPreview(request);

		assertThat(preview.first().estimatedOrderAmount())
				.isGreaterThanOrEqualTo(new BigDecimal("100000000"));
		assertThat(preview.requiresHighValueConfirmation()).isTrue();
	}

	/** 승인된 OTO를 재검증해 한 번만 모의 접수하고 동일 미리보기 재실행을 차단합니다. */
	@Test
	@DisplayName("승인된 OTO를 모의 실행하고 중복 실행을 막는다")
	void 승인된_OTO를_모의_실행하고_중복_실행을_막는다() {
		OtoConditionalOrderPreviewResponse preview = service.createPreview(기본_OTO_요청을_만든다());
		service.approvePreview(preview.previewId());

		OtoConditionalOrderExecutionResponse result =
				service.executeApprovedPreview(preview.previewId());

		assertThat(result.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(result.conditionalOrderId()).isEqualTo("mock-oto-id");
		assertThat(result.brokerMode()).isEqualTo("MOCK");
		assertThat(gateway.request.first().side()).isEqualTo(OrderSide.BUY);
		assertThat(gateway.request.second().side()).isEqualTo(OrderSide.SELL);
		assertThat(gateway.callCount).isEqualTo(1);
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderPreviewStateException.class);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** LIVE 가용성 검사가 실패하면 금융 조회나 미리보기·실행 상태를 변경하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전정책 차단은 OTO 조건 주문 재조회와 미리보기 소비 전에 적용된다")
	void LIVE_안전정책_차단은_OTO_조건_주문_재조회와_미리보기_소비_전에_적용된다() {
		OtoConditionalOrderPreviewResponse preview = service.createPreview(기본_OTO_요청을_만든다());
		service.approvePreview(preview.previewId());
		gateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE OTO 조건 주문 차단");
		int previewPriceCalls = priceClient.callCount;
		int previewBuyingPowerCalls = buyingPowerClient.callCount;
		int previewCommissionCalls = commissionsClient.callCount;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE OTO 조건 주문 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(executionStore.values).isEmpty();
		assertThat(priceClient.callCount).isEqualTo(previewPriceCalls);
		assertThat(buyingPowerClient.callCount).isEqualTo(previewBuyingPowerCalls);
		assertThat(commissionsClient.callCount).isEqualTo(previewCommissionCalls);
		assertThat(gateway.callCount).isZero();
	}

	/** 승인 후 현금이 감소하면 OTO 실행권을 만들기 전에 안전하게 중단합니다. */
	@Test
	@DisplayName("승인 후 첫 매수 가능 금액이 부족해지면 실행을 차단한다")
	void 승인_후_첫_매수_가능_금액이_부족해지면_실행을_차단한다() {
		OtoConditionalOrderPreviewResponse preview = service.createPreview(기본_OTO_요청을_만든다());
		service.approvePreview(preview.previewId());
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "KRW", new BigDecimal("1000"));

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionValidationException.class)
				.hasMessage("첫 OTO 매수에 필요한 금액이 부족합니다.");
		assertThat(executionStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 제출 결과를 알 수 없으면 UNKNOWN으로 저장하고 같은 요청의 자동 재생성을 금지합니다. */
	@Test
	@DisplayName("OTO 결과 불명은 UNKNOWN으로 저장하고 자동 재시도하지 않는다")
	void OTO_결과_불명은_UNKNOWN으로_저장하고_자동_재시도하지_않는다() {
		OtoConditionalOrderPreviewResponse preview = service.createPreview(기본_OTO_요청을_만든다());
		service.approvePreview(preview.previewId());
		gateway.unknown = true;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessageContaining("자동으로 다시 생성하지 마세요");
		OtoConditionalOrderExecutionResponse stored =
				executionStore.values.values().iterator().next();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 국내 OTO 미리보기에 필요한 정상 현재가·매수 가능 금액·수수료를 준비합니다. */
	private void 국내_기본_조회_응답을_준비한다() {
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("70000"), "KRW", NOW);
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "KRW", new BigDecimal("10000000"));
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ, List.of(new CommissionItem(
						"KR", new BigDecimal("0.001"), null, null)));
	}

	/** 반복 테스트에서 사용할 정상적인 국내 OTO 미리보기 요청을 만듭니다. */
	private OtoConditionalOrderPreviewRequest 기본_OTO_요청을_만든다() {
		return new OtoConditionalOrderPreviewRequest(
				ACCOUNT_SEQ, "005930", BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"),
				조건(OrderSide.BUY, "68000", "69000"),
				조건(OrderSide.SELL, "79000", "80000"));
	}

	/** 문자열 가격들로 OTO 미리보기의 한 조건을 만듭니다. */
	private OtoConditionalOrderPreviewRequest.Condition 조건(
			OrderSide side,
			String triggerPrice,
			String orderPrice) {
		return new OtoConditionalOrderPreviewRequest.Condition(
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

	/** OTO 미리보기의 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryPreviewStore implements OtoConditionalOrderPreviewStore {
		private final Map<String, OtoConditionalOrderPreviewResponse> values = new HashMap<>();

		/** 새 OTO 미리보기를 메모리에 저장합니다. */
		@Override
		public OtoConditionalOrderPreviewResponse save(
				OtoConditionalOrderPreviewResponse preview) {
			values.put(preview.previewId(), preview);
			return preview;
		}

		/** 유효한 승인 대기 OTO 미리보기만 승인 상태로 변경합니다. */
		@Override
		public boolean approvePending(String id, OffsetDateTime at) {
			OtoConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| !value.expiresAt().isAfter(at)) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.APPROVED, at));
			return true;
		}

		/** 유효시간이 지난 승인 대기 OTO 미리보기만 만료 상태로 변경합니다. */
		@Override
		public boolean expirePending(String id, OffsetDateTime now) {
			OtoConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| value.expiresAt().isAfter(now)) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.EXPIRED, null));
			return true;
		}

		/** 승인된 OTO 미리보기만 사용 상태로 변경합니다. */
		@Override
		public boolean consumeApproved(String id, OffsetDateTime at) {
			OtoConditionalOrderPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderPreviewStatus.APPROVED) {
				return false;
			}
			values.put(id, 상태를_바꾼다(value, OrderPreviewStatus.CONSUMED, value.approvedAt()));
			return true;
		}

		/** 식별값으로 저장된 OTO 미리보기를 조회합니다. */
		@Override
		public Optional<OtoConditionalOrderPreviewResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 금융값은 유지하면서 OTO 미리보기 상태와 승인 시각만 변경합니다. */
		private OtoConditionalOrderPreviewResponse 상태를_바꾼다(
				OtoConditionalOrderPreviewResponse value,
				OrderPreviewStatus status,
				OffsetDateTime approvedAt) {
			return new OtoConditionalOrderPreviewResponse(
					value.previewId(), value.createdAt(), value.expiresAt(), value.accountSeq(),
					value.symbol(), value.conditionalOrderType(), value.quantity(), value.orderType(),
					value.expireDate(), value.referencePrice(), value.currency(),
					value.marketCountry(), value.commissionRate(), value.first(), value.second(),
					value.buyingPowerChecked(), value.sellTaxExcluded(),
					value.requiresHighValueConfirmation(), status, approvedAt);
		}
	}

	/** OTO 실행의 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryExecutionStore implements OtoConditionalOrderExecutionStore {
		private final Map<String, OtoConditionalOrderExecutionResponse> values = new HashMap<>();

		/** OTO 미리보기당 첫 실행만 저장합니다. */
		@Override
		public boolean claim(OtoConditionalOrderExecutionResponse execution) {
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

		/** 실행을 OTO 식별값과 함께 접수 상태로 변경합니다. */
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

		/** 실행 식별값으로 저장된 OTO 실행을 조회합니다. */
		@Override
		public Optional<OtoConditionalOrderExecutionResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 미리보기 식별값으로 저장된 OTO 실행을 조회합니다. */
		@Override
		public Optional<OtoConditionalOrderExecutionResponse> findByPreviewId(String id) {
			return values.values().stream().filter(value -> value.previewId().equals(id)).findFirst();
		}

		/** 저장된 식별값은 유지하면서 OTO 실행 상태와 결과값만 변경합니다. */
		private boolean 변경한다(
				String id,
				OrderExecutionStatus status,
				OrderExecutionFailureType failure,
				String conditionalOrderId,
				OffsetDateTime submitted,
				OffsetDateTime completed) {
			OtoConditionalOrderExecutionResponse value = values.get(id);
			if (value == null) {
				return false;
			}
			OffsetDateTime updated = completed != null
					? completed : submitted != null ? submitted : NOW;
			values.put(id, new OtoConditionalOrderExecutionResponse(
					value.executionId(), value.previewId(), value.clientOrderId(),
					conditionalOrderId, value.brokerMode(), status, failure,
					value.createdAt(), updated,
					submitted != null ? submitted : value.submittedAt(), completed));
			return true;
		}
	}

	/** OTO 제출을 기록하고 정상 또는 결과 불명 응답을 재현합니다. */
	private static final class RecordingGateway implements OtoConditionalOrderGateway {
		private int callCount;
		private boolean unknown;
		private OtoConditionalOrderSubmissionRequest request;
		private RuntimeException availabilityFailure;

		/** 준비한 LIVE 안전 차단을 재현하거나 MOCK OTO 사용 가능 상태를 유지합니다. */
		@Override
		public void requireSubmissionAvailable() {
			if (availabilityFailure != null) {
				throw availabilityFailure;
			}
		}

		/** 호출을 기록하고 모의 OTO 식별값 또는 결과 불명 오류를 반환합니다. */
		@Override
		public ConditionalOrderCreationResponse submit(
				long accountSeq,
				OtoConditionalOrderSubmissionRequest request) {
			callCount++;
			this.request = request;
			if (unknown) {
				throw new OrderSubmissionException("테스트 결과 불명", true);
			}
			return new ConditionalOrderCreationResponse("mock-oto-id", request.clientOrderId());
		}

		/** 테스트 모의 모드 이름을 반환합니다. */
		@Override
		public String mode() {
			return "MOCK";
		}
	}
}

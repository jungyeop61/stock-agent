package com.jusika.backend.orderexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;
import com.jusika.backend.orderpreview.OrderPreviewResponse;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderPreviewStore;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 실제 토스증권 주문 없이 승인된 미리보기의 최종 재검증과 모의 실행 상태를 검사합니다.
 */
class OrderExecutionServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-09-04T12:00:00Z");

	private FakeOrderPreviewStore previewStore;
	private FakeOrderExecutionStore executionStore;
	private FakeOrderSubmissionGateway submissionGateway;
	private FakePriceClient priceClient;
	private FakeBuyingPowerClient buyingPowerClient;
	private FakeSellableQuantityClient sellableQuantityClient;
	private FakeCommissionsClient commissionsClient;
	private OrderExecutionService service;

	/**
	 * 각 테스트에 고정 시각과 네트워크를 사용하지 않는 모든 가짜 의존성을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_모의_주문_실행_서비스를_준비한다() {
		previewStore = new FakeOrderPreviewStore();
		executionStore = new FakeOrderExecutionStore();
		submissionGateway = new FakeOrderSubmissionGateway();
		priceClient = new FakePriceClient();
		buyingPowerClient = new FakeBuyingPowerClient();
		sellableQuantityClient = new FakeSellableQuantityClient();
		commissionsClient = new FakeCommissionsClient();
		service = new OrderExecutionService(
				previewStore,
				executionStore,
				submissionGateway,
				new OrderRequestFingerprint(),
				priceClient,
				buyingPowerClient,
				sellableQuantityClient,
				commissionsClient,
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	/**
	 * 승인된 국내 매수 주문을 모두 다시 확인한 뒤 모의 접수 상태로 기록하는지 검사합니다.
	 */
	@Test
	@DisplayName("승인된 국내 매수를 최종 재검증하고 모의 접수한다")
	void 승인된_국내_매수를_최종_재검증하고_모의_접수한다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		국내_최종_조회값을_준비한다();

		OrderExecutionResponse response = service.executeApprovedPreview(preview.previewId());

		assertThat(response.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(response.brokerMode()).isEqualTo("MOCK");
		assertThat(response.brokerOrderId()).startsWith("fake-");
		assertThat(UUID.fromString(response.executionId())).isNotNull();
		assertThat(UUID.fromString(response.clientOrderId())).isNotNull();
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
		assertThat(submissionGateway.callCount).isOne();
		assertThat(submissionGateway.lastRequest.symbol()).isEqualTo("005930");
		assertThat(submissionGateway.lastRequest.price()).isEqualByComparingTo("70000");
		assertThat(priceClient.callCount).isOne();
		assertThat(buyingPowerClient.callCount).isOne();
		assertThat(sellableQuantityClient.callCount).isZero();
		assertThat(commissionsClient.callCount).isOne();
	}

	/**
	 * LIVE 가용성 검사가 실패하면 미리보기·실행 상태와 외부 조회를 전혀 변경하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("LIVE 안전정책 차단은 미리보기 소비 전에 적용된다")
	void LIVE_안전정책_차단은_미리보기_소비_전에_적용된다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		submissionGateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE 안전 차단");

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE 안전 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(executionStore.findByPreviewId(preview.previewId())).isEmpty();
		assertThat(submissionGateway.callCount).isZero();
		assertThat(priceClient.callCount).isZero();
	}

	/** 최종 금융 재검증 뒤 한도 초과가 확인되면 실행권이나 미리보기를 변경하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 1회 주문 한도 차단은 실행 기록 생성 전에 적용된다")
	void LIVE_1회_주문_한도_차단은_실행_기록_생성_전에_적용된다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		국내_최종_조회값을_준비한다();
		submissionGateway.limitFailure = new BrokerMutationBlockedException(
				"테스트 LIVE 1회 주문 한도 차단");

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE 1회 주문 한도 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(executionStore.findByPreviewId(preview.previewId())).isEmpty();
		assertThat(submissionGateway.callCount).isZero();
		assertThat(submissionGateway.lastRiskSnapshot).isNotNull();
		assertThat(priceClient.callCount).isOne();
	}

	/**
	 * 이미 사용된 미리보기를 다시 실행할 때 최종 조회나 주문 제출을 시작하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("같은 주문 미리보기의 중복 실행을 차단한다")
	void 같은_주문_미리보기의_중복_실행을_차단한다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		국내_최종_조회값을_준비한다();
		service.executeApprovedPreview(preview.previewId());
		int firstPriceCalls = priceClient.callCount;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessage("승인된 주문 미리보기만 실행할 수 있습니다.");
		assertThat(submissionGateway.callCount).isOne();
		assertThat(priceClient.callCount).isEqualTo(firstPriceCalls);
	}

	/**
	 * 승인 뒤 매수 가능 금액이 줄었으면 실행권을 만들지 않고 주문을 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("최종 매수 가능 금액이 부족하면 주문을 실행하지 않는다")
	void 최종_매수_가능_금액이_부족하면_주문을_실행하지_않는다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		국내_최종_조회값을_준비한다();
		buyingPowerClient.response = new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("100"));

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionValidationException.class)
				.hasMessage("최종 확인 결과 매수 가능 금액이 부족합니다. 새 미리보기를 만들어 주세요.");
		assertThat(executionStore.findByPreviewId(preview.previewId())).isEmpty();
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(submissionGateway.callCount).isZero();
	}

	/**
	 * 최종 조회 응답의 계좌가 승인한 계좌와 다르면 금액이 충분해도 주문하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("다른 계좌의 매수 가능 금액 응답을 거절한다")
	void 다른_계좌의_매수_가능_금액_응답을_거절한다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		국내_최종_조회값을_준비한다();
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ + 1, "KRW", new BigDecimal("100000"));

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionValidationException.class)
				.hasMessage("최종 매수 가능 금액이 승인한 계좌 또는 통화와 일치하지 않습니다.");
		assertThat(executionStore.findByPreviewId(preview.previewId())).isEmpty();
		assertThat(submissionGateway.callCount).isZero();
	}

	/**
	 * 승인 뒤 매도 가능 수량이 줄었으면 모의 주문도 제출하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("최종 매도 가능 수량이 부족하면 주문을 실행하지 않는다")
	void 최종_매도_가능_수량이_부족하면_주문을_실행하지_않는다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.SELL, "KRW", "KR");
		국내_최종_조회값을_준비한다();
		sellableQuantityClient.response =
				new SellableQuantityResponse(ACCOUNT_SEQ, "005930", BigDecimal.ZERO);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionValidationException.class)
				.hasMessage("최종 확인 결과 매도 가능 수량이 부족합니다. 새 미리보기를 만들어 주세요.");
		assertThat(executionStore.findByPreviewId(preview.previewId())).isEmpty();
		assertThat(submissionGateway.callCount).isZero();
		assertThat(buyingPowerClient.callCount).isZero();
	}

	/**
	 * 증권사가 확정적으로 거절했다고 가정하면 거절 상태와 실패 분류를 남기는지 검사합니다.
	 */
	@Test
	@DisplayName("모의 증권사의 확정 거절을 실행 기록에 남긴다")
	void 모의_증권사의_확정_거절을_실행_기록에_남긴다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		국내_최종_조회값을_준비한다();
		submissionGateway.failure = new OrderSubmissionException("테스트 거절", false);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessage("증권사가 주문을 거절했습니다.");
		OrderExecutionResponse stored = executionStore.findByPreviewId(preview.previewId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.REJECTED);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.BROKER_REJECTED);
		assertThat(stored.completedAt()).isNotNull();
	}

	/**
	 * 응답을 받지 못해 접수 여부가 불명확하면 자동 재시도를 금지하는 상태로 남기는지 검사합니다.
	 */
	@Test
	@DisplayName("모의 제출 결과가 불명확하면 UNKNOWN 상태로 남긴다")
	void 모의_제출_결과가_불명확하면_UNKNOWN_상태로_남긴다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "KR");
		국내_최종_조회값을_준비한다();
		submissionGateway.failure = new OrderSubmissionException("테스트 결과 불명", true);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessage("주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 주문하지 마세요.");
		OrderExecutionResponse stored = executionStore.findByPreviewId(preview.previewId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(stored.completedAt()).isNull();
	}

	/**
	 * 승인 시각과 무관하게 미리보기 유효시간이 지났으면 재검증 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("유효시간이 지난 승인 미리보기는 실행하지 않는다")
	void 유효시간이_지난_승인_미리보기는_실행하지_않는다() {
		OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
		OrderPreviewResponse expired = 미리보기를_만든다(
				OrderSide.BUY, "KRW", "KR", now.minusMinutes(3), now.minusMinutes(1));
		previewStore.save(expired);

		assertThatThrownBy(() -> service.executeApprovedPreview(expired.previewId()))
				.isInstanceOf(com.jusika.backend.orderpreview.OrderPreviewExpiredException.class);
		assertThat(priceClient.callCount).isZero();
		assertThat(submissionGateway.callCount).isZero();
	}

	/**
	 * 승인된 통화와 시장 국가 코드가 서로 맞지 않으면 계좌 조회와 주문 제출 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("통화와 시장 국가 코드가 맞지 않는 미리보기를 차단한다")
	void 통화와_시장_국가_코드가_맞지_않는_미리보기를_차단한다() {
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(OrderSide.BUY, "KRW", "US");
		국내_최종_조회값을_준비한다();

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionValidationException.class)
				.hasMessage("최종 현재가가 승인한 주문 종목 또는 통화와 일치하지 않습니다.");
		assertThat(commissionsClient.callCount).isZero();
		assertThat(submissionGateway.callCount).isZero();
	}

	/**
	 * 현재 시각에 유효한 승인 미리보기를 만들고 테스트 저장소에 보관합니다.
	 *
	 * @param side 매수 또는 매도 방향
	 * @param currency 거래 통화
	 * @param marketCountry 거래 시장 국가 코드
	 * @return 저장된 승인 미리보기
	 */
	private OrderPreviewResponse 승인된_미리보기를_저장한다(
			OrderSide side,
			String currency,
			String marketCountry) {
		OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
		OrderPreviewResponse preview = 미리보기를_만든다(
				side, currency, marketCountry, now.minusSeconds(30), now.plusMinutes(1));
		return previewStore.save(preview);
	}

	/**
	 * 국내 종목의 현재가, 충분한 계좌 금액·수량과 국내 수수료를 준비합니다.
	 */
	private void 국내_최종_조회값을_준비한다() {
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("72000"), "KRW",
				OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("100000"));
		sellableQuantityClient.response =
				new SellableQuantityResponse(ACCOUNT_SEQ, "005930", new BigDecimal("10"));
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ,
				List.of(new CommissionItem(
						"KR", new BigDecimal("0.00015"), LocalDate.of(2026, 1, 1), null)));
	}

	/**
	 * 테스트 조건에 맞는 지정가 1주 승인 미리보기 전체 값을 만듭니다.
	 *
	 * @param side 매수 또는 매도 방향
	 * @param currency 거래 통화
	 * @param marketCountry 거래 시장 국가 코드
	 * @param createdAt 생성 시각
	 * @param expiresAt 유효시간 종료 시각
	 * @return 승인 상태인 주문 미리보기
	 */
	private OrderPreviewResponse 미리보기를_만든다(
			OrderSide side,
			String currency,
			String marketCountry,
			OffsetDateTime createdAt,
			OffsetDateTime expiresAt) {
		return new OrderPreviewResponse(
				UUID.randomUUID().toString(), createdAt, expiresAt, ACCOUNT_SEQ, "005930",
				side, OrderType.LIMIT, BigDecimal.ONE, new BigDecimal("70000"),
				new BigDecimal("72000"), new BigDecimal("70000"), currency, marketCountry,
				new BigDecimal("0.00015"), new BigDecimal("70000"), new BigDecimal("10.5"),
				side == OrderSide.BUY ? new BigDecimal("70010.5") : new BigDecimal("69989.5"),
				side == OrderSide.SELL, false, true, OrderPreviewStatus.APPROVED, createdAt);
	}

	/**
	 * 테스트 메모리에서 미리보기 상태를 변경하는 저장소입니다.
	 */
	private static final class FakeOrderPreviewStore implements OrderPreviewStore {

		private final Map<String, OrderPreviewResponse> previews = new HashMap<>();

		/** 저장할 미리보기를 테스트 메모리에 보관합니다. */
		@Override
		public OrderPreviewResponse save(OrderPreviewResponse preview) {
			previews.put(preview.previewId(), preview);
			return preview;
		}

		/** 이 서비스 테스트에서는 사용하지 않는 승인 변경을 항상 거절합니다. */
		@Override
		public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
			return false;
		}

		/** 이 서비스 테스트에서는 사용하지 않는 만료 변경을 항상 거절합니다. */
		@Override
		public boolean expirePending(String previewId, OffsetDateTime now) {
			return false;
		}

		/** 승인 상태인 미리보기만 사용 완료 상태로 한 번 변경합니다. */
		@Override
		public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
			OrderPreviewResponse preview = previews.get(previewId);
			if (preview == null || preview.status() != OrderPreviewStatus.APPROVED) {
				return false;
			}
			previews.put(previewId, 상태를_복사한다(preview, OrderPreviewStatus.CONSUMED));
			return true;
		}

		/** 식별값으로 테스트 메모리의 미리보기를 조회합니다. */
		@Override
		public Optional<OrderPreviewResponse> findById(String previewId) {
			return Optional.ofNullable(previews.get(previewId));
		}

		/** 계산 내용은 유지하고 상태만 변경한 미리보기 복사본을 만듭니다. */
		private OrderPreviewResponse 상태를_복사한다(
				OrderPreviewResponse preview,
				OrderPreviewStatus status) {
			return new OrderPreviewResponse(
					preview.previewId(), preview.createdAt(), preview.expiresAt(), preview.accountSeq(),
					preview.symbol(), preview.side(), preview.orderType(), preview.quantity(),
					preview.requestedPrice(), preview.referencePrice(), preview.calculationPrice(),
					preview.currency(), preview.marketCountry(), preview.commissionRate(),
					preview.estimatedOrderAmount(), preview.estimatedCommission(),
					preview.estimatedAmountAfterCommission(), preview.sellTaxExcluded(),
					preview.requiresHighValueConfirmation(), preview.orderReady(), status,
					preview.approvedAt());
		}
	}

	/**
	 * 테스트 메모리에서 실행권과 조건부 상태 변경을 구현하는 저장소입니다.
	 */
	private static final class FakeOrderExecutionStore implements OrderExecutionStore {

		private final Map<String, OrderExecutionResponse> executions = new HashMap<>();
		private final Map<String, String> executionIdsByPreview = new HashMap<>();
		private final Map<String, String> fingerprints = new HashMap<>();

		/** 미리보기에 기존 실행이 없을 때만 준비 기록을 저장합니다. */
		@Override
		public boolean claim(OrderExecutionResponse execution, String requestFingerprint) {
			if (executionIdsByPreview.containsKey(execution.previewId())) {
				return false;
			}
			executions.put(execution.executionId(), execution);
			fingerprints.put(execution.executionId(), requestFingerprint);
			executionIdsByPreview.put(execution.previewId(), execution.executionId());
			return true;
		}

		/** 준비 상태인 실행만 제출 중 상태로 변경합니다. */
		@Override
		public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
			return 상태를_바꾼다(
					executionId, OrderExecutionStatus.PREPARED, OrderExecutionStatus.SUBMITTING,
					null, null, submittedAt, null);
		}

		/** 준비 단계의 내부 오류를 확정 거절 상태로 기록합니다. */
		@Override
		public boolean markPreparationFailed(String executionId, OffsetDateTime failedAt) {
			return 상태를_바꾼다(
					executionId, OrderExecutionStatus.PREPARED, OrderExecutionStatus.REJECTED,
					null, OrderExecutionFailureType.INTERNAL_STATE, null, failedAt);
		}

		/** 제출 중인 실행을 모의 접수 상태로 변경합니다. */
		@Override
		public boolean markAccepted(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			return 상태를_바꾼다(
					executionId, OrderExecutionStatus.SUBMITTING, OrderExecutionStatus.ACCEPTED,
					brokerOrderId, null, null, completedAt);
		}

		/** 제출 중인 실행을 증권사 확정 거절 상태로 변경합니다. */
		@Override
		public boolean markRejected(String executionId, OffsetDateTime failedAt) {
			return 상태를_바꾼다(
					executionId, OrderExecutionStatus.SUBMITTING, OrderExecutionStatus.REJECTED,
					null, OrderExecutionFailureType.BROKER_REJECTED, null, failedAt);
		}

		/** 제출 중인 실행을 접수 여부 불명 상태로 변경합니다. */
		@Override
		public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
			return 상태를_바꾼다(
					executionId, OrderExecutionStatus.SUBMITTING, OrderExecutionStatus.UNKNOWN,
					null, OrderExecutionFailureType.SUBMISSION_UNKNOWN, null, null);
		}

		/** 실행 식별값으로 테스트 복구 후보와 요청 지문을 조회합니다. */
		@Override
		public Optional<OrderExecutionRecoveryCandidate> findRecoveryCandidateById(String executionId) {
			return findById(executionId).map(execution -> new OrderExecutionRecoveryCandidate(
					execution, fingerprints.get(executionId)));
		}

		/** 이 실행 서비스 테스트에서는 복구권을 사용하지 않습니다. */
		@Override
		public boolean claimRecovery(
				String executionId,
				OffsetDateTime submittedAfter,
				OffsetDateTime recoveryStartedAt) {
			return false;
		}

		/** 이 실행 서비스 테스트에서는 복구 접수 상태를 사용하지 않습니다. */
		@Override
		public boolean markRecovered(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			return false;
		}

		/** 이 실행 서비스 테스트에서는 복구 결과 불명 상태를 사용하지 않습니다. */
		@Override
		public boolean markRecoveryUnknown(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 실행 식별값으로 테스트 실행 기록을 조회합니다. */
		@Override
		public Optional<OrderExecutionResponse> findById(String executionId) {
			return Optional.ofNullable(executions.get(executionId));
		}

		/** 미리보기 식별값으로 테스트 실행 기록을 조회합니다. */
		@Override
		public Optional<OrderExecutionResponse> findByPreviewId(String previewId) {
			return Optional.ofNullable(executionIdsByPreview.get(previewId))
					.flatMap(this::findById);
		}

		/** 예상 이전 상태인 실행만 새 상태와 결과 값으로 복사해 저장합니다. */
		private boolean 상태를_바꾼다(
				String executionId,
				OrderExecutionStatus expected,
				OrderExecutionStatus status,
				String brokerOrderId,
				OrderExecutionFailureType failureType,
				OffsetDateTime submittedAt,
				OffsetDateTime completedAt) {
			OrderExecutionResponse current = executions.get(executionId);
			if (current == null || current.status() != expected) {
				return false;
			}
			OffsetDateTime updatedAt = completedAt != null ? completedAt
					: submittedAt != null ? submittedAt : current.updatedAt();
			executions.put(executionId, new OrderExecutionResponse(
					current.executionId(), current.previewId(), current.clientOrderId(),
					current.brokerMode(), status, brokerOrderId, failureType,
					current.createdAt(), updatedAt,
					submittedAt != null ? submittedAt : current.submittedAt(),
					current.recoveryAttemptedAt(), completedAt));
			return true;
		}
	}

	/** 실제 네트워크 없이 준비한 현재가를 반환하는 테스트 대역입니다. */
	private static final class FakePriceClient extends TossPriceClient {

		private StockPriceResponse response;
		private int callCount;

		/** 부모의 네트워크 의존성 없이 테스트 대역을 만듭니다. */
		private FakePriceClient() {
			super(null, null);
		}

		/** 준비한 현재가를 반환하고 호출 횟수를 기록합니다. */
		@Override
		public StockPriceResponse getCurrentPrice(String symbol) {
			callCount++;
			return response;
		}
	}

	/** 실제 네트워크 없이 준비한 매수 가능 금액을 반환하는 테스트 대역입니다. */
	private static final class FakeBuyingPowerClient extends TossBuyingPowerClient {

		private BuyingPowerResponse response;
		private int callCount;

		/** 부모의 네트워크 의존성 없이 테스트 대역을 만듭니다. */
		private FakeBuyingPowerClient() {
			super(null, null);
		}

		/** 준비한 매수 가능 금액을 반환하고 호출 횟수를 기록합니다. */
		@Override
		public BuyingPowerResponse getBuyingPower(long accountSeq, String currency) {
			callCount++;
			return response;
		}
	}

	/** 실제 네트워크 없이 준비한 매도 가능 수량을 반환하는 테스트 대역입니다. */
	private static final class FakeSellableQuantityClient extends TossSellableQuantityClient {

		private SellableQuantityResponse response;
		private int callCount;

		/** 부모의 네트워크 의존성 없이 테스트 대역을 만듭니다. */
		private FakeSellableQuantityClient() {
			super(null, null);
		}

		/** 준비한 매도 가능 수량을 반환하고 호출 횟수를 기록합니다. */
		@Override
		public SellableQuantityResponse getSellableQuantity(long accountSeq, String symbol) {
			callCount++;
			return response;
		}
	}

	/** 실제 네트워크 없이 준비한 수수료를 반환하는 테스트 대역입니다. */
	private static final class FakeCommissionsClient extends TossCommissionsClient {

		private CommissionsResponse response;
		private int callCount;

		/** 부모의 네트워크 의존성 없이 테스트 대역을 만듭니다. */
		private FakeCommissionsClient() {
			super(null, null);
		}

		/** 준비한 수수료를 반환하고 호출 횟수를 기록합니다. */
		@Override
		public CommissionsResponse getCommissions(long accountSeq) {
			callCount++;
			return response;
		}
	}

	/** 실제 증권사 없이 성공·거절·불명 결과를 선택해 반환하는 제출 대역입니다. */
	private static final class FakeOrderSubmissionGateway implements OrderSubmissionGateway {

		private int callCount;
		private QuantityOrderSubmissionRequest lastRequest;
		private OrderSubmissionException failure;
		private RuntimeException availabilityFailure;
		private RuntimeException limitFailure;
		private BrokerOrderRiskSnapshot lastRiskSnapshot;

		/** 준비 단계에서 설정된 LIVE 안전 차단을 재현하거나 MOCK 사용 가능 상태를 유지합니다. */
		@Override
		public void requireSubmissionAvailable() {
			if (availabilityFailure != null) {
				throw availabilityFailure;
			}
		}

		/** 최종 계산 위험값을 기록하고 설정된 한도 차단을 실행 서비스에 전달합니다. */
		@Override
		public void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
			lastRiskSnapshot = riskSnapshot;
			if (limitFailure != null) {
				throw limitFailure;
			}
		}

		/** 전달된 주문을 기록하고 설정에 따라 모의 접수 또는 실패를 반환합니다. */
		@Override
		public OrderCreationResponse submitQuantityOrder(
				long accountSeq,
				QuantityOrderSubmissionRequest request) {
			callCount++;
			lastRequest = request;
			if (failure != null) {
				throw failure;
			}
			return new OrderCreationResponse("fake-" + request.clientOrderId(), request.clientOrderId());
		}

		/** 복구 테스트가 아닌 이 대역에서는 일반 제출과 같은 모의 결과를 반환합니다. */
		@Override
		public OrderCreationResponse recoverQuantityOrder(
				long accountSeq,
				QuantityOrderSubmissionRequest request) {
			return submitQuantityOrder(accountSeq, request);
		}

		/** 테스트 제출 경계가 모의 모드임을 반환합니다. */
		@Override
		public String mode() {
			return "MOCK";
		}
	}
}

package com.jusika.backend.amountorderexecution;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.amountorderpreview.AmountOrderPreviewResponse;
import com.jusika.backend.amountorderpreview.AmountOrderPreviewStore;
import com.jusika.backend.amountorderwindow.UsAmountOrderWindowResponse;
import com.jusika.backend.amountorderwindow.UsAmountOrderWindowService;
import com.jusika.backend.amountorderwindow.UsAmountOrderWindowStatus;
import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.exchangerate.ExchangeRateChangeType;
import com.jusika.backend.exchangerate.ExchangeRateResponse;
import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.marketinfo.TossExchangeRateClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;

/**
 * 실제 토스증권 주문 없이 금액 주문의 최종 재검증과 MOCK 실행 상태를 검사합니다.
 */
class AmountOrderExecutionServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-09-08T01:00:00Z");
	private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.ofHours(9));

	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingSubmissionGateway submissionGateway;
	private FixedWindowService windowService;
	private FixedPriceClient priceClient;
	private FixedBuyingPowerClient buyingPowerClient;
	private FixedCommissionsClient commissionsClient;
	private FixedExchangeRateClient exchangeRateClient;
	private AmountOrderExecutionService service;

	/**
	 * 각 테스트에 고정 시각과 네트워크를 사용하지 않는 가짜 의존성을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_금액_주문_실행_서비스를_준비한다() {
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		submissionGateway = new RecordingSubmissionGateway();
		windowService = new FixedWindowService();
		priceClient = new FixedPriceClient();
		buyingPowerClient = new FixedBuyingPowerClient();
		commissionsClient = new FixedCommissionsClient();
		exchangeRateClient = new FixedExchangeRateClient();
		최종_조회값을_정상으로_준비한다();
		service = new AmountOrderExecutionService(
				previewStore,
				executionStore,
				submissionGateway,
				new AmountOrderRequestFingerprint(),
				windowService,
				priceClient,
				buyingPowerClient,
				commissionsClient,
				exchangeRateClient,
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	/** 승인된 금액 주문을 모두 다시 확인한 뒤 MOCK 접수하는지 검사합니다. */
	@Test
	@DisplayName("승인된 금액 주문을 최종 재검증하고 MOCK 접수한다")
	void 승인된_금액_주문을_최종_재검증하고_MOCK_접수한다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);

		AmountOrderExecutionResponse result = service.executeApprovedPreview(preview.previewId());

		assertThat(result.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(result.brokerMode()).isEqualTo("MOCK");
		assertThat(result.brokerOrderId()).startsWith("fake-amount-");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
		assertThat(submissionGateway.callCount).isOne();
		assertThat(submissionGateway.lastAccountSeq).isEqualTo(ACCOUNT_SEQ);
		assertThat(submissionGateway.lastRequest.symbol()).isEqualTo("AAPL");
		assertThat(submissionGateway.lastRequest.orderAmount()).isEqualByComparingTo("100");
		assertThat(windowService.callCount).isOne();
		assertThat(priceClient.callCount).isOne();
		assertThat(buyingPowerClient.callCount).isOne();
		assertThat(commissionsClient.callCount).isOne();
		assertThat(exchangeRateClient.callCount).isOne();
	}

	/** 같은 미리보기를 두 번 실행해도 제출 경계는 한 번만 호출되는지 검사합니다. */
	@Test
	@DisplayName("같은 금액 주문 미리보기의 중복 실행을 차단한다")
	void 같은_금액_주문_미리보기의_중복_실행을_차단한다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);
		service.executeApprovedPreview(preview.previewId());

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(AmountOrderExecutionConflictException.class)
				.hasMessage("승인된 금액 주문 미리보기만 실행할 수 있습니다.");
		assertThat(submissionGateway.callCount).isOne();
	}

	/** 금액 주문 접수 시간이 아니면 금융 조회와 제출을 시작하지 않는지 검사합니다. */
	@Test
	@DisplayName("미국 금액 주문 접수 시간이 아니면 실행하지 않는다")
	void 미국_금액_주문_접수_시간이_아니면_실행하지_않는다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);
		windowService.response = new UsAmountOrderWindowResponse(
				LocalDate.of(2026, 9, 7), NOW, true,
				NOW.minusHours(1), NOW.plusHours(5), NOW.plusHours(4),
				false, UsAmountOrderWindowStatus.AFTER_CUTOFF);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(AmountOrderExecutionValidationException.class)
				.hasMessage("현재는 미국 주식 금액 주문 접수 시간이 아닙니다.");
		assertThat(priceClient.callCount).isZero();
		assertThat(submissionGateway.callCount).isZero();
		assertThat(executionStore.findByPreviewId(preview.previewId())).isEmpty();
	}

	/** 승인 뒤 달러 현금이 줄었으면 실행권을 만들지 않는지 검사합니다. */
	@Test
	@DisplayName("최종 달러 매수 가능 금액이 부족하면 실행하지 않는다")
	void 최종_달러_매수_가능_금액이_부족하면_실행하지_않는다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);
		buyingPowerClient.response = new BuyingPowerResponse(ACCOUNT_SEQ, "USD", new BigDecimal("100"));

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(AmountOrderExecutionValidationException.class)
				.hasMessage("최종 달러 매수 가능 금액이 부족합니다. 새 미리보기를 만들어 주세요.");
		assertThat(submissionGateway.callCount).isZero();
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
	}

	/** 환율 상승으로 1억원 이상이 되면 이전의 일반 승인을 재사용하지 않는지 검사합니다. */
	@Test
	@DisplayName("환율 상승으로 고액 주문이 되면 새 미리보기를 요구한다")
	void 환율_상승으로_고액_주문이_되면_새_미리보기를_요구한다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);
		exchangeRateClient.response = 환율_응답을_만든다("1000000");

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(AmountOrderExecutionValidationException.class)
				.hasMessage("최종 원화 환산액이 1억원 이상이므로 새 미리보기에서 다시 확인해야 합니다.");
		assertThat(submissionGateway.callCount).isZero();
	}

	/** 승인된 고정 계산값이 모순되면 외부 조회 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("승인한 금액 주문의 고정 계산값이 바뀌면 실행하지 않는다")
	void 승인한_금액_주문의_고정_계산값이_바뀌면_실행하지_않는다() {
		AmountOrderPreviewResponse valid = 승인된_미리보기를_저장한다(false);
		AmountOrderPreviewResponse changed = 복사하며_예상_총비용을_바꾼다(valid, new BigDecimal("999"));
		previewStore.save(changed);

		assertThatThrownBy(() -> service.executeApprovedPreview(valid.previewId()))
				.isInstanceOf(AmountOrderExecutionValidationException.class)
				.hasMessageContaining("고정값이 올바르지 않습니다");
		assertThat(windowService.callCount).isZero();
		assertThat(submissionGateway.callCount).isZero();
	}

	/** 제출 경계가 확정 거절을 알리면 거절 상태를 저장하는지 검사합니다. */
	@Test
	@DisplayName("MOCK 제출의 확정 거절을 실행 기록에 남긴다")
	void MOCK_제출의_확정_거절을_실행_기록에_남긴다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);
		submissionGateway.failure = new OrderSubmissionException("테스트 거절", false);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(AmountOrderExecutionSubmissionException.class)
				.hasMessage("증권사가 금액 주문을 거절했습니다.");
		AmountOrderExecutionResponse stored = executionStore
				.findByPreviewId(preview.previewId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.REJECTED);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.BROKER_REJECTED);
		assertThat(stored.completedAt()).isNotNull();
	}

	/** 제출 결과가 불명확하면 자동 재시도하지 않는 UNKNOWN 상태로 남기는지 검사합니다. */
	@Test
	@DisplayName("MOCK 제출 결과가 불명확하면 UNKNOWN 상태로 남긴다")
	void MOCK_제출_결과가_불명확하면_UNKNOWN_상태로_남긴다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);
		submissionGateway.failure = new OrderSubmissionException("테스트 결과 불명", true);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(AmountOrderExecutionSubmissionException.class)
				.hasMessage("금액 주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 주문하지 마세요.");
		AmountOrderExecutionResponse stored = executionStore
				.findByPreviewId(preview.previewId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(stored.completedAt()).isNull();
		assertThat(submissionGateway.callCount).isOne();
	}

	/** 제출 경계가 MOCK이 아니면 요청 메서드를 호출하지 않는지 검사합니다. */
	@Test
	@DisplayName("금액 주문 실행 경계가 MOCK이 아니면 실행을 차단한다")
	void 금액_주문_실행_경계가_MOCK이_아니면_실행을_차단한다() {
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(false);
		submissionGateway.mode = "LIVE";

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(AmountOrderExecutionSubmissionException.class)
				.hasMessage("금액 주문 실행은 현재 MOCK 모드에서만 허용됩니다.");
		assertThat(submissionGateway.callCount).isZero();
		assertThat(executionStore.findByPreviewId(preview.previewId())).isEmpty();
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.APPROVED);
	}

	/** 유효시간이 끝난 승인 미리보기는 장 일정 조회 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("유효시간이 지난 승인 미리보기는 실행하지 않는다")
	void 유효시간이_지난_승인_미리보기는_실행하지_않는다() {
		AmountOrderPreviewResponse preview = 미리보기를_만든다(
				false, NOW.minusMinutes(3), NOW);
		previewStore.save(preview);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(com.jusika.backend.amountorderpreview.AmountOrderPreviewExpiredException.class);
		assertThat(windowService.callCount).isZero();
		assertThat(submissionGateway.callCount).isZero();
	}

	/** 정상 실행에 필요한 최신 조회 응답을 준비합니다. */
	private void 최종_조회값을_정상으로_준비한다() {
		windowService.response = new UsAmountOrderWindowResponse(
				LocalDate.of(2026, 9, 7), NOW, true,
				NOW.minusHours(1), NOW.plusHours(5), NOW.plusHours(4),
				true, UsAmountOrderWindowStatus.OPEN);
		priceClient.response = new StockPriceResponse("AAPL", new BigDecimal("210"), "USD", NOW);
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "USD", new BigDecimal("1000"));
		commissionsClient.response = new CommissionsResponse(
				ACCOUNT_SEQ,
				List.of(new CommissionItem(
						"US", new BigDecimal("0.001"), LocalDate.of(2026, 1, 1), null)));
		exchangeRateClient.response = 환율_응답을_만든다("1400");
	}

	/** 승인된 정상 금액 주문 미리보기를 메모리에 저장합니다. */
	private AmountOrderPreviewResponse 승인된_미리보기를_저장한다(boolean highValue) {
		AmountOrderPreviewResponse preview = 미리보기를_만든다(
				highValue, NOW.minusMinutes(1), NOW.plusMinutes(1));
		return previewStore.save(preview);
	}

	/** 지정 시각과 고액 여부에 맞는 일관된 금액 주문 미리보기를 만듭니다. */
	private AmountOrderPreviewResponse 미리보기를_만든다(
			boolean highValue,
			OffsetDateTime createdAt,
			OffsetDateTime expiresAt) {
		BigDecimal orderAmount = highValue ? new BigDecimal("100000") : new BigDecimal("100");
		BigDecimal commission = orderAmount.multiply(new BigDecimal("0.001"));
		BigDecimal krwAmount = orderAmount.multiply(new BigDecimal("1400"));
		return new AmountOrderPreviewResponse(
				java.util.UUID.randomUUID().toString(), createdAt, expiresAt, ACCOUNT_SEQ,
				"AAPL", OrderSide.BUY, OrderType.MARKET, orderAmount, "USD", "US",
				new BigDecimal("200"), orderAmount.divide(new BigDecimal("200")),
				new BigDecimal("0.001"), commission, orderAmount.add(commission),
				new BigDecimal("1400"), createdAt.minusMinutes(1), createdAt.plusMinutes(5),
				krwAmount, highValue, true, OrderPreviewStatus.APPROVED, createdAt);
	}

	/** 환율만 지정해 실행 시각에 유효한 USD→KRW 응답을 만듭니다. */
	private ExchangeRateResponse 환율_응답을_만든다(String rate) {
		return new ExchangeRateResponse(
				"USD", "KRW", new BigDecimal(rate), new BigDecimal(rate), BigDecimal.ZERO,
				ExchangeRateChangeType.EQUAL, NOW.minusMinutes(1), NOW.plusMinutes(1));
	}

	/** 원본 미리보기의 예상 총비용만 바꾼 불일치 응답을 만듭니다. */
	private AmountOrderPreviewResponse 복사하며_예상_총비용을_바꾼다(
			AmountOrderPreviewResponse preview,
			BigDecimal estimatedTotalCost) {
		return new AmountOrderPreviewResponse(
				preview.previewId(), preview.createdAt(), preview.expiresAt(), preview.accountSeq(),
				preview.symbol(), preview.side(), preview.orderType(), preview.orderAmount(),
				preview.currency(), preview.marketCountry(), preview.referencePrice(),
				preview.estimatedQuantity(), preview.commissionRate(), preview.estimatedCommission(),
				estimatedTotalCost, preview.exchangeRate(), preview.exchangeRateValidFrom(),
				preview.exchangeRateValidUntil(), preview.estimatedOrderAmountKrw(),
				preview.requiresHighValueConfirmation(), preview.orderReady(), preview.status(),
				preview.approvedAt());
	}

	/** 금액 주문 미리보기 상태를 메모리에서 원자적으로 바꾸는 테스트 저장소입니다. */
	private static final class MemoryPreviewStore implements AmountOrderPreviewStore {

		private final Map<String, AmountOrderPreviewResponse> previews = new HashMap<>();

		/** 미리보기를 메모리에 저장합니다. */
		@Override
		public AmountOrderPreviewResponse save(AmountOrderPreviewResponse preview) {
			previews.put(preview.previewId(), preview);
			return preview;
		}

		/** 이 실행 서비스 테스트에서는 승인 전이를 사용하지 않습니다. */
		@Override
		public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
			return false;
		}

		/** 이 실행 서비스 테스트에서는 만료 전이를 사용하지 않습니다. */
		@Override
		public boolean expirePending(String previewId, OffsetDateTime now) {
			return false;
		}

		/** 유효한 승인 미리보기만 한 번 사용 완료 상태로 바꿉니다. */
		@Override
		public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
			AmountOrderPreviewResponse preview = previews.get(previewId);
			if (preview == null
					|| preview.status() != OrderPreviewStatus.APPROVED
					|| !preview.expiresAt().isAfter(consumedAt)) {
				return false;
			}
			previews.put(previewId, 상태를_바꾼다(preview, OrderPreviewStatus.CONSUMED));
			return true;
		}

		/** 식별값에 해당하는 메모리 미리보기를 반환합니다. */
		@Override
		public Optional<AmountOrderPreviewResponse> findById(String previewId) {
			return Optional.ofNullable(previews.get(previewId));
		}

		/** 금융 계산값을 유지하고 상태만 바꾼 미리보기 사본을 만듭니다. */
		private AmountOrderPreviewResponse 상태를_바꾼다(
				AmountOrderPreviewResponse preview,
				OrderPreviewStatus status) {
			return new AmountOrderPreviewResponse(
					preview.previewId(), preview.createdAt(), preview.expiresAt(), preview.accountSeq(),
					preview.symbol(), preview.side(), preview.orderType(), preview.orderAmount(),
					preview.currency(), preview.marketCountry(), preview.referencePrice(),
					preview.estimatedQuantity(), preview.commissionRate(), preview.estimatedCommission(),
					preview.estimatedTotalCost(), preview.exchangeRate(), preview.exchangeRateValidFrom(),
					preview.exchangeRateValidUntil(), preview.estimatedOrderAmountKrw(),
					preview.requiresHighValueConfirmation(), preview.orderReady(), status,
					preview.approvedAt());
		}
	}

	/** 금액 주문 실행 상태 전이를 메모리에서 기록하는 테스트 저장소입니다. */
	private static final class MemoryExecutionStore implements AmountOrderExecutionStore {

		private AmountOrderExecutionResponse execution;
		private String requestFingerprint;

		/** 같은 미리보기의 첫 실행 준비 기록만 저장합니다. */
		@Override
		public boolean claim(AmountOrderExecutionResponse candidate, String requestFingerprint) {
			if (execution != null) {
				return false;
			}
			execution = candidate;
			this.requestFingerprint = requestFingerprint;
			return true;
		}

		/** 준비 상태를 제출 중으로 바꿉니다. */
		@Override
		public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
			if (!matches(executionId, OrderExecutionStatus.PREPARED)) {
				return false;
			}
			execution = copy(OrderExecutionStatus.SUBMITTING, null, null, submittedAt, null);
			return true;
		}

		/** 준비 상태를 내부 오류 거절로 바꿉니다. */
		@Override
		public boolean markPreparationFailed(String executionId, OffsetDateTime failedAt) {
			if (!matches(executionId, OrderExecutionStatus.PREPARED)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.INTERNAL_STATE,
					null,
					execution.submittedAt(),
					failedAt);
			return true;
		}

		/** 제출 중 상태를 접수로 바꿉니다. */
		@Override
		public boolean markAccepted(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			if (!matches(executionId, OrderExecutionStatus.SUBMITTING)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.ACCEPTED, null, brokerOrderId,
					execution.submittedAt(), completedAt);
			return true;
		}

		/** 제출 중 상태를 확정 거절로 바꿉니다. */
		@Override
		public boolean markRejected(String executionId, OffsetDateTime failedAt) {
			if (!matches(executionId, OrderExecutionStatus.SUBMITTING)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.BROKER_REJECTED,
					null,
					execution.submittedAt(),
					failedAt);
			return true;
		}

		/** 제출 중 상태를 결과 불명으로 바꿉니다. */
		@Override
		public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
			if (!matches(executionId, OrderExecutionStatus.SUBMITTING)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.UNKNOWN,
					OrderExecutionFailureType.SUBMISSION_UNKNOWN,
					null,
					execution.submittedAt(),
					null);
			return true;
		}

		/** 저장된 실행 기록과 최초 요청 지문을 복구 후보로 반환합니다. */
		@Override
		public Optional<AmountOrderExecutionRecoveryCandidate> findRecoveryCandidateById(
				String executionId) {
			return findById(executionId)
					.map(value -> new AmountOrderExecutionRecoveryCandidate(value, requestFingerprint));
		}

		/** 이 실행 테스트에서는 안전 복구 선점을 수행하지 않습니다. */
		@Override
		public boolean claimRecovery(
				String executionId,
				OffsetDateTime submittedAfter,
				OffsetDateTime recoveryStartedAt) {
			return false;
		}

		/** 이 실행 테스트에서는 복구 접수 전이를 수행하지 않습니다. */
		@Override
		public boolean markRecovered(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			return false;
		}

		/** 이 실행 테스트에서는 복구 결과 불명 전이를 수행하지 않습니다. */
		@Override
		public boolean markRecoveryUnknown(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 실행 식별값으로 메모리 실행 기록을 조회합니다. */
		@Override
		public Optional<AmountOrderExecutionResponse> findById(String executionId) {
			return execution != null && execution.executionId().equals(executionId)
					? Optional.of(execution) : Optional.empty();
		}

		/** 미리보기 식별값으로 메모리 실행 기록을 조회합니다. */
		@Override
		public Optional<AmountOrderExecutionResponse> findByPreviewId(String previewId) {
			return execution != null && execution.previewId().equals(previewId)
					? Optional.of(execution) : Optional.empty();
		}

		/** 현재 실행 식별값과 예상 상태가 일치하는지 확인합니다. */
		private boolean matches(String executionId, OrderExecutionStatus expectedStatus) {
			return execution != null
					&& execution.executionId().equals(executionId)
					&& execution.status() == expectedStatus;
		}

		/** 식별값과 생성 시각을 유지한 채 상태 필드만 복사합니다. */
		private AmountOrderExecutionResponse copy(
				OrderExecutionStatus status,
				OrderExecutionFailureType failureType,
				String brokerOrderId,
				OffsetDateTime submittedAt,
				OffsetDateTime completedAt) {
			OffsetDateTime updatedAt = completedAt != null ? completedAt
					: submittedAt != null ? submittedAt : execution.updatedAt();
			return new AmountOrderExecutionResponse(
					execution.executionId(), execution.previewId(), execution.clientOrderId(),
					execution.brokerMode(), status, brokerOrderId, failureType,
					execution.createdAt(), updatedAt, submittedAt,
					execution.recoveryAttemptedAt(), completedAt);
		}
	}

	/** 준비한 금액 주문 시간 판정 결과를 반환하는 테스트 대역입니다. */
	private static final class FixedWindowService extends UsAmountOrderWindowService {

		private UsAmountOrderWindowResponse response;
		private int callCount;

		/** 실제 캘린더 클라이언트 없이 부모 서비스를 초기화합니다. */
		private FixedWindowService() {
			super(null, Clock.systemUTC());
		}

		/** 준비한 시간 판정 결과를 반환하고 호출 횟수를 기록합니다. */
		@Override
		public UsAmountOrderWindowResponse checkCurrentWindow() {
			callCount++;
			return response;
		}
	}

	/** 준비한 미국 현재가를 반환하는 테스트 대역입니다. */
	private static final class FixedPriceClient extends TossPriceClient {

		private StockPriceResponse response;
		private int callCount;

		/** 실제 REST 구성 없이 부모 클라이언트를 초기화합니다. */
		private FixedPriceClient() {
			super(null, null);
		}

		/** 준비한 현재가를 반환하고 호출 횟수를 기록합니다. */
		@Override
		public StockPriceResponse getCurrentPrice(String symbol) {
			callCount++;
			return response;
		}
	}

	/** 준비한 달러 매수 가능 금액을 반환하는 테스트 대역입니다. */
	private static final class FixedBuyingPowerClient extends TossBuyingPowerClient {

		private BuyingPowerResponse response;
		private int callCount;

		/** 실제 REST 구성 없이 부모 클라이언트를 초기화합니다. */
		private FixedBuyingPowerClient() {
			super(null, null);
		}

		/** 준비한 매수 가능 금액을 반환하고 호출 횟수를 기록합니다. */
		@Override
		public BuyingPowerResponse getBuyingPower(long accountSeq, String currency) {
			callCount++;
			return response;
		}
	}

	/** 준비한 미국 수수료를 반환하는 테스트 대역입니다. */
	private static final class FixedCommissionsClient extends TossCommissionsClient {

		private CommissionsResponse response;
		private int callCount;

		/** 실제 REST 구성 없이 부모 클라이언트를 초기화합니다. */
		private FixedCommissionsClient() {
			super(null, null);
		}

		/** 준비한 수수료를 반환하고 호출 횟수를 기록합니다. */
		@Override
		public CommissionsResponse getCommissions(long accountSeq) {
			callCount++;
			return response;
		}
	}

	/** 준비한 달러 원화 환율을 반환하는 테스트 대역입니다. */
	private static final class FixedExchangeRateClient extends TossExchangeRateClient {

		private ExchangeRateResponse response;
		private int callCount;

		/** 실제 REST 구성 없이 부모 클라이언트를 초기화합니다. */
		private FixedExchangeRateClient() {
			super(null, null);
		}

		/** 준비한 환율을 반환하고 호출 횟수를 기록합니다. */
		@Override
		public ExchangeRateResponse getExchangeRate(
				String baseCurrency,
				String quoteCurrency,
				OffsetDateTime dateTime) {
			callCount++;
			return response;
		}
	}

	/** 실제 증권사 없이 성공·거절·불명 결과를 기록하는 제출 대역입니다. */
	private static final class RecordingSubmissionGateway implements AmountOrderSubmissionGateway {

		private int callCount;
		private long lastAccountSeq;
		private AmountOrderSubmissionRequest lastRequest;
		private OrderSubmissionException failure;
		private String mode = "MOCK";

		/** 전달된 금액 주문을 기록하고 준비한 성공 또는 실패를 반환합니다. */
		@Override
		public OrderCreationResponse submitAmountOrder(
				long accountSeq,
				AmountOrderSubmissionRequest request) {
			callCount++;
			lastAccountSeq = accountSeq;
			lastRequest = request;
			if (failure != null) {
				throw failure;
			}
			return new OrderCreationResponse(
					"fake-amount-" + request.clientOrderId(), request.clientOrderId());
		}

		/** 이 실행 테스트에서는 복구 경계를 호출하지 않습니다. */
		@Override
		public OrderCreationResponse recoverAmountOrder(
				long accountSeq,
				AmountOrderSubmissionRequest request) {
			throw new AssertionError("새 금액 주문 실행 중 복구 경계를 호출하면 안 됩니다.");
		}

		/** 테스트 제출 경계가 모의 모드임을 반환합니다. */
		@Override
		public String mode() {
			return mode;
		}
	}
}

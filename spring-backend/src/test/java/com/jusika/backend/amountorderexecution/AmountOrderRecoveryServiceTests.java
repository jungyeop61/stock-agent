package com.jusika.backend.amountorderexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.amountorderpreview.AmountOrderPreviewResponse;
import com.jusika.backend.amountorderpreview.AmountOrderPreviewStore;
import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 실제 토스증권 호출 없이 금액 주문 UNKNOWN 상태의 일회성 안전 복구를 검사합니다.
 */
class AmountOrderRecoveryServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-09-08T01:10:00Z");
	private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.ofHours(9));

	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingRecoveryGateway recoveryGateway;
	private AmountOrderRequestFingerprint requestFingerprint;
	private AmountOrderRecoveryService service;

	/** 각 테스트에 고정 시각과 네트워크를 사용하지 않는 복구 의존성을 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_금액_주문_복구_서비스를_준비한다() {
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		recoveryGateway = new RecordingRecoveryGateway();
		requestFingerprint = new AmountOrderRequestFingerprint();
		service = new AmountOrderRecoveryService(
				previewStore,
				executionStore,
				recoveryGateway,
				requestFingerprint,
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	/** 최초 요청과 같은 내용으로 UNKNOWN 금액 주문을 한 번 복구하는지 검사합니다. */
	@Test
	@DisplayName("UNKNOWN 금액 주문을 최초 요청과 같은 내용으로 한 번 복구한다")
	void UNKNOWN_금액_주문을_최초_요청과_같은_내용으로_한_번_복구한다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));

		AmountOrderExecutionResponse recovered = service.recoverUnknownExecution(unknown.executionId());

		assertThat(recovered.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(recovered.failureType()).isNull();
		assertThat(recovered.recoveryAttemptedAt()).isEqualTo(NOW);
		assertThat(recovered.completedAt()).isEqualTo(NOW);
		assertThat(recoveryGateway.recoveryCallCount).isOne();
		assertThat(recoveryGateway.lastAccountSeq).isEqualTo(ACCOUNT_SEQ);
		assertThat(recoveryGateway.lastRequest.clientOrderId()).isEqualTo(unknown.clientOrderId());
		assertThat(recoveryGateway.lastRequest.symbol()).isEqualTo("AAPL");
		assertThat(recoveryGateway.lastRequest.orderAmount()).isEqualByComparingTo("100");
		assertThat(recoveryGateway.lastRequest.confirmHighValueOrder()).isFalse();
		assertThat(recoveryGateway.submitCallCount).isZero();
	}

	/** LIVE 가용성 검사가 실패하면 복구권과 결과 불명 상태를 그대로 유지하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전정책 차단은 금액 주문 복구권 확보 전에 적용된다")
	void LIVE_안전정책_차단은_금액_주문_복구권_확보_전에_적용된다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));
		recoveryGateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE 금액 주문 복구 차단");

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE 금액 주문 복구 차단");
		AmountOrderExecutionResponse stored = executionStore.findById(
				unknown.executionId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.recoveryAttemptedAt()).isNull();
		assertThat(recoveryGateway.recoveryCallCount).isZero();
	}

	/** 성공한 복구를 다시 요청해도 복구 경계가 두 번 호출되지 않는지 검사합니다. */
	@Test
	@DisplayName("이미 복구한 금액 주문의 중복 복구를 차단한다")
	void 이미_복구한_금액_주문의_중복_복구를_차단한다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));
		service.recoverUnknownExecution(unknown.executionId());

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderExecutionConflictException.class)
				.hasMessageContaining("아직 복구하지 않은 금액 주문만");
		assertThat(recoveryGateway.recoveryCallCount).isOne();
	}

	/** 최초 제출 후 정확히 10분이 되면 복구 경계를 호출하지 않는지 검사합니다. */
	@Test
	@DisplayName("정확히 10분이 지난 금액 주문 복구를 만료 처리한다")
	void 정확히_10분이_지난_금액_주문_복구를_만료_처리한다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(10));

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderRecoveryExpiredException.class)
				.hasMessageContaining("10분이 지났습니다");
		assertThat(recoveryGateway.recoveryCallCount).isZero();
	}

	/** 저장된 최초 요청 지문이 다르면 복구권을 만들지 않는지 검사합니다. */
	@Test
	@DisplayName("최초 금액 주문 요청 지문이 다르면 복구를 차단한다")
	void 최초_금액_주문_요청_지문이_다르면_복구를_차단한다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));
		executionStore.requestFingerprint = "f".repeat(64);

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderExecutionConflictException.class)
				.hasMessageContaining("동일성을 확인할 수 없어");
		assertThat(recoveryGateway.recoveryCallCount).isZero();
		assertThat(executionStore.execution.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
	}

	/** 최초 실행이나 현재 경계가 MOCK이 아니면 복구하지 않는지 검사합니다. */
	@Test
	@DisplayName("MOCK 모드가 아닌 금액 주문 복구를 차단한다")
	void MOCK_모드가_아닌_금액_주문_복구를_차단한다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));
		recoveryGateway.mode = "LIVE";

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderExecutionConflictException.class)
				.hasMessageContaining("현재 실행 모드가 달라");
		assertThat(recoveryGateway.recoveryCallCount).isZero();
	}

	/** 복구 경계 결과가 불명확하면 재복구 불가 실패 분류를 저장하는지 검사합니다. */
	@Test
	@DisplayName("금액 주문 복구 결과가 불명확하면 재복구를 금지한다")
	void 금액_주문_복구_결과가_불명확하면_재복구를_금지한다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));
		recoveryGateway.failure = new OrderSubmissionException("테스트 복구 결과 불명", true);

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderExecutionSubmissionException.class)
				.hasMessageContaining("복구 결과도 확인할 수 없습니다");
		assertThat(executionStore.execution.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(executionStore.execution.failureType())
				.isEqualTo(OrderExecutionFailureType.RECOVERY_UNKNOWN);
		assertThat(executionStore.execution.recoveryAttemptedAt()).isEqualTo(NOW);

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderExecutionConflictException.class);
		assertThat(recoveryGateway.recoveryCallCount).isOne();
	}

	/** 복구 응답의 멱등성 식별값이 다르면 결과 불명으로 보수 처리하는지 검사합니다. */
	@Test
	@DisplayName("금액 주문 복구 응답이 원요청과 다르면 결과 불명으로 저장한다")
	void 금액_주문_복구_응답이_원요청과_다르면_결과_불명으로_저장한다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));
		recoveryGateway.response = new OrderCreationResponse(
				"fake-amount-order", UUID.randomUUID().toString());

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderExecutionSubmissionException.class);
		assertThat(executionStore.execution.failureType())
				.isEqualTo(OrderExecutionFailureType.RECOVERY_UNKNOWN);
		assertThat(executionStore.execution.completedAt()).isNull();
	}

	/** 결과 불명이 아닌 접수 상태는 복구 대상으로 삼지 않는지 검사합니다. */
	@Test
	@DisplayName("결과 불명 상태가 아닌 금액 주문은 복구하지 않는다")
	void 결과_불명_상태가_아닌_금액_주문은_복구하지_않는다() {
		AmountOrderExecutionResponse unknown = 결과_불명_실행을_저장한다(NOW.minusMinutes(1));
		executionStore.execution = 실행_상태를_복사한다(
				unknown, OrderExecutionStatus.ACCEPTED, null, "fake-amount-order", null, NOW);

		assertThatThrownBy(() -> service.recoverUnknownExecution(unknown.executionId()))
				.isInstanceOf(AmountOrderExecutionConflictException.class)
				.hasMessageContaining("결과가 불명확하고");
		assertThat(recoveryGateway.recoveryCallCount).isZero();
	}

	/** 복구 테스트에 사용할 최초 미리보기와 결과 불명 실행, 정확한 지문을 저장합니다. */
	private AmountOrderExecutionResponse 결과_불명_실행을_저장한다(OffsetDateTime submittedAt) {
		AmountOrderPreviewResponse preview = previewStore.save(미리보기를_만든다());
		AmountOrderExecutionResponse execution = new AmountOrderExecutionResponse(
				UUID.randomUUID().toString(), preview.previewId(), UUID.randomUUID().toString(),
				"MOCK", OrderExecutionStatus.UNKNOWN, null,
				OrderExecutionFailureType.SUBMISSION_UNKNOWN,
				submittedAt.minusSeconds(1), submittedAt, submittedAt, null, null);
		AmountOrderSubmissionRequest request = new AmountOrderSubmissionRequest(
				execution.clientOrderId(), preview.symbol(), preview.side(), preview.orderAmount(),
				preview.requiresHighValueConfirmation());
		executionStore.save(
				execution, requestFingerprint.calculate(preview.accountSeq(), request));
		return execution;
	}

	/** 최초 금액 주문 본문을 재구성할 변경 불가 미리보기를 만듭니다. */
	private AmountOrderPreviewResponse 미리보기를_만든다() {
		return new AmountOrderPreviewResponse(
				UUID.randomUUID().toString(), NOW.minusMinutes(2), NOW, ACCOUNT_SEQ,
				"AAPL", OrderSide.BUY, OrderType.MARKET, new BigDecimal("100"), "USD", "US",
				new BigDecimal("200"), new BigDecimal("0.5"), new BigDecimal("0.001"),
				new BigDecimal("0.1"), new BigDecimal("100.1"), new BigDecimal("1400"),
				NOW.minusMinutes(3), NOW.plusMinutes(1), new BigDecimal("140000"),
				false, true, OrderPreviewStatus.CONSUMED, NOW.minusMinutes(1));
	}

	/** 실행 기록의 식별값과 최초 시각을 유지하며 복구 상태 필드만 바꿉니다. */
	private AmountOrderExecutionResponse 실행_상태를_복사한다(
			AmountOrderExecutionResponse source,
			OrderExecutionStatus status,
			OrderExecutionFailureType failureType,
			String brokerOrderId,
			OffsetDateTime recoveryAttemptedAt,
			OffsetDateTime completedAt) {
		return new AmountOrderExecutionResponse(
				source.executionId(), source.previewId(), source.clientOrderId(), source.brokerMode(),
				status, brokerOrderId, failureType, source.createdAt(), NOW, source.submittedAt(),
				recoveryAttemptedAt, completedAt);
	}

	/** 금액 주문 미리보기를 메모리에 보관하는 테스트 저장소입니다. */
	private static final class MemoryPreviewStore implements AmountOrderPreviewStore {

		private final Map<String, AmountOrderPreviewResponse> previews = new HashMap<>();

		/** 금액 주문 미리보기를 메모리에 저장합니다. */
		@Override
		public AmountOrderPreviewResponse save(AmountOrderPreviewResponse preview) {
			previews.put(preview.previewId(), preview);
			return preview;
		}

		/** 복구 테스트에서는 승인 상태 전이를 사용하지 않습니다. */
		@Override
		public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
			return false;
		}

		/** 복구 테스트에서는 만료 상태 전이를 사용하지 않습니다. */
		@Override
		public boolean expirePending(String previewId, OffsetDateTime now) {
			return false;
		}

		/** 복구 테스트에서는 사용 완료 상태 전이를 사용하지 않습니다. */
		@Override
		public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
			return false;
		}

		/** 식별값에 해당하는 금액 주문 미리보기를 반환합니다. */
		@Override
		public Optional<AmountOrderPreviewResponse> findById(String previewId) {
			return Optional.ofNullable(previews.get(previewId));
		}
	}

	/** 금액 주문 복구 상태와 최초 지문을 메모리에 보관하는 테스트 저장소입니다. */
	private static final class MemoryExecutionStore implements AmountOrderExecutionStore {

		private AmountOrderExecutionResponse execution;
		private String requestFingerprint;

		/** 테스트가 준비한 결과 불명 실행과 지문을 저장합니다. */
		private void save(AmountOrderExecutionResponse execution, String requestFingerprint) {
			this.execution = execution;
			this.requestFingerprint = requestFingerprint;
		}

		/** 복구 테스트에서는 새 실행권 생성을 사용하지 않습니다. */
		@Override
		public boolean claim(AmountOrderExecutionResponse candidate, String fingerprint) {
			return false;
		}

		/** 복구 테스트에서는 새 제출 전이를 사용하지 않습니다. */
		@Override
		public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
			return false;
		}

		/** 복구 테스트에서는 준비 실패 전이를 사용하지 않습니다. */
		@Override
		public boolean markPreparationFailed(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 복구 테스트에서는 최초 접수 전이를 사용하지 않습니다. */
		@Override
		public boolean markAccepted(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			return false;
		}

		/** 복구 테스트에서는 최초 거절 전이를 사용하지 않습니다. */
		@Override
		public boolean markRejected(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 복구 테스트에서는 최초 결과 불명 전이를 사용하지 않습니다. */
		@Override
		public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 저장된 실행과 비공개 지문을 복구 후보로 반환합니다. */
		@Override
		public Optional<AmountOrderExecutionRecoveryCandidate> findRecoveryCandidateById(
				String executionId) {
			return findById(executionId)
					.map(value -> new AmountOrderExecutionRecoveryCandidate(value, requestFingerprint));
		}

		/** 조건을 만족하는 최초 UNKNOWN 실행만 복구 중으로 선점합니다. */
		@Override
		public boolean claimRecovery(
				String executionId,
				OffsetDateTime submittedAfter,
				OffsetDateTime recoveryStartedAt) {
			if (!matches(executionId, OrderExecutionStatus.UNKNOWN)
					|| execution.failureType() != OrderExecutionFailureType.SUBMISSION_UNKNOWN
					|| execution.recoveryAttemptedAt() != null
					|| execution.submittedAt() == null
					|| !execution.submittedAt().isAfter(submittedAfter)
					|| execution.submittedAt().isAfter(recoveryStartedAt)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.RECOVERING,
					execution.failureType(), null, recoveryStartedAt, null, recoveryStartedAt);
			return true;
		}

		/** 복구 중 실행을 회수한 주문번호와 함께 접수 상태로 바꿉니다. */
		@Override
		public boolean markRecovered(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			if (!matches(executionId, OrderExecutionStatus.RECOVERING)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.ACCEPTED, null, brokerOrderId,
					execution.recoveryAttemptedAt(), completedAt, completedAt);
			return true;
		}

		/** 복구 중 실행을 재복구할 수 없는 결과 불명 상태로 바꿉니다. */
		@Override
		public boolean markRecoveryUnknown(String executionId, OffsetDateTime failedAt) {
			if (!matches(executionId, OrderExecutionStatus.RECOVERING)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.UNKNOWN,
					OrderExecutionFailureType.RECOVERY_UNKNOWN,
					null,
					execution.recoveryAttemptedAt(),
					null,
					failedAt);
			return true;
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

		/** 저장된 실행 식별값과 예상 상태가 일치하는지 확인합니다. */
		private boolean matches(String executionId, OrderExecutionStatus status) {
			return execution != null
					&& execution.executionId().equals(executionId)
					&& execution.status() == status;
		}

		/** 식별값과 최초 제출 시각을 유지하며 복구 상태 필드만 복사합니다. */
		private AmountOrderExecutionResponse copy(
				OrderExecutionStatus status,
				OrderExecutionFailureType failureType,
				String brokerOrderId,
				OffsetDateTime recoveryAttemptedAt,
				OffsetDateTime completedAt,
				OffsetDateTime updatedAt) {
			return new AmountOrderExecutionResponse(
					execution.executionId(), execution.previewId(), execution.clientOrderId(),
					execution.brokerMode(), status, brokerOrderId, failureType,
					execution.createdAt(), updatedAt, execution.submittedAt(),
					recoveryAttemptedAt, completedAt);
		}
	}

	/** 실제 증권사 없이 금액 주문 복구 호출과 결과를 기록하는 테스트 경계입니다. */
	private static final class RecordingRecoveryGateway implements AmountOrderSubmissionGateway {

		private int submitCallCount;
		private int recoveryCallCount;
		private long lastAccountSeq;
		private AmountOrderSubmissionRequest lastRequest;
		private String mode = "MOCK";
		private OrderSubmissionException failure;
		private OrderCreationResponse response;
		private RuntimeException availabilityFailure;

		/** 준비한 LIVE 안전 차단을 복구권 확보 전에 재현하거나 MOCK 사용을 허용합니다. */
		@Override
		public void requireSubmissionAvailable() {
			if (availabilityFailure != null) {
				throw availabilityFailure;
			}
		}

		/** 복구 중 새 금액 주문 제출이 호출되면 테스트를 실패시킵니다. */
		@Override
		public OrderCreationResponse submitAmountOrder(
				long accountSeq,
				AmountOrderSubmissionRequest request) {
			submitCallCount++;
			throw new AssertionError("복구 중 새 금액 주문 제출을 호출하면 안 됩니다.");
		}

		/** 전달된 복구 요청을 기록하고 준비한 결과 또는 실패를 반환합니다. */
		@Override
		public OrderCreationResponse recoverAmountOrder(
				long accountSeq,
				AmountOrderSubmissionRequest request) {
			recoveryCallCount++;
			lastAccountSeq = accountSeq;
			lastRequest = request;
			if (failure != null) {
				throw failure;
			}
			return response != null ? response : new OrderCreationResponse(
					"fake-amount-" + request.clientOrderId(), request.clientOrderId());
		}

		/** 현재 테스트 복구 경계의 모드를 반환합니다. */
		@Override
		public String mode() {
			return mode;
		}
	}
}

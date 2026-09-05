package com.jusika.backend.orderexecution;

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

import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.OrderTimeInForce;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;
import com.jusika.backend.orderpreview.OrderPreviewResponse;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderPreviewStore;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 실제 토스증권 주문 없이 결과 불명 주문의 동일 요청 단일 복구 규칙을 검사합니다.
 */
class OrderRecoveryServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-09-06T12:00:00Z");

	private FakeOrderPreviewStore previewStore;
	private FakeOrderExecutionStore executionStore;
	private FakeOrderSubmissionGateway submissionGateway;
	private OrderRequestFingerprint fingerprint;
	private OrderRecoveryService service;

	/**
	 * 각 테스트에 고정 시각과 네트워크를 사용하지 않는 복구 의존성을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_모의_복구_서비스를_준비한다() {
		previewStore = new FakeOrderPreviewStore();
		executionStore = new FakeOrderExecutionStore();
		submissionGateway = new FakeOrderSubmissionGateway();
		fingerprint = new OrderRequestFingerprint();
		service = new OrderRecoveryService(
				previewStore,
				executionStore,
				submissionGateway,
				fingerprint,
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	/**
	 * 10분 안의 결과 불명 주문을 같은 계좌·본문·식별값으로 한 번만 복구하는지 검사합니다.
	 */
	@Test
	@DisplayName("결과 불명 주문을 최초 요청 그대로 한 번만 복구한다")
	void 결과_불명_주문을_최초_요청_그대로_한_번만_복구한다() {
		RecoveryFixture fixture = 복구_대상을_저장한다(현재시각().minusMinutes(1));

		OrderExecutionResponse recovered = service.recoverUnknownExecution(
				fixture.execution().executionId());

		assertThat(recovered.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(recovered.failureType()).isNull();
		assertThat(recovered.brokerOrderId()).startsWith("fake-");
		assertThat(recovered.recoveryAttemptedAt()).isEqualTo(현재시각());
		assertThat(submissionGateway.recoveryCallCount).isOne();
		assertThat(submissionGateway.lastAccountSeq).isEqualTo(ACCOUNT_SEQ);
		assertThat(submissionGateway.lastRequest.clientOrderId())
				.isEqualTo(fixture.execution().clientOrderId());
		assertThat(submissionGateway.lastRequest.symbol()).isEqualTo(fixture.preview().symbol());
		assertThat(submissionGateway.lastRequest.quantity())
				.isEqualByComparingTo(fixture.preview().quantity());
		assertThat(submissionGateway.lastRequest.price())
				.isEqualByComparingTo(fixture.preview().requestedPrice());

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderExecutionConflictException.class);
		assertThat(submissionGateway.recoveryCallCount).isOne();
	}

	/**
	 * 최초 제출 후 정확히 10분이 된 주문은 보수적으로 만료 처리하는지 검사합니다.
	 */
	@Test
	@DisplayName("최초 제출 후 정확히 10분이면 복구하지 않는다")
	void 최초_제출_후_정확히_10분이면_복구하지_않는다() {
		RecoveryFixture fixture = 복구_대상을_저장한다(현재시각().minusMinutes(10));

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderRecoveryExpiredException.class)
				.hasMessage("주문 복구 가능 시간 10분이 지났습니다. 토스증권 앱에서 직접 확인해 주세요.");
		assertThat(submissionGateway.recoveryCallCount).isZero();
	}

	/**
	 * 저장된 최초 요청 지문과 재구성 주문이 다르면 복구 경계를 호출하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("최초 주문 지문이 다르면 안전 복구를 차단한다")
	void 최초_주문_지문이_다르면_안전_복구를_차단한다() {
		RecoveryFixture fixture = 복구_대상을_저장한다(현재시각().minusMinutes(1));
		executionStore.fingerprint = "0".repeat(64);

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessage("최초 주문 내용의 동일성을 확인할 수 없어 안전 복구를 차단했습니다.");
		assertThat(submissionGateway.recoveryCallCount).isZero();
	}

	/**
	 * 최초 주문 모드와 현재 제출 모드가 다르면 다른 증권사 경계로 보내지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("최초 주문과 현재 증권사 모드가 다르면 복구하지 않는다")
	void 최초_주문과_현재_증권사_모드가_다르면_복구하지_않는다() {
		RecoveryFixture fixture = 복구_대상을_저장한다(현재시각().minusMinutes(1));
		submissionGateway.mode = "LIVE";

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessage("최초 주문과 현재 증권사 모드가 달라 안전 복구할 수 없습니다.");
		assertThat(submissionGateway.recoveryCallCount).isZero();
	}

	/**
	 * 동시에 다른 요청이 복구권을 선점하면 주문 재전송을 시작하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("복구권을 선점하지 못하면 주문 재전송을 차단한다")
	void 복구권을_선점하지_못하면_주문_재전송을_차단한다() {
		RecoveryFixture fixture = 복구_대상을_저장한다(현재시각().minusMinutes(1));
		executionStore.claimRecoveryAllowed = false;

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessage("다른 요청이 이미 복구했거나 안전 복구할 수 없는 주문 상태입니다.");
		assertThat(submissionGateway.recoveryCallCount).isZero();
	}

	/**
	 * 복구 응답도 불명확하면 두 번째 자동 복구를 막는 실패 분류를 남기는지 검사합니다.
	 */
	@Test
	@DisplayName("복구 결과도 불명확하면 다시 복구할 수 없게 기록한다")
	void 복구_결과도_불명확하면_다시_복구할_수_없게_기록한다() {
		RecoveryFixture fixture = 복구_대상을_저장한다(현재시각().minusMinutes(1));
		submissionGateway.failure = new OrderSubmissionException("테스트 복구 결과 불명", true);

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessage("주문 복구 결과도 확인할 수 없습니다. 주문 목록과 토스증권 앱에서 직접 확인해 주세요.");
		OrderExecutionResponse stored = executionStore.findById(
				fixture.execution().executionId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.RECOVERY_UNKNOWN);
		assertThat(stored.recoveryAttemptedAt()).isEqualTo(현재시각());

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderExecutionConflictException.class);
		assertThat(submissionGateway.recoveryCallCount).isOne();
	}

	/**
	 * 증권사가 다른 멱등성 식별값을 반환하면 성공으로 오인하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("복구 응답의 멱등성 식별값이 다르면 결과 불명으로 기록한다")
	void 복구_응답의_멱등성_식별값이_다르면_결과_불명으로_기록한다() {
		RecoveryFixture fixture = 복구_대상을_저장한다(현재시각().minusMinutes(1));
		submissionGateway.response = new OrderCreationResponse("fake-order", "different-client-id");

		assertThatThrownBy(() -> service.recoverUnknownExecution(
				fixture.execution().executionId()))
				.isInstanceOf(OrderExecutionSubmissionException.class);
		assertThat(executionStore.findById(fixture.execution().executionId()).orElseThrow().failureType())
				.isEqualTo(OrderExecutionFailureType.RECOVERY_UNKNOWN);
	}

	/**
	 * 같은 주문은 같은 지문을 만들고 주문 필드 하나라도 바뀌면 다른 지문을 만드는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 요청 지문은 모든 주문 필드 변경을 감지한다")
	void 주문_요청_지문은_모든_주문_필드_변경을_감지한다() {
		QuantityOrderSubmissionRequest original = 요청을_만든다("client-id", BigDecimal.ONE);
		QuantityOrderSubmissionRequest changed = 요청을_만든다("client-id", new BigDecimal("2"));

		assertThat(fingerprint.calculate(ACCOUNT_SEQ, original))
				.isEqualTo(fingerprint.calculate(ACCOUNT_SEQ, original))
				.hasSize(64)
				.isNotEqualTo(fingerprint.calculate(ACCOUNT_SEQ, changed))
				.isNotEqualTo(fingerprint.calculate(ACCOUNT_SEQ + 1, original));
	}

	/**
	 * 테스트 저장소에 소비된 미리보기와 최초 제출 결과 불명 실행을 함께 보관합니다.
	 *
	 * @param submittedAt 최초 주문 제출 시각
	 * @return 저장한 미리보기와 실행 기록
	 */
	private RecoveryFixture 복구_대상을_저장한다(OffsetDateTime submittedAt) {
		OrderPreviewResponse preview = 미리보기를_만든다();
		previewStore.save(preview);
		OrderExecutionResponse execution = new OrderExecutionResponse(
				UUID.randomUUID().toString(),
				preview.previewId(),
				UUID.randomUUID().toString(),
				"MOCK",
				OrderExecutionStatus.UNKNOWN,
				null,
				OrderExecutionFailureType.SUBMISSION_UNKNOWN,
				submittedAt.minusSeconds(1),
				submittedAt,
				submittedAt,
				null,
				null);
		QuantityOrderSubmissionRequest request = new QuantityOrderSubmissionRequest(
				execution.clientOrderId(), preview.symbol(), preview.side(), preview.orderType(),
				OrderTimeInForce.DAY, preview.quantity(), preview.requestedPrice(),
				preview.requiresHighValueConfirmation());
		executionStore.execution = execution;
		executionStore.fingerprint = fingerprint.calculate(preview.accountSeq(), request);
		return new RecoveryFixture(preview, execution);
	}

	/**
	 * 복구 테스트에 사용할 소비 완료 상태의 국내 지정가 매수 미리보기를 만듭니다.
	 *
	 * @return 최초 주문 내용을 모두 보관한 미리보기
	 */
	private OrderPreviewResponse 미리보기를_만든다() {
		OffsetDateTime now = 현재시각();
		return new OrderPreviewResponse(
				UUID.randomUUID().toString(), now.minusMinutes(2), now.minusMinutes(1),
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT, BigDecimal.ONE,
				new BigDecimal("70000"), new BigDecimal("72000"), new BigDecimal("70000"),
				"KRW", "KR", new BigDecimal("0.00015"), new BigDecimal("70000"),
				new BigDecimal("10.5"), new BigDecimal("70010.5"), false, false, true,
				OrderPreviewStatus.CONSUMED, now.minusMinutes(2));
	}

	/**
	 * 지문 단위 테스트에 사용할 수량 주문을 만듭니다.
	 *
	 * @param clientOrderId 멱등성 식별값
	 * @param quantity 주문 수량
	 * @return 국내 지정가 매수 요청
	 */
	private QuantityOrderSubmissionRequest 요청을_만든다(
			String clientOrderId,
			BigDecimal quantity) {
		return new QuantityOrderSubmissionRequest(
				clientOrderId, "005930", OrderSide.BUY, OrderType.LIMIT,
				OrderTimeInForce.DAY, quantity, new BigDecimal("70000"), false);
	}

	/**
	 * 테스트의 고정 현재 시각을 서울 시간대가 아닌 UTC 오프셋으로 반환합니다.
	 *
	 * @return 고정된 현재 시각
	 */
	private OffsetDateTime 현재시각() {
		return OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
	}

	/**
	 * 한 복구 테스트에서 함께 사용하는 미리보기와 실행 기록을 묶습니다.
	 *
	 * @param preview 최초 주문 미리보기
	 * @param execution 최초 제출 결과 불명 실행
	 */
	private record RecoveryFixture(
			OrderPreviewResponse preview,
			OrderExecutionResponse execution) {
	}

	/** 테스트 메모리에서 최초 주문 미리보기를 보관하는 저장소입니다. */
	private static final class FakeOrderPreviewStore implements OrderPreviewStore {

		private final Map<String, OrderPreviewResponse> previews = new HashMap<>();

		/** 미리보기를 테스트 메모리에 보관합니다. */
		@Override
		public OrderPreviewResponse save(OrderPreviewResponse preview) {
			previews.put(preview.previewId(), preview);
			return preview;
		}

		/** 이 복구 테스트에서는 승인 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
			return false;
		}

		/** 이 복구 테스트에서는 만료 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean expirePending(String previewId, OffsetDateTime now) {
			return false;
		}

		/** 이 복구 테스트에서는 소비 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
			return false;
		}

		/** 식별값으로 테스트 미리보기를 조회합니다. */
		@Override
		public Optional<OrderPreviewResponse> findById(String previewId) {
			return Optional.ofNullable(previews.get(previewId));
		}
	}

	/** 테스트 메모리에서 복구 상태의 원자적 변경을 흉내 내는 저장소입니다. */
	private static final class FakeOrderExecutionStore implements OrderExecutionStore {

		private OrderExecutionResponse execution;
		private String fingerprint;
		private boolean claimRecoveryAllowed = true;

		/** 이 복구 테스트에서는 최초 실행권 생성을 사용하지 않습니다. */
		@Override
		public boolean claim(OrderExecutionResponse execution, String requestFingerprint) {
			return false;
		}

		/** 이 복구 테스트에서는 최초 제출 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
			return false;
		}

		/** 이 복구 테스트에서는 준비 실패 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean markPreparationFailed(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 이 복구 테스트에서는 최초 접수 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean markAccepted(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			return false;
		}

		/** 이 복구 테스트에서는 최초 거절 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean markRejected(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 이 복구 테스트에서는 최초 결과 불명 상태 변경을 사용하지 않습니다. */
		@Override
		public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
			return false;
		}

		/** 실행 기록과 최초 요청 지문을 복구 후보 형태로 반환합니다. */
		@Override
		public Optional<OrderExecutionRecoveryCandidate> findRecoveryCandidateById(
				String executionId) {
			return findById(executionId)
					.map(value -> new OrderExecutionRecoveryCandidate(value, fingerprint));
		}

		/** 최초 결과 불명 상태와 시간 범위를 만족할 때만 복구 중으로 변경합니다. */
		@Override
		public boolean claimRecovery(
				String executionId,
				OffsetDateTime submittedAfter,
				OffsetDateTime recoveryStartedAt) {
			if (!claimRecoveryAllowed
					|| execution == null
					|| !execution.executionId().equals(executionId)
					|| execution.status() != OrderExecutionStatus.UNKNOWN
					|| execution.failureType() != OrderExecutionFailureType.SUBMISSION_UNKNOWN
					|| execution.submittedAt() == null
					|| !execution.submittedAt().isAfter(submittedAfter)
					|| execution.submittedAt().isAfter(recoveryStartedAt)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.RECOVERING,
					execution.failureType(),
					null,
					recoveryStartedAt,
					null,
					recoveryStartedAt);
			return true;
		}

		/** 복구 중 실행을 회수한 주문번호와 함께 접수 상태로 변경합니다. */
		@Override
		public boolean markRecovered(
				String executionId,
				String brokerOrderId,
				OffsetDateTime completedAt) {
			if (!matches(executionId, OrderExecutionStatus.RECOVERING)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.ACCEPTED,
					null,
					brokerOrderId,
					completedAt,
					completedAt,
					execution.recoveryAttemptedAt());
			return true;
		}

		/** 복구 응답도 불확실하면 재복구 불가 실패 분류를 기록합니다. */
		@Override
		public boolean markRecoveryUnknown(String executionId, OffsetDateTime failedAt) {
			if (!matches(executionId, OrderExecutionStatus.RECOVERING)) {
				return false;
			}
			execution = copy(
					OrderExecutionStatus.UNKNOWN,
					OrderExecutionFailureType.RECOVERY_UNKNOWN,
					null,
					failedAt,
					null,
					execution.recoveryAttemptedAt());
			return true;
		}

		/** 실행 식별값으로 테스트 실행 기록을 조회합니다. */
		@Override
		public Optional<OrderExecutionResponse> findById(String executionId) {
			return execution != null && execution.executionId().equals(executionId)
					? Optional.of(execution) : Optional.empty();
		}

		/** 미리보기 식별값으로 테스트 실행 기록을 조회합니다. */
		@Override
		public Optional<OrderExecutionResponse> findByPreviewId(String previewId) {
			return execution != null && execution.previewId().equals(previewId)
					? Optional.of(execution) : Optional.empty();
		}

		/** 현재 실행 식별값과 예상 상태가 일치하는지 확인합니다. */
		private boolean matches(String executionId, OrderExecutionStatus expectedStatus) {
			return execution != null
					&& execution.executionId().equals(executionId)
					&& execution.status() == expectedStatus;
		}

		/** 식별값과 최초 제출 시각을 유지한 채 복구 상태 필드만 복사합니다. */
		private OrderExecutionResponse copy(
				OrderExecutionStatus status,
				OrderExecutionFailureType failureType,
				String brokerOrderId,
				OffsetDateTime updatedAt,
				OffsetDateTime completedAt,
				OffsetDateTime recoveryAttemptedAt) {
			return new OrderExecutionResponse(
					execution.executionId(), execution.previewId(), execution.clientOrderId(),
					execution.brokerMode(), status, brokerOrderId, failureType,
					execution.createdAt(), updatedAt, execution.submittedAt(),
					recoveryAttemptedAt, completedAt);
		}
	}

	/** 실제 증권사 없이 복구 성공 또는 실패를 선택해 반환하는 제출 대역입니다. */
	private static final class FakeOrderSubmissionGateway implements OrderSubmissionGateway {

		private int recoveryCallCount;
		private long lastAccountSeq;
		private QuantityOrderSubmissionRequest lastRequest;
		private String mode = "MOCK";
		private OrderSubmissionException failure;
		private OrderCreationResponse response;

		/** 이 복구 테스트에서는 새 주문 제출을 허용하지 않습니다. */
		@Override
		public OrderCreationResponse submitQuantityOrder(
				long accountSeq,
				QuantityOrderSubmissionRequest request) {
			throw new AssertionError("복구 중 새 주문 제출 경계를 호출하면 안 됩니다.");
		}

		/** 전달된 복구 요청을 기록하고 준비한 결과 또는 실패를 반환합니다. */
		@Override
		public OrderCreationResponse recoverQuantityOrder(
				long accountSeq,
				QuantityOrderSubmissionRequest request) {
			recoveryCallCount++;
			lastAccountSeq = accountSeq;
			lastRequest = request;
			if (failure != null) {
				throw failure;
			}
			return response != null ? response
					: new OrderCreationResponse(
							"fake-" + request.clientOrderId(), request.clientOrderId());
		}

		/** 현재 테스트 제출 경계의 모의 또는 변경된 모드를 반환합니다. */
		@Override
		public String mode() {
			return mode;
		}
	}
}

package com.jusika.backend.orderexecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.OrderTimeInForce;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;
import com.jusika.backend.orderpreview.OrderPreviewResponse;
import com.jusika.backend.orderpreview.OrderPreviewStore;

/**
 * 최초 제출 결과가 불명확한 주문의 기존 주문번호를 동일 요청으로 한 번만 회수합니다.
 */
@Service
public class OrderRecoveryService {

	private static final Duration IDEMPOTENCY_WINDOW = Duration.ofMinutes(10);

	private final OrderPreviewStore previewStore;
	private final OrderExecutionStore executionStore;
	private final OrderSubmissionGateway submissionGateway;
	private final OrderRequestFingerprint requestFingerprint;
	private final Clock clock;

	/**
	 * 저장된 원본 주문과 제출 경계, 요청 지문 계산기와 시계를 전달받습니다.
	 *
	 * @param previewStore 최초 주문 내용을 보관한 미리보기 저장소
	 * @param executionStore 주문 실행 상태와 요청 지문 저장소
	 * @param submissionGateway 현재 설정된 모의 또는 실제 주문 제출 경계
	 * @param requestFingerprint 최초 주문과 복구 주문의 동일성을 확인할 지문 계산기
	 * @param clock 복구 유효시간과 감사 시각을 계산할 시스템 시계
	 */
	public OrderRecoveryService(
			OrderPreviewStore previewStore,
			OrderExecutionStore executionStore,
			OrderSubmissionGateway submissionGateway,
			OrderRequestFingerprint requestFingerprint,
			Clock clock) {
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.submissionGateway = submissionGateway;
		this.requestFingerprint = requestFingerprint;
		this.clock = clock;
	}

	/**
	 * 결과 불명 실행을 10분 안에 최초 본문과 같은 멱등성 식별값으로 한 번만 복구합니다.
	 * 복구는 새로운 주문 판단이 아니므로 현재가나 잔고를 다시 조회해 본문을 바꾸지 않습니다.
	 *
	 * @param executionId 안전 복구할 우리 서버의 주문 실행 식별값
	 * @return 회수한 증권사 주문번호가 기록된 접수 상태
	 */
	public OrderExecutionResponse recoverUnknownExecution(String executionId) {
		validateExecutionId(executionId);
		OrderExecutionRecoveryCandidate candidate = executionStore
				.findRecoveryCandidateById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException(
						"주문 실행 기록을 찾을 수 없습니다."));
		OrderExecutionResponse execution = candidate.execution();
		OrderPreviewResponse preview = previewStore.findById(execution.previewId())
				.orElseThrow(() -> new OrderExecutionConflictException(
						"최초 주문 내용을 찾을 수 없어 안전 복구할 수 없습니다."));
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		QuantityOrderSubmissionRequest request = createOriginalRequest(execution, preview);
		validateRecoveryCandidate(candidate, preview, request, startedAt);
		submissionGateway.requireSubmissionAvailable(preview.accountSeq());

		OffsetDateTime submittedAfter = startedAt.minus(IDEMPOTENCY_WINDOW);
		if (!executionStore.claimRecovery(executionId, submittedAfter, startedAt)) {
			throw new OrderExecutionConflictException(
					"다른 요청이 이미 복구했거나 안전 복구할 수 없는 주문 상태입니다.");
		}
		return recoverAndRecord(execution, preview.accountSeq(), request);
	}

	/**
	 * 저장된 미리보기와 최초 멱등성 식별값으로 원래 수량 주문 본문을 재구성합니다.
	 *
	 * @param execution 최초 주문 실행 기록
	 * @param preview 최초 주문 내용을 보관한 미리보기
	 * @return 최초 제출과 같아야 하는 수량 주문 요청
	 */
	private QuantityOrderSubmissionRequest createOriginalRequest(
			OrderExecutionResponse execution,
			OrderPreviewResponse preview) {
		return new QuantityOrderSubmissionRequest(
				execution.clientOrderId(),
				preview.symbol(),
				preview.side(),
				preview.orderType(),
				OrderTimeInForce.DAY,
				preview.quantity(),
				preview.requestedPrice(),
				preview.requiresHighValueConfirmation());
	}

	/**
	 * 상태·시간·모드·주문 지문이 모두 안전 복구 조건과 일치하는지 확인합니다.
	 *
	 * @param candidate 외부 비공개 요청 지문을 포함한 실행 기록
	 * @param preview 최초 주문의 계좌와 본문을 보관한 미리보기
	 * @param request 저장 정보로 재구성한 주문 요청
	 * @param now 복구 시작 시각
	 */
	private void validateRecoveryCandidate(
			OrderExecutionRecoveryCandidate candidate,
			OrderPreviewResponse preview,
			QuantityOrderSubmissionRequest request,
			OffsetDateTime now) {
		OrderExecutionResponse execution = candidate.execution();
		if (execution.status() != OrderExecutionStatus.UNKNOWN
				|| execution.failureType() != OrderExecutionFailureType.SUBMISSION_UNKNOWN
				|| execution.brokerOrderId() != null
				|| execution.completedAt() != null
				|| execution.recoveryAttemptedAt() != null) {
			throw new OrderExecutionConflictException(
					"최초 제출 결과가 불명확하고 아직 복구하지 않은 주문만 복구할 수 있습니다.");
		}
		if (execution.submittedAt() == null || execution.submittedAt().isAfter(now)) {
			throw new OrderExecutionConflictException(
					"최초 주문 제출 시각이 올바르지 않아 안전 복구할 수 없습니다.");
		}
		if (!now.isBefore(execution.submittedAt().plus(IDEMPOTENCY_WINDOW))) {
			throw new OrderRecoveryExpiredException(
					"주문 복구 가능 시간 10분이 지났습니다. 토스증권 앱에서 직접 확인해 주세요.");
		}
		if (!Objects.equals(submissionGateway.mode(), execution.brokerMode())) {
			throw new OrderExecutionConflictException(
					"최초 주문과 현재 증권사 모드가 달라 안전 복구할 수 없습니다.");
		}
		String storedFingerprint = candidate.requestFingerprint();
		String calculatedFingerprint = requestFingerprint.calculate(preview.accountSeq(), request);
		if (storedFingerprint == null
				|| !storedFingerprint.matches("[0-9a-f]{64}")
				|| !MessageDigest.isEqual(
						storedFingerprint.getBytes(StandardCharsets.US_ASCII),
						calculatedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new OrderExecutionConflictException(
					"최초 주문 내용의 동일성을 확인할 수 없어 안전 복구를 차단했습니다.");
		}
	}

	/**
	 * 원본 주문을 복구 전용 경계로 한 번 호출하고 성공 또는 결과 불명을 기록합니다.
	 *
	 * @param execution 최초 주문 실행 기록
	 * @param accountSeq 최초 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 동일한 수량 주문 요청
	 * @return 접수 상태로 확정된 주문 실행 기록
	 */
	private OrderExecutionResponse recoverAndRecord(
			OrderExecutionResponse execution,
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		try {
			OrderCreationResponse submission = submissionGateway.recoverQuantityOrder(accountSeq, request);
			validateRecoveryResponse(submission, execution.clientOrderId());
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markRecovered(
					execution.executionId(), submission.orderId(), completedAt)) {
				throw new OrderExecutionSubmissionException(
						"주문 복구 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return executionStore.findById(execution.executionId())
					.orElseThrow(() -> new OrderExecutionSubmissionException(
							"주문 복구 결과를 데이터베이스에서 찾지 못했습니다."));
		} catch (OrderSubmissionException exception) {
			markRecoveryUnknown(execution.executionId());
			throw recoveryUnknownException();
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			markRecoveryUnknown(execution.executionId());
			throw recoveryUnknownException();
		}
	}

	/**
	 * 복구 응답에 유효한 주문번호와 최초 멱등성 식별값이 있는지 확인합니다.
	 *
	 * @param submission 모의 또는 실제 증권사가 반환한 복구 응답
	 * @param clientOrderId 최초 주문에 사용한 멱등성 식별값
	 */
	private void validateRecoveryResponse(
			OrderCreationResponse submission,
			String clientOrderId) {
		if (submission == null
				|| submission.orderId() == null
				|| submission.orderId().isBlank()
				|| !clientOrderId.equals(submission.clientOrderId())) {
			throw new OrderSubmissionException("주문 복구 응답 형식이 올바르지 않습니다.", true);
		}
	}

	/**
	 * 복구 결과도 확정할 수 없음을 감사 상태에 기록합니다.
	 *
	 * @param executionId 상태를 변경할 주문 실행 식별값
	 */
	private void markRecoveryUnknown(String executionId) {
		executionStore.markRecoveryUnknown(executionId, OffsetDateTime.now(clock));
	}

	/**
	 * 복구 결과를 자동으로 다시 시도하지 않도록 안내하는 안전한 오류를 만듭니다.
	 *
	 * @return 금융정보를 포함하지 않은 복구 결과 불명 오류
	 */
	private OrderExecutionSubmissionException recoveryUnknownException() {
		return new OrderExecutionSubmissionException(
				"주문 복구 결과도 확인할 수 없습니다. 주문 목록과 토스증권 앱에서 직접 확인해 주세요.");
	}

	/**
	 * 주문 실행 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param executionId 검사할 주문 실행 식별값
	 */
	private void validateExecutionId(String executionId) {
		if (executionId == null) {
			throw new OrderExecutionRequestException("주문 실행 식별값이 필요합니다.");
		}
		try {
			UUID.fromString(executionId);
		} catch (IllegalArgumentException exception) {
			throw new OrderExecutionRequestException("주문 실행 식별값 형식이 올바르지 않습니다.");
		}
	}
}

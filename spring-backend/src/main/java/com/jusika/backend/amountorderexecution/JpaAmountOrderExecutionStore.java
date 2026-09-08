package com.jusika.backend.amountorderexecution;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * JPA와 금액 미리보기 행 잠금을 사용해 금액 주문 실행 저장소를 구현합니다.
 */
@Repository
class JpaAmountOrderExecutionStore implements AmountOrderExecutionStore {

	private final AmountOrderExecutionJpaRepository repository;

	/**
	 * 금액 주문 실행 JPA 저장소를 전달받습니다.
	 *
	 * @param repository 금액 주문 실행 JPA 저장소
	 */
	JpaAmountOrderExecutionStore(AmountOrderExecutionJpaRepository repository) {
		this.repository = repository;
	}

	/**
	 * 같은 금액 미리보기 행을 잠근 트랜잭션에서 실행 기록을 한 번만 생성합니다.
	 *
	 * @param execution 저장할 실행 준비 기록
	 * @param requestFingerprint 최초 금액 주문 본문의 변경 감지용 지문
	 * @return 이번 호출이 실행권을 확보했으면 true
	 */
	@Override
	@Transactional
	public boolean claim(AmountOrderExecutionResponse execution, String requestFingerprint) {
		if (repository.lockPreview(execution.previewId()).isEmpty()) {
			return false;
		}
		if (repository.findByPreviewId(execution.previewId()).isPresent()) {
			return false;
		}
		repository.saveAndFlush(AmountOrderExecutionEntity.from(execution, requestFingerprint));
		return true;
	}

	/** 금액 주문 실행 준비 상태 한 건만 제출 중으로 변경합니다. */
	@Override
	@Transactional
	public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
		return repository.markSubmitting(executionId, submittedAt) == 1;
	}

	/** 제출 전 내부 오류가 난 금액 주문 실행 준비 기록을 종료합니다. */
	@Override
	@Transactional
	public boolean markPreparationFailed(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId,
				OrderExecutionStatus.PREPARED,
				OrderExecutionStatus.REJECTED,
				OrderExecutionFailureType.INTERNAL_STATE,
				failedAt,
				failedAt) == 1;
	}

	/** 제출 중인 금액 주문 실행 한 건만 접수 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markAccepted(
			String executionId,
			String brokerOrderId,
			OffsetDateTime completedAt) {
		return repository.markAccepted(executionId, brokerOrderId, completedAt) == 1;
	}

	/** 제출 중인 금액 주문 실행 한 건만 확정 거절 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markRejected(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId,
				OrderExecutionStatus.SUBMITTING,
				OrderExecutionStatus.REJECTED,
				OrderExecutionFailureType.BROKER_REJECTED,
				failedAt,
				failedAt) == 1;
	}

	/** 제출 중인 금액 주문 실행 한 건만 결과 불명 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId,
				OrderExecutionStatus.SUBMITTING,
				OrderExecutionStatus.UNKNOWN,
				OrderExecutionFailureType.SUBMISSION_UNKNOWN,
				failedAt,
				null) == 1;
	}

	/** 안전 복구에 필요한 금액 주문 실행 기록과 비공개 요청 지문을 함께 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<AmountOrderExecutionRecoveryCandidate> findRecoveryCandidateById(
			String executionId) {
		return repository.findById(executionId)
				.map(entity -> new AmountOrderExecutionRecoveryCandidate(
						entity.toResponse(), entity.requestFingerprint()));
	}

	/** 유효시간 안의 최초 결과 불명 금액 주문만 한 번 복구 중으로 선점합니다. */
	@Override
	@Transactional
	public boolean claimRecovery(
			String executionId,
			OffsetDateTime submittedAfter,
			OffsetDateTime recoveryStartedAt) {
		return repository.claimRecovery(executionId, submittedAfter, recoveryStartedAt) == 1;
	}

	/** 복구 중인 금액 주문을 회수한 주문번호와 함께 접수 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markRecovered(
			String executionId,
			String brokerOrderId,
			OffsetDateTime completedAt) {
		return repository.markRecovered(executionId, brokerOrderId, completedAt) == 1;
	}

	/** 복구 응답도 불확실한 금액 주문을 재복구 불가 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markRecoveryUnknown(String executionId, OffsetDateTime failedAt) {
		return repository.markRecoveryUnknown(executionId, failedAt) == 1;
	}

	/** 실행 식별값으로 저장된 금액 주문 실행 기록을 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<AmountOrderExecutionResponse> findById(String executionId) {
		return repository.findById(executionId).map(AmountOrderExecutionEntity::toResponse);
	}

	/** 미리보기 식별값으로 저장된 금액 주문 실행 기록을 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<AmountOrderExecutionResponse> findByPreviewId(String previewId) {
		return repository.findByPreviewId(previewId).map(AmountOrderExecutionEntity::toResponse);
	}
}

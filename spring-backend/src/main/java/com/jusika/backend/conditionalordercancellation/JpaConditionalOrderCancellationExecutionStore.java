package com.jusika.backend.conditionalordercancellation;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 데이터베이스 잠금과 조건부 갱신으로 조건 주문 취소 실행 저장소를 구현합니다. */
@Repository
class JpaConditionalOrderCancellationExecutionStore
		implements ConditionalOrderCancellationExecutionStore {

	private final ConditionalOrderCancellationExecutionJpaRepository repository;

	/** 조건 주문 취소 실행 JPA 저장소를 전달받습니다. */
	JpaConditionalOrderCancellationExecutionStore(
			ConditionalOrderCancellationExecutionJpaRepository repository) {
		this.repository = repository;
	}

	/** 대상 미리보기 행을 잠그고 같은 계좌의 같은 조건 주문에 실행을 한 번만 만듭니다. */
	@Override
	@Transactional
	public boolean claim(ConditionalOrderCancellationExecutionResponse execution) {
		if (repository.lockPreviewsByTarget(
				execution.accountSeq(), execution.conditionalOrderId()).isEmpty()
				|| repository.findByPreviewId(execution.previewId()).isPresent()
				|| repository.findByAccountSeqAndConditionalOrderId(
						execution.accountSeq(), execution.conditionalOrderId()).isPresent()) {
			return false;
		}
		repository.saveAndFlush(ConditionalOrderCancellationExecutionEntity.from(execution));
		return true;
	}

	/** 준비 상태 한 건만 제출 중 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
		return repository.markSubmitting(executionId, submittedAt) == 1;
	}

	/** 준비 상태 한 건을 내부 상태 오류로 종료합니다. */
	@Override
	@Transactional
	public boolean markPreparationFailed(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId, OrderExecutionStatus.PREPARED, OrderExecutionStatus.REJECTED,
				OrderExecutionFailureType.INTERNAL_STATE, failedAt, failedAt) == 1;
	}

	/** 제출 중인 실행 한 건을 취소 성공 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markAccepted(String executionId, OffsetDateTime completedAt) {
		return repository.markAccepted(executionId, completedAt) == 1;
	}

	/** 제출 중인 실행 한 건을 증권사 확정 거절 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markRejected(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId, OrderExecutionStatus.SUBMITTING, OrderExecutionStatus.REJECTED,
				OrderExecutionFailureType.BROKER_REJECTED, failedAt, failedAt) == 1;
	}

	/** 제출 중인 실행 한 건을 결과 불명 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId, OrderExecutionStatus.SUBMITTING, OrderExecutionStatus.UNKNOWN,
				OrderExecutionFailureType.SUBMISSION_UNKNOWN, failedAt, null) == 1;
	}

	/** 실행 식별값으로 저장된 취소 실행을 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<ConditionalOrderCancellationExecutionResponse> findById(String executionId) {
		return repository.findById(executionId)
				.map(ConditionalOrderCancellationExecutionEntity::toResponse);
	}

	/** 미리보기 식별값으로 저장된 취소 실행을 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<ConditionalOrderCancellationExecutionResponse> findByPreviewId(
			String previewId) {
		return repository.findByPreviewId(previewId)
				.map(ConditionalOrderCancellationExecutionEntity::toResponse);
	}

	/** 계좌와 조건 주문 식별값으로 저장된 취소 실행을 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<ConditionalOrderCancellationExecutionResponse> findByTarget(
			long accountSeq, String conditionalOrderId) {
		return repository.findByAccountSeqAndConditionalOrderId(accountSeq, conditionalOrderId)
				.map(ConditionalOrderCancellationExecutionEntity::toResponse);
	}
}

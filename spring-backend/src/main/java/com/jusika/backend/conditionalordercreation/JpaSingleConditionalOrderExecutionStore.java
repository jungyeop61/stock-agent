package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 데이터베이스 잠금과 조건부 갱신으로 단일 조건 주문 실행 저장소를 구현합니다.
 */
@Repository
class JpaSingleConditionalOrderExecutionStore implements SingleConditionalOrderExecutionStore {

	private final SingleConditionalOrderExecutionJpaRepository repository;

	/** 실행 JPA 저장소를 전달받습니다. */
	JpaSingleConditionalOrderExecutionStore(
			SingleConditionalOrderExecutionJpaRepository repository) {
		this.repository = repository;
	}

	/** 미리보기 행을 잠그고 첫 실행만 저장합니다. */
	@Override
	@Transactional
	public boolean claim(SingleConditionalOrderExecutionResponse execution) {
		if (repository.lockPreview(execution.previewId()).isEmpty()
				|| repository.findByPreviewId(execution.previewId()).isPresent()) {
			return false;
		}
		repository.saveAndFlush(SingleConditionalOrderExecutionEntity.from(execution));
		return true;
	}

	/** 준비 상태 한 건만 제출 중으로 변경합니다. */
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

	/** 제출 중인 실행에 조건 주문 식별값을 기록하고 접수 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markAccepted(
			String executionId,
			String conditionalOrderId,
			OffsetDateTime completedAt) {
		return repository.markAccepted(executionId, conditionalOrderId, completedAt) == 1;
	}

	/** 제출 중인 실행을 확정 거절 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markRejected(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId, OrderExecutionStatus.SUBMITTING, OrderExecutionStatus.REJECTED,
				OrderExecutionFailureType.BROKER_REJECTED, failedAt, failedAt) == 1;
	}

	/** 제출 중인 실행을 결과 불명 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(
				executionId, OrderExecutionStatus.SUBMITTING, OrderExecutionStatus.UNKNOWN,
				OrderExecutionFailureType.SUBMISSION_UNKNOWN, failedAt, null) == 1;
	}

	/** 실행 식별값으로 저장된 실행 기록을 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<SingleConditionalOrderExecutionResponse> findById(String executionId) {
		return repository.findById(executionId).map(SingleConditionalOrderExecutionEntity::toResponse);
	}

	/** 미리보기 식별값으로 저장된 실행 기록을 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<SingleConditionalOrderExecutionResponse> findByPreviewId(String previewId) {
		return repository.findByPreviewId(previewId)
				.map(SingleConditionalOrderExecutionEntity::toResponse);
	}
}

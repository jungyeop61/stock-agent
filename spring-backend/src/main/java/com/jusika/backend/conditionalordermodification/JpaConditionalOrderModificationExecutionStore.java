package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 데이터베이스 잠금과 조건부 갱신으로 조건 주문 정정 실행 저장소를 구현합니다. */
@Repository
class JpaConditionalOrderModificationExecutionStore
		implements ConditionalOrderModificationExecutionStore {

	private final ConditionalOrderModificationExecutionJpaRepository repository;

	/** 조건 주문 정정 실행 JPA 저장소를 전달받습니다. */
	JpaConditionalOrderModificationExecutionStore(
			ConditionalOrderModificationExecutionJpaRepository repository) {
		this.repository = repository;
	}

	/** 대상 미리보기 행을 잠그고 원조건 주문별 첫 실행만 저장합니다. */
	@Override
	@Transactional
	public boolean claim(ConditionalOrderModificationExecutionResponse execution) {
		if (repository.lockPreviewsByTarget(
				execution.accountSeq(), execution.originalConditionalOrderId()).isEmpty()
				|| repository.findByPreviewId(execution.previewId()).isPresent()
				|| repository.findByAccountSeqAndOriginalConditionalOrderId(
						execution.accountSeq(), execution.originalConditionalOrderId()).isPresent()) {
			return false;
		}
		repository.saveAndFlush(ConditionalOrderModificationExecutionEntity.from(execution));
		return true;
	}

	/** 준비 상태 정정 실행만 제출 중으로 변경합니다. */
	@Override
	@Transactional
	public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
		return repository.markSubmitting(executionId, submittedAt) == 1;
	}

	/** 준비 상태 정정 실행을 내부 상태 오류로 종료합니다. */
	@Override
	@Transactional
	public boolean markPreparationFailed(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(executionId, OrderExecutionStatus.PREPARED,
				OrderExecutionStatus.REJECTED, OrderExecutionFailureType.INTERNAL_STATE,
				failedAt, failedAt) == 1;
	}

	/** 제출 중 실행에 새 조건 주문 식별값과 접수 성공을 기록합니다. */
	@Override
	@Transactional
	public boolean markAccepted(
			String executionId, String replacementId, OffsetDateTime completedAt) {
		return repository.markAccepted(executionId, replacementId, completedAt) == 1;
	}

	/** 제출 중 실행을 증권사 확정 거절 상태로 기록합니다. */
	@Override
	@Transactional
	public boolean markRejected(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(executionId, OrderExecutionStatus.SUBMITTING,
				OrderExecutionStatus.REJECTED, OrderExecutionFailureType.BROKER_REJECTED,
				failedAt, failedAt) == 1;
	}

	/** 제출 중 실행을 결과 불명 상태로 기록하고 완료 시각은 비워 둡니다. */
	@Override
	@Transactional
	public boolean markUnknown(String executionId, OffsetDateTime failedAt) {
		return repository.markFailed(executionId, OrderExecutionStatus.SUBMITTING,
				OrderExecutionStatus.UNKNOWN, OrderExecutionFailureType.SUBMISSION_UNKNOWN,
				failedAt, null) == 1;
	}

	/** 실행 식별값으로 저장된 정정 결과를 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<ConditionalOrderModificationExecutionResponse> findById(String executionId) {
		return repository.findById(executionId)
				.map(ConditionalOrderModificationExecutionEntity::toResponse);
	}

	/** 계좌와 원조건 주문 식별값으로 저장된 정정 결과를 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<ConditionalOrderModificationExecutionResponse> findByTarget(
			long accountSeq, String conditionalOrderId) {
		return repository.findByAccountSeqAndOriginalConditionalOrderId(
				accountSeq, conditionalOrderId)
				.map(ConditionalOrderModificationExecutionEntity::toResponse);
	}
}

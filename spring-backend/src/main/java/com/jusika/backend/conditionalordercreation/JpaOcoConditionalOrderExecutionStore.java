package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 데이터베이스 잠금과 조건부 갱신으로 OCO 실행 저장소를 구현합니다. */
@Repository
class JpaOcoConditionalOrderExecutionStore implements OcoConditionalOrderExecutionStore {
	private final OcoConditionalOrderExecutionJpaRepository repository;

	/** OCO 실행 JPA 저장소를 전달받습니다. */
	JpaOcoConditionalOrderExecutionStore(OcoConditionalOrderExecutionJpaRepository repository) {
		this.repository = repository;
	}

	/** OCO 미리보기 행을 잠그고 첫 실행만 저장합니다. */
	@Override @Transactional
	public boolean claim(OcoConditionalOrderExecutionResponse execution) {
		if (repository.lockPreview(execution.previewId()).isEmpty()
				|| repository.findByPreviewId(execution.previewId()).isPresent()) return false;
		repository.saveAndFlush(OcoConditionalOrderExecutionEntity.from(execution));
		return true;
	}

	/** 준비 상태 한 건만 제출 중으로 변경합니다. */
	@Override @Transactional
	public boolean markSubmitting(String id, OffsetDateTime at) {
		return repository.markSubmitting(id, at) == 1;
	}

	/** 준비 상태 한 건을 내부 상태 오류로 종료합니다. */
	@Override @Transactional
	public boolean markPreparationFailed(String id, OffsetDateTime at) {
		return repository.markFailed(id, OrderExecutionStatus.PREPARED,
				OrderExecutionStatus.REJECTED, OrderExecutionFailureType.INTERNAL_STATE,
				at, at) == 1;
	}

	/** 제출 중인 실행에 조건 주문 식별값을 기록하고 접수 상태로 변경합니다. */
	@Override @Transactional
	public boolean markAccepted(String id, String conditionalOrderId, OffsetDateTime at) {
		return repository.markAccepted(id, conditionalOrderId, at) == 1;
	}

	/** 제출 중인 실행을 확정 거절 상태로 변경합니다. */
	@Override @Transactional
	public boolean markRejected(String id, OffsetDateTime at) {
		return repository.markFailed(id, OrderExecutionStatus.SUBMITTING,
				OrderExecutionStatus.REJECTED, OrderExecutionFailureType.BROKER_REJECTED,
				at, at) == 1;
	}

	/** 제출 중인 실행을 결과 불명 상태로 변경합니다. */
	@Override @Transactional
	public boolean markUnknown(String id, OffsetDateTime at) {
		return repository.markFailed(id, OrderExecutionStatus.SUBMITTING,
				OrderExecutionStatus.UNKNOWN, OrderExecutionFailureType.SUBMISSION_UNKNOWN,
				at, null) == 1;
	}

	/** 실행 식별값으로 저장된 OCO 실행을 조회합니다. */
	@Override @Transactional(readOnly = true)
	public Optional<OcoConditionalOrderExecutionResponse> findById(String id) {
		return repository.findById(id).map(OcoConditionalOrderExecutionEntity::toResponse);
	}

	/** 미리보기 식별값으로 저장된 OCO 실행을 조회합니다. */
	@Override @Transactional(readOnly = true)
	public Optional<OcoConditionalOrderExecutionResponse> findByPreviewId(String id) {
		return repository.findByPreviewId(id).map(OcoConditionalOrderExecutionEntity::toResponse);
	}
}

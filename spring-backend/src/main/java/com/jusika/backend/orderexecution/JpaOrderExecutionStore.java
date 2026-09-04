package com.jusika.backend.orderexecution;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA와 미리보기 행 잠금을 사용해 주문 실행 저장소를 구현합니다.
 */
@Repository
class JpaOrderExecutionStore implements OrderExecutionStore {

	private final OrderExecutionJpaRepository repository;

	/**
	 * 주문 실행 JPA 저장소를 전달받습니다.
	 *
	 * @param repository 주문 실행 JPA 저장소
	 */
	JpaOrderExecutionStore(OrderExecutionJpaRepository repository) {
		this.repository = repository;
	}

	/**
	 * 같은 미리보기의 행을 잠근 트랜잭션에서 실행 기록을 한 번만 생성합니다.
	 *
	 * @param execution 저장할 실행 준비 기록
	 * @return 이번 호출이 실행권을 확보했으면 true
	 */
	@Override
	@Transactional
	public boolean claim(OrderExecutionResponse execution) {
		if (repository.lockPreview(execution.previewId()).isEmpty()) {
			return false;
		}
		if (repository.findByPreviewId(execution.previewId()).isPresent()) {
			return false;
		}
		repository.saveAndFlush(OrderExecutionEntity.from(execution));
		return true;
	}

	/**
	 * 실행 준비 행 하나만 주문 제출 중으로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param submittedAt 제출 시작 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	@Override
	@Transactional
	public boolean markSubmitting(String executionId, OffsetDateTime submittedAt) {
		return repository.markSubmitting(executionId, submittedAt) == 1;
	}

	/**
	 * 제출 전 내부 상태가 어긋난 준비 행 하나를 내부 오류로 종료합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 내부 상태 오류를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
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

	/**
	 * 제출 중인 행 하나만 증권사 주문 접수 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param brokerOrderId 증권사가 반환한 주문 식별값
	 * @param completedAt 접수를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	@Override
	@Transactional
	public boolean markAccepted(
			String executionId,
			String brokerOrderId,
			OffsetDateTime completedAt) {
		return repository.markAccepted(executionId, brokerOrderId, completedAt) == 1;
	}

	/**
	 * 제출 중인 행 하나만 확정 거절 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 거절을 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
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

	/**
	 * 제출 중인 행 하나만 주문 접수 여부 불명 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 불명 상태를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
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

	/**
	 * 실행 식별값으로 저장 행을 API 응답 형태로 조회합니다.
	 *
	 * @param executionId 조회할 실행 식별값
	 * @return 저장된 주문 실행 기록이며 없으면 빈 값
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<OrderExecutionResponse> findById(String executionId) {
		return repository.findById(executionId).map(OrderExecutionEntity::toResponse);
	}

	/**
	 * 미리보기 식별값으로 저장 행을 API 응답 형태로 조회합니다.
	 *
	 * @param previewId 조회할 미리보기 식별값
	 * @return 저장된 주문 실행 기록이며 없으면 빈 값
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<OrderExecutionResponse> findByPreviewId(String previewId) {
		return repository.findByPreviewId(previewId).map(OrderExecutionEntity::toResponse);
	}
}

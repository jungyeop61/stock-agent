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
	 * @param requestFingerprint 계좌와 최초 주문 본문을 함께 계산한 변경 감지용 지문
	 * @return 이번 호출이 실행권을 확보했으면 true
	 */
	@Override
	@Transactional
	public boolean claim(OrderExecutionResponse execution, String requestFingerprint) {
		if (repository.lockPreview(execution.previewId()).isEmpty()) {
			return false;
		}
		if (repository.findByPreviewId(execution.previewId()).isPresent()) {
			return false;
		}
		repository.saveAndFlush(OrderExecutionEntity.from(execution, requestFingerprint));
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
	 * 복구 검증에 필요한 실행 기록과 외부 비공개 요청 지문을 함께 조회합니다.
	 *
	 * @param executionId 조회할 실행 식별값
	 * @return 복구 후보이며 없으면 빈 값
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<OrderExecutionRecoveryCandidate> findRecoveryCandidateById(String executionId) {
		return repository.findById(executionId)
				.map(entity -> new OrderExecutionRecoveryCandidate(
						entity.toResponse(), entity.requestFingerprint()));
	}

	/**
	 * 공식 멱등성 유효시간 안의 최초 결과 불명 실행만 복구 중 상태로 선점합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param submittedAfter 제출 시각의 허용 하한선
	 * @param recoveryStartedAt 복구 시작 시각이자 제출 시각 상한선
	 * @return 이번 호출이 복구권을 확보했으면 true
	 */
	@Override
	@Transactional
	public boolean claimRecovery(
			String executionId,
			OffsetDateTime submittedAfter,
			OffsetDateTime recoveryStartedAt) {
		return repository.claimRecovery(executionId, submittedAfter, recoveryStartedAt) == 1;
	}

	/**
	 * 복구 중인 실행을 회수한 증권사 주문번호와 함께 접수 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param brokerOrderId 복구 응답으로 회수한 증권사 주문 식별값
	 * @param completedAt 접수를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	@Override
	@Transactional
	public boolean markRecovered(
			String executionId,
			String brokerOrderId,
			OffsetDateTime completedAt) {
		return repository.markRecovered(executionId, brokerOrderId, completedAt) == 1;
	}

	/**
	 * 복구 응답도 불확실한 실행을 두 번째 복구가 불가능한 상태로 되돌립니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 복구 결과를 확정하지 못한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	@Override
	@Transactional
	public boolean markRecoveryUnknown(String executionId, OffsetDateTime failedAt) {
		return repository.markRecoveryUnknown(executionId, failedAt) == 1;
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

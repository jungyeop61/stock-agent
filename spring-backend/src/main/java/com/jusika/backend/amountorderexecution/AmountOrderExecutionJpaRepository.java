package com.jusika.backend.amountorderexecution;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 금액 주문 실행 행을 저장하고 미리보기 잠금과 조건부 상태 변경을 수행합니다.
 */
interface AmountOrderExecutionJpaRepository
		extends JpaRepository<AmountOrderExecutionEntity, String> {

	/**
	 * 같은 금액 미리보기의 실행 기록을 동시에 만들지 못하도록 부모 행을 잠급니다.
	 *
	 * @param previewId 잠글 금액 주문 미리보기 식별값
	 * @return 존재하는 미리보기 식별값이며 없으면 빈 값
	 */
	@Query(value = "SELECT preview_id FROM amount_order_previews "
			+ "WHERE preview_id = :previewId FOR UPDATE", nativeQuery = true)
	Optional<String> lockPreview(@Param("previewId") String previewId);

	/**
	 * 미리보기 식별값으로 이미 만들어진 금액 주문 실행 기록을 조회합니다.
	 *
	 * @param previewId 조회할 금액 주문 미리보기 식별값
	 * @return 기존 실행 기록이며 없으면 빈 값
	 */
	Optional<AmountOrderExecutionEntity> findByPreviewId(String previewId);

	/**
	 * 실행 준비 상태 한 건만 금액 주문 제출 중 상태로 변경합니다.
	 *
	 * @param executionId 변경할 금액 주문 실행 식별값
	 * @param submittedAt 제출을 시작한 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AmountOrderExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING,
				execution.submittedAt = :submittedAt,
				execution.updatedAt = :submittedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.PREPARED
			""")
	int markSubmitting(
			@Param("executionId") String executionId,
			@Param("submittedAt") OffsetDateTime submittedAt);

	/**
	 * 제출 중 실행을 모의 또는 향후 실제 증권사 접수 상태로 변경합니다.
	 *
	 * @param executionId 변경할 금액 주문 실행 식별값
	 * @param brokerOrderId 제출 경계가 반환한 주문 식별값
	 * @param completedAt 접수를 확인한 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AmountOrderExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.ACCEPTED,
				execution.brokerOrderId = :brokerOrderId,
				execution.completedAt = :completedAt,
				execution.updatedAt = :completedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING
			""")
	int markAccepted(
			@Param("executionId") String executionId,
			@Param("brokerOrderId") String brokerOrderId,
			@Param("completedAt") OffsetDateTime completedAt);

	/**
	 * 지정한 이전 상태의 실행을 거절 또는 결과 불명 상태로 변경합니다.
	 *
	 * @param executionId 변경할 금액 주문 실행 식별값
	 * @param expectedStatus 변경하기 전이어야 하는 상태
	 * @param status 변경할 거절 또는 결과 불명 상태
	 * @param failureType 금융정보를 포함하지 않은 실패 분류
	 * @param updatedAt 실패 상태를 기록한 시각
	 * @param completedAt 결과가 확정된 시각이며 결과 불명이면 null
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AmountOrderExecutionEntity execution
			set execution.status = :status,
				execution.failureType = :failureType,
				execution.completedAt = :completedAt,
				execution.updatedAt = :updatedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = :expectedStatus
			""")
	int markFailed(
			@Param("executionId") String executionId,
			@Param("expectedStatus") OrderExecutionStatus expectedStatus,
			@Param("status") OrderExecutionStatus status,
			@Param("failureType") OrderExecutionFailureType failureType,
			@Param("updatedAt") OffsetDateTime updatedAt,
			@Param("completedAt") OffsetDateTime completedAt);

	/**
	 * 최초 제출 결과가 불명확하고 멱등성 유효시간 안인 금액 주문만 복구 중으로 선점합니다.
	 *
	 * @param executionId 변경할 금액 주문 실행 식별값
	 * @param submittedAfter 제출 시각이 반드시 이 시각보다 나중이어야 하는 하한선
	 * @param recoveryStartedAt 복구 시작 시각이자 미래 제출 기록을 거르는 상한선
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AmountOrderExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.RECOVERING,
				execution.recoveryAttemptedAt = :recoveryStartedAt,
				execution.updatedAt = :recoveryStartedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.UNKNOWN
				and execution.failureType = com.jusika.backend.orderexecution.OrderExecutionFailureType.SUBMISSION_UNKNOWN
				and execution.brokerOrderId is null
				and execution.completedAt is null
				and execution.requestFingerprint is not null
				and execution.recoveryAttemptedAt is null
				and execution.submittedAt > :submittedAfter
				and execution.submittedAt <= :recoveryStartedAt
			""")
	int claimRecovery(
			@Param("executionId") String executionId,
			@Param("submittedAfter") OffsetDateTime submittedAfter,
			@Param("recoveryStartedAt") OffsetDateTime recoveryStartedAt);

	/**
	 * 복구 중인 금액 주문에 회수한 주문번호를 저장하고 접수 상태로 변경합니다.
	 *
	 * @param executionId 변경할 금액 주문 실행 식별값
	 * @param brokerOrderId 복구 응답으로 회수한 주문 식별값
	 * @param completedAt 접수를 확인한 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AmountOrderExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.ACCEPTED,
				execution.brokerOrderId = :brokerOrderId,
				execution.failureType = null,
				execution.completedAt = :completedAt,
				execution.updatedAt = :completedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.RECOVERING
			""")
	int markRecovered(
			@Param("executionId") String executionId,
			@Param("brokerOrderId") String brokerOrderId,
			@Param("completedAt") OffsetDateTime completedAt);

	/**
	 * 복구 결과도 불명확한 금액 주문을 두 번째 복구가 불가능한 상태로 되돌립니다.
	 *
	 * @param executionId 변경할 금액 주문 실행 식별값
	 * @param failedAt 복구 결과를 확정하지 못한 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AmountOrderExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.UNKNOWN,
				execution.failureType = com.jusika.backend.orderexecution.OrderExecutionFailureType.RECOVERY_UNKNOWN,
				execution.updatedAt = :failedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.RECOVERING
			""")
	int markRecoveryUnknown(
			@Param("executionId") String executionId,
			@Param("failedAt") OffsetDateTime failedAt);
}

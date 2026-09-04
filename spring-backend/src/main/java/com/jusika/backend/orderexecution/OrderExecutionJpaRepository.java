package com.jusika.backend.orderexecution;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 실행 행을 저장하고 미리보기 잠금과 조건부 상태 변경을 수행합니다.
 */
interface OrderExecutionJpaRepository extends JpaRepository<OrderExecutionEntity, String> {

	/**
	 * 같은 미리보기의 실행 기록을 동시에 만들지 못하도록 부모 행을 잠급니다.
	 *
	 * @param previewId 잠글 주문 미리보기 식별값
	 * @return 존재하는 미리보기 식별값이며 없으면 빈 값
	 */
	@Query(value = "SELECT preview_id FROM order_previews WHERE preview_id = :previewId FOR UPDATE", nativeQuery = true)
	Optional<String> lockPreview(@Param("previewId") String previewId);

	/**
	 * 미리보기 식별값으로 이미 만들어진 실행 기록을 조회합니다.
	 *
	 * @param previewId 조회할 주문 미리보기 식별값
	 * @return 기존 실행 기록이며 없으면 빈 값
	 */
	Optional<OrderExecutionEntity> findByPreviewId(String previewId);

	/**
	 * 실행 준비 상태 한 건만 주문 제출 중 상태로 변경합니다.
	 *
	 * @param executionId 변경할 주문 실행 식별값
	 * @param submittedAt 증권사 제출을 시작한 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderExecutionEntity execution
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
	 * 제출 중인 실행 기록에 증권사 주문번호를 저장하고 접수 상태로 변경합니다.
	 *
	 * @param executionId 변경할 주문 실행 식별값
	 * @param brokerOrderId 증권사가 반환한 주문 식별값
	 * @param completedAt 주문 접수를 확인한 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderExecutionEntity execution
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
	 * 지정한 이전 상태의 실행 기록을 실패 또는 접수 여부 불명 상태로 변경합니다.
	 *
	 * @param executionId 변경할 주문 실행 식별값
	 * @param expectedStatus 변경하기 전이어야 하는 상태
	 * @param status 거절 또는 결과 불명 상태
	 * @param failureType 금융정보를 포함하지 않은 실패 분류
	 * @param updatedAt 실패 상태를 기록한 시각
	 * @param completedAt 결과가 확정된 시각이며 불명 상태이면 null
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderExecutionEntity execution
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
}

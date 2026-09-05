package com.jusika.backend.ordercancellation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 취소 실행 행의 중복 방지 잠금과 조건부 상태 변경을 수행합니다. */
interface OrderCancellationExecutionJpaRepository
		extends JpaRepository<OrderCancellationExecutionEntity, String> {

	/** 같은 원주문을 가리키는 모든 취소 미리보기 행을 잠급니다. */
	@Query(value = "SELECT preview_id FROM order_cancellation_previews WHERE order_id = :orderId FOR UPDATE", nativeQuery = true)
	List<String> lockPreviewsByOrderId(@Param("orderId") String orderId);

	/** 미리보기로 기존 취소 실행을 조회합니다. */
	Optional<OrderCancellationExecutionEntity> findByPreviewId(String previewId);

	/** 원주문으로 기존 취소 실행을 조회합니다. */
	Optional<OrderCancellationExecutionEntity> findByOrderId(String orderId);

	/** 준비 상태의 취소 실행만 제출 중 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderCancellationExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING,
				execution.submittedAt = :submittedAt,
				execution.updatedAt = :submittedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.PREPARED
			""")
	int markSubmitting(@Param("executionId") String executionId,
			@Param("submittedAt") OffsetDateTime submittedAt);

	/** 제출 중인 취소 실행만 접수 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderCancellationExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.ACCEPTED,
				execution.completedAt = :completedAt,
				execution.updatedAt = :completedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING
			""")
	int markAccepted(@Param("executionId") String executionId,
			@Param("completedAt") OffsetDateTime completedAt);

	/** 예상 상태의 취소 실행을 실패 또는 결과 불명 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderCancellationExecutionEntity execution
			set execution.status = :status,
				execution.failureType = :failureType,
				execution.completedAt = :completedAt,
				execution.updatedAt = :updatedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = :expectedStatus
			""")
	int markFailed(@Param("executionId") String executionId,
			@Param("expectedStatus") OrderExecutionStatus expectedStatus,
			@Param("status") OrderExecutionStatus status,
			@Param("failureType") OrderExecutionFailureType failureType,
			@Param("updatedAt") OffsetDateTime updatedAt,
			@Param("completedAt") OffsetDateTime completedAt);
}

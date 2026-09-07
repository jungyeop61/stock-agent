package com.jusika.backend.ordermodification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 정정 실행 행의 원주문 잠금과 조건부 상태 변경을 수행합니다. */
interface OrderModificationExecutionJpaRepository
		extends JpaRepository<OrderModificationExecutionEntity, String> {
	/** 같은 원주문을 가리키는 정정 미리보기를 모두 잠급니다. */
	@Query(value = "SELECT preview_id FROM order_modification_previews WHERE original_order_id = :orderId FOR UPDATE", nativeQuery = true)
	List<String> lockPreviewsByOrderId(@Param("orderId") String orderId);
	/** 미리보기의 기존 정정 실행을 조회합니다. */
	Optional<OrderModificationExecutionEntity> findByPreviewId(String previewId);
	/** 원주문의 기존 정정 실행을 조회합니다. */
	Optional<OrderModificationExecutionEntity> findByOriginalOrderId(String originalOrderId);

	/** 준비 상태의 정정 실행만 제출 중으로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderModificationExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING,
				execution.submittedAt = :submittedAt,
				execution.updatedAt = :submittedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.PREPARED
			""")
	int markSubmitting(@Param("executionId") String executionId,
			@Param("submittedAt") OffsetDateTime submittedAt);

	/** 제출 중인 정정 실행에 새 주문번호를 기록하고 접수 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderModificationExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.ACCEPTED,
				execution.operationOrderId = :operationOrderId,
				execution.completedAt = :completedAt,
				execution.updatedAt = :completedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING
			""")
	int markAccepted(@Param("executionId") String executionId,
			@Param("operationOrderId") String operationOrderId,
			@Param("completedAt") OffsetDateTime completedAt);

	/** 예상 상태의 정정 실행을 거절 또는 결과 불명 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderModificationExecutionEntity execution
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

package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** OTO 실행 행의 미리보기 잠금과 조건부 상태 변경을 수행합니다. */
interface OtoConditionalOrderExecutionJpaRepository
		extends JpaRepository<OtoConditionalOrderExecutionEntity, String> {

	/** 실행권 확인 중 대상 OTO 미리보기 행을 잠급니다. */
	@Query(value = "SELECT preview_id FROM oto_conditional_order_previews "
			+ "WHERE preview_id = :previewId FOR UPDATE", nativeQuery = true)
	Optional<String> lockPreview(@Param("previewId") String previewId);

	/** 미리보기의 기존 OTO 실행 기록을 조회합니다. */
	Optional<OtoConditionalOrderExecutionEntity> findByPreviewId(String previewId);

	/** 준비 상태의 OTO 실행만 제출 중으로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OtoConditionalOrderExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING,
				execution.submittedAt = :submittedAt,
				execution.updatedAt = :submittedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.PREPARED
			""")
	int markSubmitting(@Param("executionId") String id,
			@Param("submittedAt") OffsetDateTime at);

	/** 제출 중인 OTO 실행에 조건 주문 식별값을 기록하고 접수 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OtoConditionalOrderExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.ACCEPTED,
				execution.conditionalOrderId = :conditionalOrderId,
				execution.completedAt = :completedAt,
				execution.updatedAt = :completedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING
			""")
	int markAccepted(@Param("executionId") String id,
			@Param("conditionalOrderId") String conditionalOrderId,
			@Param("completedAt") OffsetDateTime at);

	/** 예상 상태의 OTO 실행을 거절 또는 결과 불명 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OtoConditionalOrderExecutionEntity execution
			set execution.status = :status,
				execution.failureType = :failureType,
				execution.completedAt = :completedAt,
				execution.updatedAt = :updatedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = :expectedStatus
			""")
	int markFailed(@Param("executionId") String id,
			@Param("expectedStatus") OrderExecutionStatus expectedStatus,
			@Param("status") OrderExecutionStatus status,
			@Param("failureType") OrderExecutionFailureType failureType,
			@Param("updatedAt") OffsetDateTime updatedAt,
			@Param("completedAt") OffsetDateTime completedAt);
}

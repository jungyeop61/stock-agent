package com.jusika.backend.conditionalordercancellation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 조건 주문 취소 실행 행의 중복 방지 잠금과 조건부 상태 변경을 수행합니다. */
interface ConditionalOrderCancellationExecutionJpaRepository
		extends JpaRepository<ConditionalOrderCancellationExecutionEntity, String> {

	/** 같은 계좌와 조건 주문을 가리키는 모든 취소 미리보기 행을 잠급니다. */
	@Query(value = """
			SELECT preview_id
			FROM conditional_order_cancellation_previews
			WHERE account_seq = :accountSeq AND conditional_order_id = :conditionalOrderId
			FOR UPDATE
			""", nativeQuery = true)
	List<String> lockPreviewsByTarget(
			@Param("accountSeq") long accountSeq,
			@Param("conditionalOrderId") String conditionalOrderId);

	/** 미리보기 식별값으로 기존 취소 실행을 조회합니다. */
	Optional<ConditionalOrderCancellationExecutionEntity> findByPreviewId(String previewId);

	/** 계좌와 조건 주문 식별값으로 기존 취소 실행을 조회합니다. */
	Optional<ConditionalOrderCancellationExecutionEntity>
			findByAccountSeqAndConditionalOrderId(long accountSeq, String conditionalOrderId);

	/** 준비 상태의 실행만 제출 중 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderCancellationExecutionEntity execution
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

	/** 제출 중인 실행 한 건을 취소 성공 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderCancellationExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.ACCEPTED,
				execution.completedAt = :completedAt,
				execution.updatedAt = :completedAt,
				execution.version = execution.version + 1
			where execution.executionId = :executionId
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING
			""")
	int markAccepted(
			@Param("executionId") String executionId,
			@Param("completedAt") OffsetDateTime completedAt);

	/** 예상 상태의 실행을 내부 오류·거절·결과 불명 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderCancellationExecutionEntity execution
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

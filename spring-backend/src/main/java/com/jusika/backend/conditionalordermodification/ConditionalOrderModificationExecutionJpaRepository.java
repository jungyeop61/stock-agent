package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 조건 주문 정정 실행의 대상 잠금과 조건부 상태 변경을 수행합니다. */
interface ConditionalOrderModificationExecutionJpaRepository
		extends JpaRepository<ConditionalOrderModificationExecutionEntity, String> {

	/** 같은 계좌와 원조건 주문을 가리키는 모든 정정 미리보기 행을 잠급니다. */
	@Query(value = """
			SELECT preview_id FROM conditional_order_modification_previews
			WHERE account_seq = :accountSeq
			  AND original_conditional_order_id = :conditionalOrderId
			FOR UPDATE
			""", nativeQuery = true)
	List<String> lockPreviewsByTarget(@Param("accountSeq") long accountSeq,
			@Param("conditionalOrderId") String conditionalOrderId);

	/** 미리보기 식별값으로 기존 정정 실행을 조회합니다. */
	Optional<ConditionalOrderModificationExecutionEntity> findByPreviewId(String previewId);

	/** 계좌와 원조건 주문 식별값으로 기존 정정 실행을 조회합니다. */
	Optional<ConditionalOrderModificationExecutionEntity>
			findByAccountSeqAndOriginalConditionalOrderId(long accountSeq, String conditionalOrderId);

	/** 준비된 실행만 제출 중으로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderModificationExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING,
				execution.submittedAt = :at, execution.updatedAt = :at,
				execution.version = execution.version + 1
			where execution.executionId = :id
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.PREPARED
			""")
	int markSubmitting(@Param("id") String id, @Param("at") OffsetDateTime at);

	/** 제출 중 실행에 새 조건 주문 식별값을 기록하고 접수 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderModificationExecutionEntity execution
			set execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.ACCEPTED,
				execution.replacementConditionalOrderId = :replacementId,
				execution.completedAt = :at, execution.updatedAt = :at,
				execution.version = execution.version + 1
			where execution.executionId = :id
				and execution.status = com.jusika.backend.orderexecution.OrderExecutionStatus.SUBMITTING
			""")
	int markAccepted(@Param("id") String id, @Param("replacementId") String replacementId,
			@Param("at") OffsetDateTime at);

	/** 예상 상태의 실행을 내부 오류·거절·결과 불명 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderModificationExecutionEntity execution
			set execution.status = :status, execution.failureType = :failureType,
				execution.completedAt = :completedAt, execution.updatedAt = :updatedAt,
				execution.version = execution.version + 1
			where execution.executionId = :id and execution.status = :expectedStatus
			""")
	int markFailed(@Param("id") String id,
			@Param("expectedStatus") OrderExecutionStatus expectedStatus,
			@Param("status") OrderExecutionStatus status,
			@Param("failureType") OrderExecutionFailureType failureType,
			@Param("updatedAt") OffsetDateTime updatedAt,
			@Param("completedAt") OffsetDateTime completedAt);
}

package com.jusika.backend.conditionalordercancellation;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 조건 주문 취소 미리보기 행을 저장하고 승인·만료·사용 상태를 조건부로 변경합니다. */
interface ConditionalOrderCancellationPreviewJpaRepository
		extends JpaRepository<ConditionalOrderCancellationPreviewEntity, String> {

	/** 승인 대기 중이며 아직 만료되지 않은 미리보기만 승인합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderCancellationPreviewEntity preview
			set preview.status = com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewStatus.APPROVED,
				preview.approvedAt = :approvedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt > :approvedAt
			""")
	int approvePending(
			@Param("previewId") String previewId,
			@Param("approvedAt") OffsetDateTime approvedAt);

	/** 승인 대기 중이며 유효시간이 지난 미리보기만 만료 처리합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderCancellationPreviewEntity preview
			set preview.status = com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewStatus.EXPIRED,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt <= :now
			""")
	int expirePending(
			@Param("previewId") String previewId,
			@Param("now") OffsetDateTime now);

	/** 승인된 미리보기만 취소 실행에 사용된 상태로 한 번 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderCancellationPreviewEntity preview
			set preview.status = com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewStatus.CONSUMED,
				preview.consumedAt = :consumedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewStatus.APPROVED
			""")
	int consumeApproved(
			@Param("previewId") String previewId,
			@Param("consumedAt") OffsetDateTime consumedAt);
}

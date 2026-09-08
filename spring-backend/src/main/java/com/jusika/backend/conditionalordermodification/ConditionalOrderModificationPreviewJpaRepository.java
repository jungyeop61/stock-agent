package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 조건 주문 정정 미리보기의 원자적인 승인·만료·사용 변경을 수행합니다. */
interface ConditionalOrderModificationPreviewJpaRepository
		extends JpaRepository<ConditionalOrderModificationPreviewEntity, String> {

	/** 유효시간 안의 승인 대기 미리보기만 승인합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderModificationPreviewEntity preview
			set preview.status = com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewStatus.APPROVED,
				preview.approvedAt = :approvedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt > :approvedAt
			""")
	int approvePending(@Param("previewId") String previewId,
			@Param("approvedAt") OffsetDateTime approvedAt);

	/** 승인하지 않은 채 유효시간이 지난 미리보기를 만료시킵니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderModificationPreviewEntity preview
			set preview.status = com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewStatus.EXPIRED,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt <= :now
			""")
	int expirePending(@Param("previewId") String previewId, @Param("now") OffsetDateTime now);

	/** 승인된 미리보기만 한 번 실행 사용 상태로 바꿉니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ConditionalOrderModificationPreviewEntity preview
			set preview.status = com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewStatus.CONSUMED,
				preview.consumedAt = :consumedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewStatus.APPROVED
			""")
	int consumeApproved(@Param("previewId") String previewId,
			@Param("consumedAt") OffsetDateTime consumedAt);
}

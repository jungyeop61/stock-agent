package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** OCO 조건 주문 미리보기 행의 승인·만료·사용 상태를 조건부로 변경합니다. */
interface OcoConditionalOrderPreviewJpaRepository
		extends JpaRepository<OcoConditionalOrderPreviewEntity, String> {

	/** 유효시간 안의 승인 대기 미리보기만 승인합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OcoConditionalOrderPreviewEntity preview
			set preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.APPROVED,
				preview.approvedAt = :approvedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt > :approvedAt
			""")
	int approvePending(@Param("previewId") String previewId,
			@Param("approvedAt") OffsetDateTime approvedAt);

	/** 유효시간이 지난 승인 대기 미리보기만 만료 처리합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OcoConditionalOrderPreviewEntity preview
			set preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.EXPIRED,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt <= :now
			""")
	int expirePending(@Param("previewId") String previewId, @Param("now") OffsetDateTime now);

	/** 승인된 OCO 미리보기만 실행에 사용된 상태로 변경합니다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OcoConditionalOrderPreviewEntity preview
			set preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.CONSUMED,
				preview.consumedAt = :consumedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.APPROVED
			""")
	int consumeApproved(@Param("previewId") String previewId,
			@Param("consumedAt") OffsetDateTime consumedAt);
}

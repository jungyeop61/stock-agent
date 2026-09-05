package com.jusika.backend.ordercancellation;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 취소 미리보기 행을 저장하고 승인·만료·사용 상태를 조건부로 변경합니다.
 */
interface OrderCancellationPreviewJpaRepository
		extends JpaRepository<OrderCancellationPreviewEntity, String> {

	/**
	 * 승인 대기 중이며 아직 만료되지 않은 취소 미리보기만 승인합니다.
	 *
	 * @param previewId 승인할 취소 미리보기 식별값
	 * @param approvedAt 승인 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderCancellationPreviewEntity preview
			set preview.status = com.jusika.backend.ordercancellation.OrderCancellationPreviewStatus.APPROVED,
				preview.approvedAt = :approvedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.ordercancellation.OrderCancellationPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt > :approvedAt
			""")
	int approvePending(
			@Param("previewId") String previewId,
			@Param("approvedAt") OffsetDateTime approvedAt);

	/**
	 * 승인 대기 중이며 유효시간이 지난 취소 미리보기만 만료 처리합니다.
	 *
	 * @param previewId 만료 처리할 취소 미리보기 식별값
	 * @param now 현재 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderCancellationPreviewEntity preview
			set preview.status = com.jusika.backend.ordercancellation.OrderCancellationPreviewStatus.EXPIRED,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.ordercancellation.OrderCancellationPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt <= :now
			""")
	int expirePending(
			@Param("previewId") String previewId,
			@Param("now") OffsetDateTime now);

	/**
	 * 승인된 취소 미리보기만 실행에 사용된 상태로 한 번 변경합니다.
	 *
	 * @param previewId 사용 처리할 취소 미리보기 식별값
	 * @param consumedAt 취소 실행을 시작한 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderCancellationPreviewEntity preview
			set preview.status = com.jusika.backend.ordercancellation.OrderCancellationPreviewStatus.CONSUMED,
				preview.consumedAt = :consumedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.ordercancellation.OrderCancellationPreviewStatus.APPROVED
			""")
	int consumeApproved(
			@Param("previewId") String previewId,
			@Param("consumedAt") OffsetDateTime consumedAt);
}

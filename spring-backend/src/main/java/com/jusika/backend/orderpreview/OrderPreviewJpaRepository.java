package com.jusika.backend.orderpreview;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 미리보기 행을 조회하고 조건부 상태 변경 쿼리를 실행합니다.
 */
interface OrderPreviewJpaRepository extends JpaRepository<OrderPreviewEntity, String> {

	/**
	 * 승인 대기 중이며 아직 만료되지 않은 한 행만 승인 상태로 변경합니다.
	 *
	 * @param previewId 승인할 미리보기 식별값
	 * @param approvedAt 승인 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderPreviewEntity preview
			set preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.APPROVED,
				preview.approvedAt = :approvedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt > :approvedAt
			""")
	int approvePending(
			@Param("previewId") String previewId,
			@Param("approvedAt") OffsetDateTime approvedAt);

	/**
	 * 승인 대기 중이며 유효시간이 지난 한 행만 만료 상태로 변경합니다.
	 *
	 * @param previewId 만료 여부를 반영할 미리보기 식별값
	 * @param now 현재 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderPreviewEntity preview
			set preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.EXPIRED,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.PENDING_APPROVAL
				and preview.expiresAt <= :now
			""")
	int expirePending(
			@Param("previewId") String previewId,
			@Param("now") OffsetDateTime now);

	/**
	 * 승인된 미리보기 한 행만 주문에 사용된 상태로 변경합니다.
	 *
	 * @param previewId 사용 처리할 미리보기 식별값
	 * @param consumedAt 주문 실행이 시작된 시각
	 * @return 상태가 변경된 행의 수
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderPreviewEntity preview
			set preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.CONSUMED,
				preview.consumedAt = :consumedAt,
				preview.version = preview.version + 1
			where preview.previewId = :previewId
				and preview.status = com.jusika.backend.orderpreview.OrderPreviewStatus.APPROVED
			""")
	int consumeApproved(
			@Param("previewId") String previewId,
			@Param("consumedAt") OffsetDateTime consumedAt);
}

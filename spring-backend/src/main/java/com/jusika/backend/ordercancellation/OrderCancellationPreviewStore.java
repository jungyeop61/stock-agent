package com.jusika.backend.ordercancellation;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 취소 미리보기의 변경 불가 내용과 승인 상태를 보관하는 저장소 경계입니다.
 */
public interface OrderCancellationPreviewStore {

	/**
	 * 새 취소 미리보기 전체 내용을 저장합니다.
	 *
	 * @param preview 저장할 취소 미리보기
	 * @return 저장된 취소 미리보기
	 */
	OrderCancellationPreviewResponse save(OrderCancellationPreviewResponse preview);

	/**
	 * 유효한 승인 대기 미리보기만 승인 상태로 변경합니다.
	 *
	 * @param previewId 승인할 취소 미리보기 식별값
	 * @param approvedAt 승인 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean approvePending(String previewId, OffsetDateTime approvedAt);

	/**
	 * 유효시간이 지난 승인 대기 미리보기만 만료 상태로 변경합니다.
	 *
	 * @param previewId 만료 처리할 취소 미리보기 식별값
	 * @param now 현재 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean expirePending(String previewId, OffsetDateTime now);

	/**
	 * 승인된 취소 미리보기만 실행에 사용된 상태로 변경합니다.
	 *
	 * @param previewId 사용 처리할 취소 미리보기 식별값
	 * @param consumedAt 취소 실행을 시작한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean consumeApproved(String previewId, OffsetDateTime consumedAt);

	/**
	 * 식별값으로 저장된 취소 미리보기를 조회합니다.
	 *
	 * @param previewId 조회할 취소 미리보기 식별값
	 * @return 저장된 미리보기이며 없으면 빈 값
	 */
	Optional<OrderCancellationPreviewResponse> findById(String previewId);
}

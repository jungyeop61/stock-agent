package com.jusika.backend.amountorderpreview;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 금액 주문 미리보기의 변경 불가 계산 결과를 저장하고 조회하는 저장소 경계입니다.
 */
public interface AmountOrderPreviewStore {

	/**
	 * 검증과 계산을 마친 금액 주문 미리보기를 저장합니다.
	 *
	 * @param preview 저장할 금액 주문 미리보기
	 * @return 데이터베이스에 저장된 금액 주문 미리보기
	 */
	AmountOrderPreviewResponse save(AmountOrderPreviewResponse preview);

	/**
	 * 아직 승인 대기 중이고 만료되지 않은 금액 미리보기 하나만 승인합니다.
	 *
	 * @param previewId 승인할 금액 미리보기 식별값
	 * @param approvedAt 승인 시각
	 * @return 이번 호출이 실제로 승인 상태를 변경했으면 true
	 */
	boolean approvePending(String previewId, OffsetDateTime approvedAt);

	/**
	 * 승인 대기 중이면서 유효시간이 지난 금액 미리보기를 만료 상태로 바꿉니다.
	 *
	 * @param previewId 만료 여부를 반영할 금액 미리보기 식별값
	 * @param now 현재 시각
	 * @return 이번 호출이 실제로 만료 상태를 변경했으면 true
	 */
	boolean expirePending(String previewId, OffsetDateTime now);

	/**
	 * 식별값에 해당하는 저장된 금액 주문 미리보기를 조회합니다.
	 *
	 * @param previewId 조회할 미리보기 식별값
	 * @return 저장된 미리보기이며 없으면 빈 값
	 */
	Optional<AmountOrderPreviewResponse> findById(String previewId);
}

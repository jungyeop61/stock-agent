package com.jusika.backend.amountorderpreview;

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
	 * 식별값에 해당하는 저장된 금액 주문 미리보기를 조회합니다.
	 *
	 * @param previewId 조회할 미리보기 식별값
	 * @return 저장된 미리보기이며 없으면 빈 값
	 */
	Optional<AmountOrderPreviewResponse> findById(String previewId);
}

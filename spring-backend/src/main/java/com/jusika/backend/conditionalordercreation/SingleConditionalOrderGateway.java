package com.jusika.backend.conditionalordercreation;

import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.SingleConditionalOrderSubmissionRequest;

/**
 * 승인된 단일 조건 주문을 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다.
 */
interface SingleConditionalOrderGateway {

	/**
	 * 최종 재검증한 단일 조건 주문을 현재 설정된 실행 모드로 제출합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 단일 조건 주문
	 * @return 모의 또는 증권사가 반환한 조건 주문 식별값
	 */
	ConditionalOrderCreationResponse submit(
			long accountSeq,
			SingleConditionalOrderSubmissionRequest request);

	/**
	 * 현재 실행 경계가 모의인지 실제인지 구분할 이름을 반환합니다.
	 *
	 * @return 현재 단계에서는 MOCK
	 */
	String mode();
}

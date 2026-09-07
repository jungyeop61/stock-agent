package com.jusika.backend.conditionalordercreation;

import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.OtoConditionalOrderSubmissionRequest;

/** OTO 실행 서비스와 모의 또는 향후 실제 증권사 제출 구현 사이의 경계입니다. */
interface OtoConditionalOrderGateway {
	/** 최종 검증을 마친 OTO 조건 주문을 현재 모드로 한 번 제출합니다. */
	ConditionalOrderCreationResponse submit(
			long accountSeq,
			OtoConditionalOrderSubmissionRequest request);

	/** 실행 기록에 저장할 제출 모드 이름을 반환합니다. */
	String mode();
}

package com.jusika.backend.conditionalordercreation;

import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.OcoConditionalOrderSubmissionRequest;

/** 승인된 OCO 조건 주문을 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다. */
interface OcoConditionalOrderGateway {

	/**
	 * 최신 금융정보 재조회, 미리보기 소비와 실행권 확보 전에 생성 경계가 안전한지 확인합니다.
	 * 실제 생성 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireSubmissionAvailable();

	/** 최종 재검증한 OCO 조건 주문을 현재 설정된 실행 모드로 제출합니다. */
	ConditionalOrderCreationResponse submit(
			long accountSeq,
			OcoConditionalOrderSubmissionRequest request);

	/** 현재 실행 경계가 모의인지 실제인지 구분할 이름을 반환합니다. */
	String mode();
}

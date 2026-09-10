package com.jusika.backend.conditionalordermodification;

import com.jusika.backend.conditionalorder.ConditionalOrderModificationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationSubmissionRequest;

/** 승인된 조건 주문 정정을 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다. */
interface ConditionalOrderModificationGateway {

	/**
	 * 최신 원조건 주문과 금융정보 재조회, 미리보기 소비와 정정 실행권 확보 전에 경계가 안전한지 확인합니다.
	 * 실제 정정 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireModificationAvailable();

	/** 검증된 새 전체 구성으로 기존 조건 주문을 대체합니다. */
	ConditionalOrderModificationResponse modify(
			long accountSeq,
			String originalConditionalOrderId,
			ConditionalOrderModificationSubmissionRequest request);

	/** 현재 정정 경계가 모의인지 실제인지 구분할 이름을 반환합니다. */
	String mode();
}

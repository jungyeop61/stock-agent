package com.jusika.backend.conditionalordercreation;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.OtoConditionalOrderSubmissionRequest;

/** OTO 실행 서비스와 모의 또는 향후 실제 증권사 제출 구현 사이의 경계입니다. */
interface OtoConditionalOrderGateway {
	/**
	 * 최신 금융정보 재조회, 미리보기 소비와 실행권 확보 전에 생성 경계가 안전한지 확인합니다.
	 * 실제 생성 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireSubmissionAvailable();

	/**
	 * 계좌 식별값을 아는 실행 단계에서 생성 경계와 계좌 허용 여부를 함께 확인합니다.
	 *
	 * @param accountSeq OTO 조건 주문에 사용할 계좌 식별값
	 */
	default void requireSubmissionAvailable(long accountSeq) {
		requireSubmissionAvailable();
	}

	/** 실행 기록 생성 전에 OTO의 최대 조건 주문값이 LIVE 1회 한도 이내인지 확인합니다. */
	default void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 생성은 실제 주문 조건을 만들지 않으므로 LIVE 한도를 적용하지 않습니다.
	}

	/** 최종 검증을 마친 OTO 조건 주문을 현재 모드로 한 번 제출합니다. */
	ConditionalOrderCreationResponse submit(
			long accountSeq,
			OtoConditionalOrderSubmissionRequest request);

	/** 실행 기록에 저장할 제출 모드 이름을 반환합니다. */
	String mode();
}

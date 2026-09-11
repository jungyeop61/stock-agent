package com.jusika.backend.conditionalordercreation;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.OcoConditionalOrderSubmissionRequest;

/** 승인된 OCO 조건 주문을 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다. */
interface OcoConditionalOrderGateway {

	/**
	 * 최신 금융정보 재조회, 미리보기 소비와 실행권 확보 전에 생성 경계가 안전한지 확인합니다.
	 * 실제 생성 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireSubmissionAvailable();

	/**
	 * 계좌 식별값을 아는 실행 단계에서 생성 경계와 계좌 허용 여부를 함께 확인합니다.
	 *
	 * @param accountSeq OCO 조건 주문에 사용할 계좌 식별값
	 */
	default void requireSubmissionAvailable(long accountSeq) {
		requireSubmissionAvailable();
	}

	/** 실행 기록 생성 전에 OCO의 최대 조건 주문값이 LIVE 1회 한도 이내인지 확인합니다. */
	default void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 생성은 실제 주문 조건을 만들지 않으므로 LIVE 한도를 적용하지 않습니다.
	}

	/** 실행 기록을 만들기 전에 계좌별 일일 누적 OCO 한도를 사전 검사합니다. */
	default void requireDailyOrderWithinLimits(
			long accountSeq,
			BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 생성은 실제 주문 조건을 만들지 않으므로 일일 LIVE 한도를 적용하지 않습니다.
	}

	/** 실제 증권사 호출 직전에 OCO 위험을 일일 누적 한도에 멱등하게 예약합니다. */
	default void reserveDailyOrderRisk(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 생성은 실제 주문 조건을 만들지 않으므로 일일 위험을 예약하지 않습니다.
	}

	/** 최종 재검증한 OCO 조건 주문을 현재 설정된 실행 모드로 제출합니다. */
	ConditionalOrderCreationResponse submit(
			long accountSeq,
			OcoConditionalOrderSubmissionRequest request);

	/** 현재 실행 경계가 모의인지 실제인지 구분할 이름을 반환합니다. */
	String mode();
}

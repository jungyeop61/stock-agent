package com.jusika.backend.conditionalordermodification;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationSubmissionRequest;

/** 승인된 조건 주문 정정을 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다. */
interface ConditionalOrderModificationGateway {

	/**
	 * 최신 원조건 주문과 금융정보 재조회, 미리보기 소비와 정정 실행권 확보 전에 경계가 안전한지 확인합니다.
	 * 실제 정정 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireModificationAvailable();

	/**
	 * 계좌 식별값을 아는 실행 단계에서 정정 경계와 계좌 허용 여부를 함께 확인합니다.
	 *
	 * @param accountSeq 정정할 조건 주문의 계좌 식별값
	 */
	default void requireModificationAvailable(long accountSeq) {
		requireModificationAvailable();
	}

	/** 실행 기록 생성 전에 정정 후 최대 조건 주문값이 LIVE 1회 한도 이내인지 확인합니다. */
	default void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 정정은 실제 주문 조건을 만들지 않으므로 LIVE 한도를 적용하지 않습니다.
	}

	/** 실행 기록을 만들기 전에 계좌별 일일 누적 조건 정정 한도를 사전 검사합니다. */
	default void requireDailyOrderWithinLimits(
			long accountSeq,
			BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 정정은 실제 주문 조건을 만들지 않으므로 일일 LIVE 한도를 적용하지 않습니다.
	}

	/** 실제 증권사 호출 직전에 조건 정정 위험을 일일 누적 한도에 멱등하게 예약합니다. */
	default void reserveDailyOrderRisk(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 정정은 실제 주문 조건을 만들지 않으므로 일일 위험을 예약하지 않습니다.
	}

	/** 검증된 새 전체 구성으로 기존 조건 주문을 대체합니다. */
	ConditionalOrderModificationResponse modify(
			long accountSeq,
			String originalConditionalOrderId,
			ConditionalOrderModificationSubmissionRequest request);

	/** 현재 정정 경계가 모의인지 실제인지 구분할 이름을 반환합니다. */
	String mode();
}

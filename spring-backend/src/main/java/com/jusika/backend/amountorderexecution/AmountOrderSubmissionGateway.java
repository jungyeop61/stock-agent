package com.jusika.backend.amountorderexecution;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;

/**
 * 금액 주문 실행 서비스가 실제 HTTP 클라이언트를 직접 알지 않도록 분리한 제출 경계입니다.
 */
public interface AmountOrderSubmissionGateway {

	/**
	 * 미리보기 소비나 복구권 확보 전에 현재 금액 주문 제출 경계가 안전하게 사용 가능한지 확인합니다.
	 * 실제 주문 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireSubmissionAvailable();

	/**
	 * 계좌 식별값을 아는 실행 단계에서 제출 경계와 계좌 허용 여부를 함께 확인합니다.
	 *
	 * @param accountSeq 금액 주문에 사용할 계좌 식별값
	 */
	default void requireSubmissionAvailable(long accountSeq) {
		requireSubmissionAvailable();
	}

	/** 실행 상태를 만들기 전에 미국 주식 LIVE 종목 허용 목록을 검사합니다. */
	default void requireInstrumentAllowed(String symbol, String currency) {
		// MOCK 모드는 실제 주문을 만들지 않으므로 LIVE 종목 목록을 적용하지 않습니다.
	}

	/** 실행 기록 생성 전에 최종 달러 주문금액이 LIVE 1회 한도 이내인지 확인합니다. */
	default void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 모드는 실제 주문을 만들지 않으므로 LIVE 한도를 적용하지 않습니다.
	}

	/** 실행 기록을 만들기 전에 계좌별 일일 누적 달러 주문 한도를 사전 검사합니다. */
	default void requireDailyOrderWithinLimits(
			long accountSeq,
			BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 모드는 실제 주문을 만들지 않으므로 일일 LIVE 한도를 적용하지 않습니다.
	}

	/** 안전 복구 전에 기존 멱등 예약을 인식하면서 계좌별 일일 누적 한도를 검사합니다. */
	default void requireDailyOrderWithinLimits(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		requireDailyOrderWithinLimits(accountSeq, riskSnapshot);
	}

	/** 실제 증권사 호출 직전에 주문 위험을 일일 누적 한도에 멱등하게 예약합니다. */
	default void reserveDailyOrderRisk(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		// MOCK 모드는 실제 주문을 만들지 않으므로 일일 위험을 예약하지 않습니다.
	}

	/**
	 * 금액 주문을 현재 설정된 모의 또는 향후 실제 증권사 모드로 제출합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 미국 주식 달러 금액 주문
	 * @return 모의 또는 향후 실제 증권사가 반환한 주문 생성 결과
	 */
	OrderCreationResponse submitAmountOrder(long accountSeq, AmountOrderSubmissionRequest request);

	/**
	 * 결과 불명 금액 주문을 최초 본문과 같은 멱등성 식별값으로 한 번만 복구합니다.
	 *
	 * @param accountSeq 최초 금액 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 완전히 동일한 금액 주문 본문
	 * @return 기존 금액 주문이 있었다면 그 주문의 식별값을 담은 응답
	 */
	OrderCreationResponse recoverAmountOrder(long accountSeq, AmountOrderSubmissionRequest request);

	/**
	 * 현재 금액 주문 제출 구현이 사용하는 안전 모드 이름을 반환합니다.
	 *
	 * @return 예를 들어 MOCK과 같은 주문 제출 모드
	 */
	String mode();
}

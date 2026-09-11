package com.jusika.backend.order;

import java.math.BigDecimal;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.orderpreview.OrderSide;

/**
 * 미국 주식 시장가 주문에 사용할 달러 금액 기반 주문 내용을 표현합니다.
 *
 * @param clientOrderId 중복 주문을 막기 위해 우리 서버가 만든 멱등성 식별값
 * @param symbol 주문할 미국 주식 종목 코드
 * @param side 매수 또는 매도 방향
 * @param orderAmount 주문할 달러 금액
 * @param confirmHighValueOrder 사용자가 1억원 이상 환산 주문금액을 확인했는지 여부
 * @param riskSnapshot 실행 직전 확정한 주문금액 한도 검사값
 */
public record AmountOrderSubmissionRequest(
		String clientOrderId,
		String symbol,
		OrderSide side,
		BigDecimal orderAmount,
		boolean confirmHighValueOrder,
		BrokerOrderRiskSnapshot riskSnapshot) {

	/** 기존 호출 형식을 유지하되 LIVE 한도 검사값은 미지정 상태로 만듭니다. */
	public AmountOrderSubmissionRequest(
			String clientOrderId,
			String symbol,
			OrderSide side,
			BigDecimal orderAmount,
			boolean confirmHighValueOrder) {
		this(clientOrderId, symbol, side, orderAmount, confirmHighValueOrder, null);
	}

	/**
	 * 객체가 로그에 기록되더라도 주문 금액이 노출되지 않도록 가립니다.
	 *
	 * @return 금융정보가 제거된 요청 설명
	 */
	@Override
	public String toString() {
		return "AmountOrderSubmissionRequest[clientOrderId=***, symbol=" + symbol
				+ ", side=" + side + ", orderAmount=***, confirmHighValueOrder="
				+ confirmHighValueOrder + "]";
	}
}

package com.jusika.backend.order;

import java.math.BigDecimal;

import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 토스증권에 전달할 수량 기반 주문 내용을 표현합니다.
 *
 * @param clientOrderId 중복 주문을 막기 위해 우리 서버가 만든 멱등성 식별값
 * @param symbol 주문할 국내 또는 미국 주식 종목 코드
 * @param side 매수 또는 매도 방향
 * @param orderType 지정가 또는 시장가 유형
 * @param timeInForce 주문 유효 조건이며 null이면 당일 주문으로 처리
 * @param quantity 주문할 주식 수량
 * @param price 지정가 주문 가격이며 시장가 주문이면 null
 * @param confirmHighValueOrder 사용자가 1억원 이상 주문금액을 확인했는지 여부
 */
public record QuantityOrderSubmissionRequest(
		String clientOrderId,
		String symbol,
		OrderSide side,
		OrderType orderType,
		OrderTimeInForce timeInForce,
		BigDecimal quantity,
		BigDecimal price,
		boolean confirmHighValueOrder) {

	/**
	 * 객체가 로그에 기록되더라도 주문 수량과 가격이 노출되지 않도록 가립니다.
	 *
	 * @return 금융정보가 제거된 요청 설명
	 */
	@Override
	public String toString() {
		return "QuantityOrderSubmissionRequest[clientOrderId=***, symbol=" + symbol
				+ ", side=" + side + ", orderType=" + orderType
				+ ", timeInForce=" + timeInForce
				+ ", quantity=***, price=***, confirmHighValueOrder="
				+ confirmHighValueOrder + "]";
	}
}

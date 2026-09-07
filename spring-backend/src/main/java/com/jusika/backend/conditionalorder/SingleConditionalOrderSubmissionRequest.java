package com.jusika.backend.conditionalorder;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 검증을 마친 단일 조건 주문을 모의 또는 향후 실제 증권사에 제출할 때 사용하는 요청입니다.
 *
 * @param clientOrderId 중복 생성을 막는 우리 서버의 멱등성 식별값
 * @param symbol 종목 코드
 * @param quantity 주문 수량
 * @param orderType 지정가 또는 시장가
 * @param expireDate 조건 감시 만료일
 * @param side 조건 충족 시 실행할 매수 또는 매도 방향
 * @param triggerPrice 주문을 발동할 감시가격
 * @param orderPrice 발동 후 제출할 지정가이며 시장가이면 null
 * @param confirmHighValueOrder 1억원 이상 국내 주문을 사용자가 확인했는지 여부
 */
public record SingleConditionalOrderSubmissionRequest(
		String clientOrderId,
		String symbol,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		OrderSide side,
		BigDecimal triggerPrice,
		BigDecimal orderPrice,
		boolean confirmHighValueOrder) {

	/**
	 * 로그에 식별값과 모든 금융값이 노출되지 않도록 안전한 설명만 반환합니다.
	 *
	 * @return 민감한 값이 제거된 조건 주문 제출 요청 설명
	 */
	@Override
	public String toString() {
		return "SingleConditionalOrderSubmissionRequest[clientOrderId=***, symbol=" + symbol
				+ ", quantity=***, orderType=" + orderType + ", expireDate=" + expireDate
				+ ", side=" + side + ", triggerPrice=***, orderPrice=***"
				+ ", confirmHighValueOrder=" + confirmHighValueOrder + "]";
	}
}

package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 단일 조건 주문 미리보기를 만들기 위해 사용자가 입력하는 값입니다.
 *
 * @param accountSeq 주문에 사용할 계좌 식별값
 * @param symbol 감시할 종목 코드
 * @param side 조건 충족 시 실행할 매수 또는 매도 방향
 * @param orderType 지정가 또는 시장가
 * @param quantity 주문 수량
 * @param triggerPrice 주문을 발동할 감시가격
 * @param orderPrice 발동 후 제출할 지정가이며 시장가이면 null
 * @param expireDate 조건 감시 만료일
 */
public record SingleConditionalOrderPreviewRequest(
		long accountSeq,
		String symbol,
		OrderSide side,
		OrderType orderType,
		BigDecimal quantity,
		BigDecimal triggerPrice,
		BigDecimal orderPrice,
		LocalDate expireDate) {

	/**
	 * 로그에 계좌와 금융값이 노출되지 않도록 안전한 설명만 반환합니다.
	 *
	 * @return 민감한 값이 제거된 미리보기 요청 설명
	 */
	@Override
	public String toString() {
		return "SingleConditionalOrderPreviewRequest[accountSeq=***, symbol=" + symbol
				+ ", side=" + side + ", orderType=" + orderType + ", quantity=***"
				+ ", triggerPrice=***, orderPrice=***, expireDate=" + expireDate + "]";
	}
}

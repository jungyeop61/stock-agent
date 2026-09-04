package com.jusika.backend.orderpreview;

import java.math.BigDecimal;

/**
 * 실제 주문을 보내기 전에 검증하고 계산할 수량 기반 주문 내용을 표현합니다.
 *
 * @param accountSeq 주문에 사용할 계좌 식별값
 * @param symbol 주문할 국내 또는 미국 주식 종목 코드
 * @param side 매수 또는 매도 방향
 * @param orderType 지정가 또는 시장가 유형
 * @param quantity 주문할 주식 수량
 * @param price 지정가 주문 가격이며 시장가 주문이면 null
 */
public record OrderPreviewRequest(
		long accountSeq,
		String symbol,
		OrderSide side,
		OrderType orderType,
		BigDecimal quantity,
		BigDecimal price) {

	/**
	 * 요청 객체가 로그에 기록되더라도 계좌 식별값과 주문 수량·가격이 노출되지 않도록 가립니다.
	 *
	 * @return 금융정보가 제거된 요청 설명
	 */
	@Override
	public String toString() {
		return "OrderPreviewRequest[accountSeq=***, symbol=" + symbol
				+ ", side=" + side + ", orderType=" + orderType
				+ ", quantity=***, price=***]";
	}
}

package com.jusika.backend.amountorderpreview;

import java.math.BigDecimal;

/**
 * 미국 주식 달러 금액 기반 시장가 매수 미리보기에 사용할 내용을 표현합니다.
 *
 * @param accountSeq 주문 가능 금액을 확인할 계좌 식별값
 * @param symbol 매수할 미국 주식 종목 코드
 * @param orderAmount 매수에 사용할 달러 금액
 */
public record AmountOrderPreviewRequest(
		long accountSeq,
		String symbol,
		BigDecimal orderAmount) {

	/**
	 * 요청 객체가 로그에 기록되더라도 계좌 식별값과 주문 금액이 노출되지 않도록 가립니다.
	 *
	 * @return 금융정보가 제거된 요청 설명
	 */
	@Override
	public String toString() {
		return "AmountOrderPreviewRequest[accountSeq=***, symbol=" + symbol
				+ ", orderAmount=***]";
	}
}

package com.jusika.backend.orderhistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.orderpreview.OrderSide;

/**
 * 토스증권에서 조회한 한 주문의 현재 처리 상태와 누적 체결 결과를 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param orderId 토스증권 주문 식별값
 * @param symbol 주문 종목 코드
 * @param side 매수 또는 매도 방향
 * @param orderTypeCode 토스증권 원본 호가 유형 코드
 * @param timeInForceCode 토스증권 원본 주문 유효 조건 코드
 * @param status 우리 서버가 해석한 주문 상태
 * @param brokerStatusCode 토스증권 원본 주문 상태 코드
 * @param price 지정가이며 시장가 주문이면 null
 * @param quantity 주문 수량
 * @param orderAmount 미국 주식 금액 주문의 달러 금액이며 수량 주문이면 null
 * @param currency 거래 통화 코드
 * @param orderedAt 주문 접수 시각
 * @param canceledAt 취소 시각이며 취소되지 않았으면 null
 * @param execution 누적 체결 결과
 */
public record OrderDetailResponse(
		long accountSeq,
		String orderId,
		String symbol,
		OrderSide side,
		String orderTypeCode,
		String timeInForceCode,
		OrderStatus status,
		String brokerStatusCode,
		BigDecimal price,
		BigDecimal quantity,
		BigDecimal orderAmount,
		String currency,
		OffsetDateTime orderedAt,
		OffsetDateTime canceledAt,
		ExecutionDetail execution) {

	/**
	 * 로그에 주문 식별값과 금융값이 노출되지 않도록 안전한 설명만 반환합니다.
	 *
	 * @return 민감한 주문 값이 제거된 주문 상태 설명
	 */
	@Override
	public String toString() {
		return "OrderDetailResponse[accountSeq=***, orderId=***, symbol=" + symbol
				+ ", side=" + side + ", orderTypeCode=" + orderTypeCode
				+ ", timeInForceCode=" + timeInForceCode + ", status=" + status
				+ ", brokerStatusCode=" + brokerStatusCode
				+ ", price=***, quantity=***, orderAmount=***, currency=" + currency
				+ ", orderedAt=" + orderedAt + ", canceledAt=" + canceledAt
				+ ", execution=***]";
	}

	/**
	 * 한 주문에서 지금까지 누적된 체결 수량·금액·비용과 결제일을 표현합니다.
	 *
	 * @param filledQuantity 누적 체결 수량
	 * @param averageFilledPrice 평균 체결 가격이며 미체결이면 null
	 * @param filledAmount 누적 체결 금액이며 미체결이면 null
	 * @param commission 누적 체결 수수료이며 미체결이면 null
	 * @param tax 누적 체결 세금이며 미체결이면 null
	 * @param filledAt 마지막 체결 시각이며 미체결이면 null
	 * @param settlementDate 결제 예정일이며 아직 정해지지 않았으면 null
	 */
	public record ExecutionDetail(
			BigDecimal filledQuantity,
			BigDecimal averageFilledPrice,
			BigDecimal filledAmount,
			BigDecimal commission,
			BigDecimal tax,
			OffsetDateTime filledAt,
			LocalDate settlementDate) {

		/**
		 * 로그에 실제 체결 금융값이 노출되지 않도록 모든 금액과 수량을 가립니다.
		 *
		 * @return 금융값이 제거된 누적 체결 설명
		 */
		@Override
		public String toString() {
			return "ExecutionDetail[filledQuantity=***, averageFilledPrice=***"
					+ ", filledAmount=***, commission=***, tax=***"
					+ ", filledAt=" + filledAt + ", settlementDate=" + settlementDate + "]";
		}
	}
}

package com.jusika.backend.ordercancellation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderSide;

/**
 * 사용자가 승인하기 전에 읽어 줄 취소 대상 주문의 변경 불가 정보를 표현합니다.
 *
 * @param previewId 우리 서버가 만든 취소 미리보기 식별값
 * @param createdAt 미리보기 생성 시각
 * @param expiresAt 승인 유효시간 종료 시각
 * @param accountSeq 취소 대상 주문의 계좌 식별값
 * @param orderId 취소 대상 토스증권 주문 식별값
 * @param symbol 주문 종목 코드
 * @param side 원주문의 매수 또는 매도 방향
 * @param orderTypeCode 토스증권 원본 주문 유형 코드
 * @param originalStatus 미리보기 생성 때 확인한 주문 상태
 * @param price 원주문 지정가이며 시장가이면 null
 * @param quantity 원주문 수량이며 금액 주문이면 null일 수 있음
 * @param filledQuantity 지금까지 체결된 수량
 * @param remainingQuantity 아직 체결되지 않은 수량이며 금액 주문이면 null일 수 있음
 * @param orderAmount 미국 주식 금액 주문의 달러 금액이며 수량 주문이면 null
 * @param currency 거래 통화 코드
 * @param status 취소 미리보기의 승인·실행 상태
 * @param approvedAt 사용자 승인 시각이며 승인 전에는 null
 */
public record OrderCancellationPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		OffsetDateTime expiresAt,
		long accountSeq,
		String orderId,
		String symbol,
		OrderSide side,
		String orderTypeCode,
		OrderStatus originalStatus,
		BigDecimal price,
		BigDecimal quantity,
		BigDecimal filledQuantity,
		BigDecimal remainingQuantity,
		BigDecimal orderAmount,
		String currency,
		OrderCancellationPreviewStatus status,
		OffsetDateTime approvedAt) {

	/**
	 * 객체가 로그에 기록되더라도 주문 식별값과 금융값이 노출되지 않도록 가립니다.
	 *
	 * @return 민감한 주문 값이 제거된 미리보기 설명
	 */
	@Override
	public String toString() {
		return "OrderCancellationPreviewResponse[previewId=***, accountSeq=***, orderId=***"
				+ ", symbol=" + symbol + ", side=" + side
				+ ", orderTypeCode=" + orderTypeCode + ", originalStatus=" + originalStatus
				+ ", price=***, quantity=***, filledQuantity=***, remainingQuantity=***"
				+ ", orderAmount=***, currency=" + currency + ", status=" + status
				+ ", createdAt=" + createdAt + ", expiresAt=" + expiresAt
				+ ", approvedAt=" + approvedAt + "]";
	}
}

package com.jusika.backend.amountorderpreview;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 실제 주문 없이 검증과 계산을 마친 미국 주식 달러 금액 매수 미리보기를 표현합니다.
 *
 * @param previewId 미리보기를 구분할 임시 식별값
 * @param createdAt 미리보기를 만든 시각
 * @param expiresAt 향후 승인을 받을 수 있는 마지막 시각
 * @param accountSeq 검증한 계좌 식별값
 * @param symbol 검증한 미국 주식 종목 코드
 * @param side 금액 매수에 고정된 주문 방향
 * @param orderType 금액 매수에 고정된 시장가 유형
 * @param orderAmount 사용자가 입력한 달러 주문 금액
 * @param currency 거래 통화인 USD
 * @param marketCountry 거래 시장 국가 코드인 US
 * @param referencePrice 예상 수량 계산에 사용한 현재가
 * @param estimatedQuantity 현재가로 계산한 참고용 예상 수량
 * @param commissionRate 계좌에 적용되는 미국 시장 수수료율
 * @param estimatedCommission 예상 달러 수수료
 * @param estimatedTotalCost 주문 금액과 예상 수수료를 더한 달러 필요 금액
 * @param exchangeRate 고액 여부 계산에 사용한 USD→KRW 참고 환율
 * @param exchangeRateValidFrom 참고 환율 유효 시작 시각
 * @param exchangeRateValidUntil 참고 환율 유효 종료 시각
 * @param estimatedOrderAmountKrw 주문 금액을 참고 환율로 환산한 원화 금액
 * @param requiresHighValueConfirmation 원화 환산액이 1억원 이상인지 여부
 * @param orderReady 입력과 계좌 여력 검증을 통과했는지 여부
 * @param status 미리보기의 현재 상태
 * @param approvedAt 사용자가 승인한 시각이며 승인 전이면 null
 */
public record AmountOrderPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		OffsetDateTime expiresAt,
		long accountSeq,
		String symbol,
		OrderSide side,
		OrderType orderType,
		BigDecimal orderAmount,
		String currency,
		String marketCountry,
		BigDecimal referencePrice,
		BigDecimal estimatedQuantity,
		BigDecimal commissionRate,
		BigDecimal estimatedCommission,
		BigDecimal estimatedTotalCost,
		BigDecimal exchangeRate,
		OffsetDateTime exchangeRateValidFrom,
		OffsetDateTime exchangeRateValidUntil,
		BigDecimal estimatedOrderAmountKrw,
		boolean requiresHighValueConfirmation,
		boolean orderReady,
		OrderPreviewStatus status,
		OffsetDateTime approvedAt) {
}

package com.jusika.backend.orderpreview;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 외부 주문을 전송하지 않고 검증과 예상 계산만 마친 주문 미리보기를 표현합니다.
 *
 * @param previewId 화면과 음성 안내에서 미리보기를 구분할 임시 식별값
 * @param createdAt 미리보기를 만든 시각
 * @param accountSeq 검증한 계좌 식별값
 * @param symbol 검증한 종목 코드
 * @param side 매수 또는 매도 방향
 * @param orderType 지정가 또는 시장가 유형
 * @param quantity 주문할 주식 수량
 * @param requestedPrice 사용자가 입력한 지정가이며 시장가 주문이면 null
 * @param referencePrice 계산 시점의 현재가
 * @param calculationPrice 예상 금액 계산에 사용한 지정가 또는 현재가
 * @param currency 거래 통화 코드
 * @param marketCountry 거래 시장의 국가 코드
 * @param commissionRate 계좌에 적용되는 소수 비율 형태의 수수료율
 * @param estimatedOrderAmount 수량과 계산 가격을 곱한 예상 주문금액
 * @param estimatedCommission 예상 주문금액과 수수료율로 계산한 예상 수수료
 * @param estimatedAmountAfterCommission 매수는 수수료를 더하고 매도는 수수료를 뺀 예상 금액
 * @param sellTaxExcluded 매도 세금이 예상 금액 계산에서 제외되었는지 여부
 * @param requiresHighValueConfirmation 국내 1억원 이상 주문이라 추가 확인이 필요한지 여부
 * @param orderReady 입력값과 계좌의 금액 또는 수량 검증을 통과했는지 여부
 */
public record OrderPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		long accountSeq,
		String symbol,
		OrderSide side,
		OrderType orderType,
		BigDecimal quantity,
		BigDecimal requestedPrice,
		BigDecimal referencePrice,
		BigDecimal calculationPrice,
		String currency,
		String marketCountry,
		BigDecimal commissionRate,
		BigDecimal estimatedOrderAmount,
		BigDecimal estimatedCommission,
		BigDecimal estimatedAmountAfterCommission,
		boolean sellTaxExcluded,
		boolean requiresHighValueConfirmation,
		boolean orderReady) {
}

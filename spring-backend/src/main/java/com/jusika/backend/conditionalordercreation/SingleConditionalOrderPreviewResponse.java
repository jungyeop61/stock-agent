package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 사용자가 승인하기 전에 읽어 줄 단일 조건 주문의 변경 불가 사본입니다.
 *
 * @param previewId 우리 서버가 만든 미리보기 식별값
 * @param createdAt 미리보기 생성 시각
 * @param expiresAt 승인 유효시간 종료 시각
 * @param accountSeq 주문에 사용할 계좌 식별값
 * @param symbol 종목 코드
 * @param conditionalOrderType 현재 단계에서 지원하는 SINGLE 유형
 * @param side 조건 충족 시 실행할 매수 또는 매도 방향
 * @param orderType 지정가 또는 시장가
 * @param quantity 주문 수량
 * @param triggerPrice 주문을 발동할 감시가격
 * @param orderPrice 발동 후 제출할 지정가이며 시장가이면 null
 * @param expireDate 조건 감시 만료일
 * @param referencePrice 미리보기 시점의 현재가
 * @param calculationPrice 예상 금액 계산에 사용한 가격
 * @param currency 원화 또는 달러 통화 코드
 * @param marketCountry 국내 또는 미국 시장 코드
 * @param commissionRate 계산에 사용한 수수료율
 * @param estimatedOrderAmount 예상 주문금액
 * @param estimatedCommission 예상 수수료
 * @param estimatedAmountAfterCommission 수수료를 반영한 예상 지출 또는 수령액
 * @param sellTaxExcluded 매도 세금이 예상 금액에 포함되지 않았으면 true
 * @param requiresHighValueConfirmation 국내 예상 주문금액이 1억원 이상이면 true
 * @param status 승인·만료·사용 상태
 * @param approvedAt 사용자가 승인한 시각이며 승인 전이면 null
 */
public record SingleConditionalOrderPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		OffsetDateTime expiresAt,
		long accountSeq,
		String symbol,
		ConditionalOrderType conditionalOrderType,
		OrderSide side,
		OrderType orderType,
		BigDecimal quantity,
		BigDecimal triggerPrice,
		BigDecimal orderPrice,
		LocalDate expireDate,
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
		OrderPreviewStatus status,
		OffsetDateTime approvedAt) {

	/**
	 * 로그에 미리보기 식별값·계좌·가격·수량·예상 금액이 노출되지 않도록 가립니다.
	 *
	 * @return 민감한 값이 제거된 조건 주문 미리보기 설명
	 */
	@Override
	public String toString() {
		return "SingleConditionalOrderPreviewResponse[previewId=***, accountSeq=***"
				+ ", symbol=" + symbol + ", conditionalOrderType=" + conditionalOrderType
				+ ", side=" + side + ", orderType=" + orderType + ", quantity=***"
				+ ", triggerPrice=***, orderPrice=***, expireDate=" + expireDate
				+ ", referencePrice=***, calculationPrice=***, currency=" + currency
				+ ", marketCountry=" + marketCountry + ", commissionRate=***"
				+ ", estimatedOrderAmount=***, estimatedCommission=***"
				+ ", estimatedAmountAfterCommission=***, sellTaxExcluded=" + sellTaxExcluded
				+ ", requiresHighValueConfirmation=" + requiresHighValueConfirmation
				+ ", status=" + status + ", createdAt=" + createdAt
				+ ", expiresAt=" + expiresAt + ", approvedAt=" + approvedAt + "]";
	}
}

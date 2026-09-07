package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 사용자가 승인하기 전에 읽어 줄 OCO 조건 주문의 변경 불가 사본입니다.
 *
 * @param previewId 우리 서버가 만든 미리보기 식별값
 * @param createdAt 미리보기 생성 시각
 * @param expiresAt 승인 유효시간 종료 시각
 * @param accountSeq 주문에 사용할 계좌 식별값
 * @param symbol 종목 코드
 * @param conditionalOrderType OCO 조건 주문 유형
 * @param quantity 두 조건이 공통으로 사용할 매도 수량
 * @param orderType 지정가 주문 유형
 * @param expireDate 두 조건의 공통 감시 만료일
 * @param referencePrice 미리보기 시점의 현재가
 * @param currency 원화 또는 달러 통화 코드
 * @param marketCountry 국내 또는 미국 시장 코드
 * @param commissionRate 계산에 사용한 매도 수수료율
 * @param first 현재가보다 높은 첫 번째 매도 조건의 예상 결과
 * @param second 현재가보다 낮은 두 번째 매도 조건의 예상 결과
 * @param sellTaxExcluded 매도 세금이 예상 수령액에 포함되지 않았으면 true
 * @param requiresHighValueConfirmation 두 조건 중 하나라도 국내 주문금액이 1억원 이상이면 true
 * @param status 승인·만료·사용 상태
 * @param approvedAt 사용자가 승인한 시각이며 승인 전이면 null
 */
public record OcoConditionalOrderPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		OffsetDateTime expiresAt,
		long accountSeq,
		String symbol,
		ConditionalOrderType conditionalOrderType,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		BigDecimal referencePrice,
		String currency,
		String marketCountry,
		BigDecimal commissionRate,
		Condition first,
		Condition second,
		boolean sellTaxExcluded,
		boolean requiresHighValueConfirmation,
		OrderPreviewStatus status,
		OffsetDateTime approvedAt) {

	/** 로그에 식별값·계좌·수량·가격·예상 금액이 노출되지 않도록 가립니다. */
	@Override
	public String toString() {
		return "OcoConditionalOrderPreviewResponse[previewId=***, accountSeq=***"
				+ ", symbol=" + symbol + ", conditionalOrderType=" + conditionalOrderType
				+ ", quantity=***, orderType=" + orderType + ", expireDate=" + expireDate
				+ ", referencePrice=***, currency=" + currency
				+ ", marketCountry=" + marketCountry + ", commissionRate=***"
				+ ", first=***, second=***, sellTaxExcluded=" + sellTaxExcluded
				+ ", requiresHighValueConfirmation=" + requiresHighValueConfirmation
				+ ", status=" + status + ", createdAt=" + createdAt
				+ ", expiresAt=" + expiresAt + ", approvedAt=" + approvedAt + "]";
	}

	/**
	 * 한 OCO 감시 조건과 해당 조건 발동 시 예상되는 매도 결과를 표현합니다.
	 *
	 * @param side 매도 주문 방향
	 * @param triggerPrice 주문을 발동할 감시가격
	 * @param orderPrice 발동 후 제출할 매도 지정가
	 * @param estimatedOrderAmount 예상 매도 주문금액
	 * @param estimatedCommission 예상 매도 수수료
	 * @param estimatedProceedsAfterCommission 수수료를 제외한 예상 수령액
	 */
	public record Condition(
			OrderSide side,
			BigDecimal triggerPrice,
			BigDecimal orderPrice,
			BigDecimal estimatedOrderAmount,
			BigDecimal estimatedCommission,
			BigDecimal estimatedProceedsAfterCommission) {

		/** 로그에 가격과 예상 금융값이 노출되지 않도록 가립니다. */
		@Override
		public String toString() {
			return "Condition[side=" + side + ", triggerPrice=***, orderPrice=***"
					+ ", estimatedOrderAmount=***, estimatedCommission=***"
					+ ", estimatedProceedsAfterCommission=***]";
		}
	}
}

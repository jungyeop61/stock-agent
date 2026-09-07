package com.jusika.backend.conditionalordercancellation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 사용자가 승인하기 전에 확인할 조건 주문 취소 대상의 변경 불가 사본입니다.
 *
 * @param previewId 우리 서버가 만든 취소 미리보기 식별값
 * @param createdAt 미리보기 생성 시각
 * @param expiresAt 미리보기 유효시간 종료 시각
 * @param accountSeq 취소 대상 계좌 식별값
 * @param conditionalOrderId 취소 대상 토스증권 조건 주문 식별값
 * @param conditionalOrderType SINGLE·OCO·OTO 조건 주문 유형
 * @param originalStatus 미리보기 생성 때 확인한 조건 주문 전체 상태
 * @param symbol 종목 코드
 * @param market 국내 또는 미국 시장
 * @param quantity 조건 주문 수량
 * @param orderType 지정가 또는 시장가 주문 유형
 * @param expireDate 조건 주문 만료일
 * @param first 첫 번째 감시 조건의 변경 불가 사본
 * @param second 두 번째 감시 조건이며 SINGLE이면 null
 * @param conditionalOrderCreatedAt 원래 조건 주문 등록 시각
 * @param status 취소 미리보기 승인·실행 상태
 * @param approvedAt 사용자 승인 시각이며 승인 전에는 null
 */
public record ConditionalOrderCancellationPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		OffsetDateTime expiresAt,
		long accountSeq,
		String conditionalOrderId,
		ConditionalOrderType conditionalOrderType,
		ConditionalOrderStatus originalStatus,
		String symbol,
		ConditionalOrderMarket market,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		ConditionSnapshot first,
		ConditionSnapshot second,
		OffsetDateTime conditionalOrderCreatedAt,
		ConditionalOrderCancellationPreviewStatus status,
		OffsetDateTime approvedAt) {

	/** 로그에 식별값과 금융값이 노출되지 않는 미리보기 설명을 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderCancellationPreviewResponse[previewId=***, accountSeq=***"
				+ ", conditionalOrderId=***, conditionalOrderType=" + conditionalOrderType
				+ ", originalStatus=" + originalStatus + ", symbol=" + symbol
				+ ", market=" + market + ", quantity=***, orderType=" + orderType
				+ ", expireDate=" + expireDate + ", first=***, second=***"
				+ ", conditionalOrderCreatedAt=" + conditionalOrderCreatedAt
				+ ", status=" + status + ", createdAt=" + createdAt
				+ ", expiresAt=" + expiresAt + ", approvedAt=" + approvedAt + "]";
	}

	/**
	 * 조건 주문의 한 감시 조건을 승인 시점 그대로 보관합니다.
	 *
	 * @param type 가격 또는 수익률 감시 유형
	 * @param status 개별 감시 조건 상태
	 * @param triggerPrice 발동 가격
	 * @param targetProfitRate 목표 수익률 퍼센트
	 * @param orderPrice 발동 후 제출할 지정가
	 * @param triggeredOrderId 이미 발동해 만들어진 일반 주문 식별값
	 */
	public record ConditionSnapshot(
			ConditionalOrderConditionType type,
			ConditionalOrderConditionStatus status,
			BigDecimal triggerPrice,
			BigDecimal targetProfitRate,
			BigDecimal orderPrice,
			String triggeredOrderId) {

		/** 로그에 가격과 주문 식별값이 노출되지 않는 조건 설명을 반환합니다. */
		@Override
		public String toString() {
			return "ConditionSnapshot[type=" + type + ", status=" + status
					+ ", triggerPrice=***, targetProfitRate=***, orderPrice=***"
					+ ", triggeredOrderId=***]";
		}
	}
}

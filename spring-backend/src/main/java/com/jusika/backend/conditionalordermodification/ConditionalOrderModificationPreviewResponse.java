package com.jusika.backend.conditionalordermodification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 사용자가 승인할 원주문 사본과 정정 후 전체 조건의 변경 불가 미리보기입니다.
 *
 * @param previewId 우리 서버의 정정 미리보기 식별값
 * @param createdAt 미리보기 생성 시각
 * @param expiresAt 승인과 실행 유효시간 종료 시각
 * @param accountSeq 정정 대상 계좌 식별값
 * @param originalConditionalOrderId 기존 조건 주문 식별값
 * @param originalType 기존 조건 주문 유형
 * @param originalStatus 기존 조건 주문 전체 상태
 * @param symbol 기존 조건 주문의 종목 코드
 * @param market 기존 조건 주문의 시장
 * @param originalQuantity 기존 공통 주문 수량
 * @param originalOrderType 기존 지정가 또는 시장가 유형
 * @param originalExpireDate 기존 조건 감시 만료일
 * @param originalFirst 기존 첫 번째 조건 사본
 * @param originalSecond 기존 두 번째 조건 사본
 * @param originalCreatedAt 기존 조건 주문 생성 시각
 * @param requestedType 정정 후 조건 주문 유형
 * @param requestedQuantity 정정 후 공통 주문 수량
 * @param requestedOrderType 정정 후 지정가 또는 시장가 유형
 * @param requestedExpireDate 정정 후 조건 감시 만료일
 * @param requestedFirst 정정 후 첫 번째 조건
 * @param requestedSecond 정정 후 두 번째 조건
 * @param referencePrice 미리보기 시점의 현재가
 * @param currency 현재가가 나타내는 통화
 * @param requiresHighValueConfirmation 국내 1억원 이상 주문 확인 필요 여부
 * @param status 미리보기 승인·만료·사용 상태
 * @param approvedAt 사용자가 승인한 시각이며 승인 전이면 null
 */
public record ConditionalOrderModificationPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		OffsetDateTime expiresAt,
		long accountSeq,
		String originalConditionalOrderId,
		ConditionalOrderType originalType,
		ConditionalOrderStatus originalStatus,
		String symbol,
		ConditionalOrderMarket market,
		BigDecimal originalQuantity,
		OrderType originalOrderType,
		LocalDate originalExpireDate,
		OriginalCondition originalFirst,
		OriginalCondition originalSecond,
		OffsetDateTime originalCreatedAt,
		ConditionalOrderType requestedType,
		BigDecimal requestedQuantity,
		OrderType requestedOrderType,
		LocalDate requestedExpireDate,
		RequestedCondition requestedFirst,
		RequestedCondition requestedSecond,
		BigDecimal referencePrice,
		String currency,
		boolean requiresHighValueConfirmation,
		ConditionalOrderModificationPreviewStatus status,
		OffsetDateTime approvedAt) {

	/** 로그에 모든 계좌·식별값·금융값이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderModificationPreviewResponse[previewId=***, accountSeq=***"
				+ ", originalConditionalOrderId=***, originalType=" + originalType
				+ ", originalStatus=" + originalStatus + ", symbol=" + symbol
				+ ", market=" + market + ", originalQuantity=***, originalOrderType="
				+ originalOrderType + ", originalExpireDate=" + originalExpireDate
				+ ", originalFirst=***, originalSecond=***, originalCreatedAt="
				+ originalCreatedAt + ", requestedType=" + requestedType
				+ ", requestedQuantity=***, requestedOrderType=" + requestedOrderType
				+ ", requestedExpireDate=" + requestedExpireDate
				+ ", requestedFirst=***, requestedSecond=***, referencePrice=***"
				+ ", currency=" + currency + ", requiresHighValueConfirmation="
				+ requiresHighValueConfirmation + ", status=" + status
				+ ", createdAt=" + createdAt + ", expiresAt=" + expiresAt
				+ ", approvedAt=" + approvedAt + "]";
	}

	/**
	 * 승인 시점의 기존 감시 조건 전체를 보관합니다.
	 *
	 * @param type 기존 가격 또는 수익률 조건 유형
	 * @param status 기존 개별 조건 상태
	 * @param triggerPrice 기존 감시가격
	 * @param targetProfitRate 기존 목표 수익률
	 * @param orderPrice 기존 발동 후 주문가격
	 * @param triggeredOrderId 기존 발동 일반 주문 식별값
	 */
	public record OriginalCondition(
			ConditionalOrderConditionType type,
			ConditionalOrderConditionStatus status,
			BigDecimal triggerPrice,
			BigDecimal targetProfitRate,
			BigDecimal orderPrice,
			String triggeredOrderId) {

		/** 로그에 기존 조건 금융값과 주문 식별값이 노출되지 않도록 가립니다. */
		@Override
		public String toString() {
			return "OriginalCondition[type=" + type + ", status=" + status
					+ ", triggerPrice=***, targetProfitRate=***, orderPrice=***"
					+ ", triggeredOrderId=***]";
		}
	}

	/**
	 * 사용자가 요청한 정정 후 한 감시 조건을 보관합니다.
	 *
	 * @param side 조건 충족 시 제출할 주문 방향
	 * @param triggerPrice 주문을 발동할 감시가격
	 * @param orderPrice 발동 후 제출할 지정가이며 시장가이면 null
	 */
	public record RequestedCondition(
			OrderSide side,
			BigDecimal triggerPrice,
			BigDecimal orderPrice) {

		/** 로그에 요청 가격이 노출되지 않도록 안전한 설명만 반환합니다. */
		@Override
		public String toString() {
			return "RequestedCondition[side=" + side
					+ ", triggerPrice=***, orderPrice=***]";
		}
	}
}

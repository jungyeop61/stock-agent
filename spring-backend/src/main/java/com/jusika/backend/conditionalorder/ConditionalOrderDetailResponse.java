package com.jusika.backend.conditionalorder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.orderpreview.OrderType;

/**
 * 한 조건 주문의 전체 상태와 감시 조건을 안전한 자료형으로 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param conditionalOrderId 토스증권 조건 주문 식별값
 * @param type 조건 주문 구성 유형
 * @param status 조건 주문 전체 상태
 * @param symbol 종목 코드
 * @param market 국내 또는 미국 시장
 * @param quantity 주문 수량
 * @param orderType 지정가 또는 시장가
 * @param expireDate 조건 주문 만료일이며 값이 없으면 null
 * @param first 첫 번째 필수 감시 조건
 * @param second 두 번째 감시 조건이며 단일 조건이면 null
 * @param createdAt 조건 주문 등록 시각
 */
public record ConditionalOrderDetailResponse(
		long accountSeq,
		String conditionalOrderId,
		ConditionalOrderType type,
		ConditionalOrderStatus status,
		String symbol,
		ConditionalOrderMarket market,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		Condition first,
		Condition second,
		OffsetDateTime createdAt) {

	/**
	 * 로그에 조건 주문 식별값과 금융값이 노출되지 않도록 안전한 설명만 반환합니다.
	 *
	 * @return 민감한 조건 주문 값이 제거된 상세 설명
	 */
	@Override
	public String toString() {
		return "ConditionalOrderDetailResponse[accountSeq=***, conditionalOrderId=***"
				+ ", type=" + type + ", status=" + status + ", symbol=" + symbol
				+ ", market=" + market + ", quantity=***, orderType=" + orderType
				+ ", expireDate=" + expireDate + ", first=***, second=***"
				+ ", createdAt=" + createdAt + "]";
	}

	/**
	 * 조건 주문을 구성하는 하나의 가격 또는 수익률 감시 조건입니다.
	 *
	 * @param type 감시 기준 유형
	 * @param status 개별 조건 상태
	 * @param triggerPrice 발동 가격이며 가격 조건이 아니면 null
	 * @param targetProfitRate 목표 수익률 퍼센트이며 수익률 조건이 아니면 null
	 * @param orderPrice 발동 후 제출할 지정가이며 시장가이면 null
	 * @param triggeredOrderId 발동으로 생성된 일반 주문 식별값이며 발동 전이면 null
	 */
	public record Condition(
			ConditionalOrderConditionType type,
			ConditionalOrderConditionStatus status,
			BigDecimal triggerPrice,
			BigDecimal targetProfitRate,
			BigDecimal orderPrice,
			String triggeredOrderId) {

		/**
		 * 로그에 가격과 발동 주문 식별값이 노출되지 않도록 안전한 설명만 반환합니다.
		 *
		 * @return 민감한 감시 조건 값이 제거된 설명
		 */
		@Override
		public String toString() {
			return "Condition[type=" + type + ", status=" + status
					+ ", triggerPrice=***, targetProfitRate=***, orderPrice=***"
					+ ", triggeredOrderId=***]";
		}
	}
}

package com.jusika.backend.conditionalorder;

/**
 * 토스증권이 기존 조건 주문을 대체하며 새로 발급한 식별값입니다.
 *
 * @param conditionalOrderId 이후 조회·정정·취소에 사용할 새 조건 주문 식별값
 */
public record ConditionalOrderModificationResponse(String conditionalOrderId) {

	/** 로그에 새 조건 주문 식별값이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderModificationResponse[conditionalOrderId=***]";
	}
}

package com.jusika.backend.conditionalordercancellation;

/**
 * 취소할 토스증권 조건 주문을 지정합니다.
 *
 * @param accountSeq 조건 주문이 속한 계좌 식별값
 * @param conditionalOrderId 취소할 토스증권 조건 주문 식별값
 */
public record ConditionalOrderCancellationPreviewRequest(
		long accountSeq,
		String conditionalOrderId) {

	/** 로그에 계좌와 조건 주문 식별값이 노출되지 않는 설명을 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderCancellationPreviewRequest[accountSeq=***, conditionalOrderId=***]";
	}
}

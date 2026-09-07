package com.jusika.backend.conditionalorder;

/**
 * 토스증권이 조건 주문 생성 요청을 접수하며 반환한 식별값을 표현합니다.
 *
 * @param conditionalOrderId 조회·정정·취소에 사용할 조건 주문 식별값
 * @param clientOrderId 요청에 사용한 우리 서버의 멱등성 식별값
 */
public record ConditionalOrderCreationResponse(
		String conditionalOrderId,
		String clientOrderId) {

	/**
	 * 로그에 조건 주문과 멱등성 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 식별값이 제거된 생성 응답 설명
	 */
	@Override
	public String toString() {
		return "ConditionalOrderCreationResponse[conditionalOrderId=***, clientOrderId=***]";
	}
}

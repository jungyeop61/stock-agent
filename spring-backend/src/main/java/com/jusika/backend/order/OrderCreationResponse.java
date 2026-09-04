package com.jusika.backend.order;

/**
 * 토스증권이 주문 생성 요청을 접수하며 반환한 식별값을 표현합니다.
 *
 * @param orderId 주문 조회·정정·취소에 사용할 토스증권 주문 식별값
 * @param clientOrderId 요청에 사용한 우리 서버의 멱등성 식별값
 */
public record OrderCreationResponse(String orderId, String clientOrderId) {

	/**
	 * 객체가 로그에 기록되더라도 주문 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 주문 식별값이 제거된 응답 설명
	 */
	@Override
	public String toString() {
		return "OrderCreationResponse[orderId=***, clientOrderId=***]";
	}
}

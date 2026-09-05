package com.jusika.backend.order;

/**
 * 토스증권이 주문 취소나 정정 요청을 접수하며 반환한 주문 식별값을 표현합니다.
 *
 * @param orderId 처리 대상이 된 토스증권 주문 식별값
 */
public record OrderOperationResponse(String orderId) {

	/**
	 * 객체가 로그에 기록되더라도 주문 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 주문 식별값이 제거된 처리 결과 설명
	 */
	@Override
	public String toString() {
		return "OrderOperationResponse[orderId=***]";
	}
}

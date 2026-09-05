package com.jusika.backend.ordercancellation;

/**
 * 취소할 토스증권 주문을 지정하는 미리보기 요청입니다.
 *
 * @param accountSeq 주문이 속한 계좌 식별값
 * @param orderId 취소할 토스증권 주문 식별값
 */
public record OrderCancellationPreviewRequest(long accountSeq, String orderId) {

	/**
	 * 객체가 로그에 기록되더라도 계좌와 주문 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 민감한 식별값이 제거된 요청 설명
	 */
	@Override
	public String toString() {
		return "OrderCancellationPreviewRequest[accountSeq=***, orderId=***]";
	}
}

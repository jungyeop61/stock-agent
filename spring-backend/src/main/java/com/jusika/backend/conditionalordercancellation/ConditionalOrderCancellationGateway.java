package com.jusika.backend.conditionalordercancellation;

/** 승인된 조건 주문 취소를 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다. */
interface ConditionalOrderCancellationGateway {

	/** 지정한 계좌의 조건 주문을 취소합니다. */
	void cancelConditionalOrder(long accountSeq, String conditionalOrderId);

	/** 현재 취소 처리가 모의인지 실제인지 식별할 이름을 반환합니다. */
	String mode();
}

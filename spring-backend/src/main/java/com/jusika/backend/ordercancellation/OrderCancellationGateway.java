package com.jusika.backend.ordercancellation;

import com.jusika.backend.order.OrderOperationResponse;

/** 승인된 취소 요청을 모의 처리 또는 향후 실제 증권사로 전달하는 경계입니다. */
interface OrderCancellationGateway {

	/** 계좌의 원주문을 취소하고 취소된 주문 식별값을 반환합니다. */
	OrderOperationResponse cancelOrder(long accountSeq, String orderId);

	/** 현재 취소 처리가 모의인지 실제인지 식별할 이름을 반환합니다. */
	String mode();
}

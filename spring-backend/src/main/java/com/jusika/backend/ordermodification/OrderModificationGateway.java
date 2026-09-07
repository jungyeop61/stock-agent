package com.jusika.backend.ordermodification;

import com.jusika.backend.order.OrderModificationSubmissionRequest;
import com.jusika.backend.order.OrderOperationResponse;

/** 승인된 주문 정정을 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다. */
interface OrderModificationGateway {
	/** 원주문을 검증된 새 유형·수량·가격으로 정정합니다. */
	OrderOperationResponse modifyOrder(
			long accountSeq, String originalOrderId, OrderModificationSubmissionRequest request);
	/** 현재 정정 처리가 모의인지 실제인지 구분할 이름을 반환합니다. */
	String mode();
}

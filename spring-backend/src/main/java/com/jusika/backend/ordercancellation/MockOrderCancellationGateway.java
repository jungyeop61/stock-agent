package com.jusika.backend.ordercancellation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.order.OrderOperationResponse;

/** 외부 증권사에 연결하지 않고 취소 접수 성공을 재현합니다. */
@Component
@ConditionalOnProperty(name = "jusika.broker.mode", havingValue = "mock", matchIfMissing = true)
class MockOrderCancellationGateway implements OrderCancellationGateway {

	/** 네트워크 호출 없이 요청받은 원주문 식별값을 그대로 반환합니다. */
	@Override
	public OrderOperationResponse cancelOrder(long accountSeq, String orderId) {
		return new OrderOperationResponse(orderId);
	}

	/** 실제 주문을 변경하지 않는 모의 모드 이름을 반환합니다. */
	@Override
	public String mode() {
		return "MOCK";
	}
}

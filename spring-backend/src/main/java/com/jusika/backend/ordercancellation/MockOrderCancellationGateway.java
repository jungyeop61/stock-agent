package com.jusika.backend.ordercancellation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.order.OrderOperationResponse;

/** 외부 증권사에 연결하지 않고 새 주문번호가 생기는 취소 접수 성공을 재현합니다. */
@Component
@ConditionalOnProperty(name = "jusika.broker.mode", havingValue = "mock", matchIfMissing = true)
class MockOrderCancellationGateway implements OrderCancellationGateway {

	/** 모의 취소는 실제 증권사 주문을 변경하지 않으므로 별도 차단 없이 사용할 수 있습니다. */
	@Override
	public void requireCancellationAvailable() {
		// MOCK 모드는 실제 네트워크 취소 요청을 만들지 않습니다.
	}

	/** 네트워크 호출 없이 원주문과 다른 모의 취소 주문번호를 반환합니다. */
	@Override
	public OrderOperationResponse cancelOrder(long accountSeq, String orderId) {
		return new OrderOperationResponse("mock-cancel-" + orderId);
	}

	/** 실제 주문을 변경하지 않는 모의 모드 이름을 반환합니다. */
	@Override
	public String mode() {
		return "MOCK";
	}
}

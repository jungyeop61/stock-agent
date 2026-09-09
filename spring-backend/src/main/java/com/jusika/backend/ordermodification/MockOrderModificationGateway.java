package com.jusika.backend.ordermodification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.order.OrderModificationSubmissionRequest;
import com.jusika.backend.order.OrderOperationResponse;

/** 외부 증권사에 연결하지 않고 새 주문번호가 생기는 정정 접수를 재현합니다. */
@Component
@ConditionalOnProperty(name = "jusika.broker.mode", havingValue = "mock", matchIfMissing = true)
class MockOrderModificationGateway implements OrderModificationGateway {
	/** 모의 정정은 실제 증권사 주문을 변경하지 않으므로 별도 차단 없이 사용할 수 있습니다. */
	@Override
	public void requireModificationAvailable() {
		// MOCK 모드는 실제 네트워크 정정 요청을 만들지 않습니다.
	}

	/** 네트워크 호출 없이 원주문과 다른 모의 정정 주문번호를 반환합니다. */
	@Override
	public OrderOperationResponse modifyOrder(
			long accountSeq, String originalOrderId, OrderModificationSubmissionRequest request) {
		return new OrderOperationResponse("mock-modify-" + originalOrderId);
	}
	/** 실제 주문을 바꾸지 않는 모의 모드 이름을 반환합니다. */
	@Override
	public String mode() { return "MOCK"; }
}

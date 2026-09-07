package com.jusika.backend.conditionalordercancellation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 외부 증권사에 연결하지 않고 조건 주문 취소 성공을 재현합니다. */
@Component
@ConditionalOnProperty(name = "jusika.broker.mode", havingValue = "mock", matchIfMissing = true)
class MockConditionalOrderCancellationGateway implements ConditionalOrderCancellationGateway {

	/** 실제 계좌를 변경하지 않고 취소 성공으로 처리합니다. */
	@Override
	public void cancelConditionalOrder(long accountSeq, String conditionalOrderId) {
		// MOCK 단계에서는 외부 호출을 하지 않습니다.
	}

	/** 실제 주문에 영향을 주지 않는 모의 모드 이름을 반환합니다. */
	@Override
	public String mode() {
		return "MOCK";
	}
}

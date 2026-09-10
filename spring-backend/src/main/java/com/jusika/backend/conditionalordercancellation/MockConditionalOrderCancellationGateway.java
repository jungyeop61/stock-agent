package com.jusika.backend.conditionalordercancellation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 외부 증권사에 연결하지 않고 조건 주문 취소 성공을 재현합니다. */
@Component
@ConditionalOnProperty(name = "jusika.broker.mode", havingValue = "mock", matchIfMissing = true)
class MockConditionalOrderCancellationGateway implements ConditionalOrderCancellationGateway {

	/** 모의 취소는 실제 증권사 조건 주문을 변경하지 않으므로 별도 차단 없이 사용할 수 있습니다. */
	@Override
	public void requireCancellationAvailable() {
		// MOCK 모드는 실제 네트워크 취소 요청을 만들지 않습니다.
	}

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

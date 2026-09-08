package com.jusika.backend.amountorderexecution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;

/**
 * 실제 토스증권에 연결하지 않고 금액 주문의 결정적인 모의 주문번호를 반환합니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "mock", matchIfMissing = true)
class MockAmountOrderSubmissionGateway implements AmountOrderSubmissionGateway {

	/**
	 * 네트워크 호출 없이 멱등성 식별값으로 모의 금액 주문번호를 만듭니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 금액 주문
	 * @return 실제 주문이 아닌 모의 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse submitAmountOrder(
			long accountSeq,
			AmountOrderSubmissionRequest request) {
		return new OrderCreationResponse("mock-amount-" + request.clientOrderId(), request.clientOrderId());
	}

	/**
	 * 이 구현이 실제 주문을 보내지 않는 모의 모드임을 반환합니다.
	 *
	 * @return 모의 주문을 뜻하는 MOCK
	 */
	@Override
	public String mode() {
		return "MOCK";
	}
}

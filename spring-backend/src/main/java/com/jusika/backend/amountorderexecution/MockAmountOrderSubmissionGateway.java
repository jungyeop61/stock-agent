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

	/** 모의 금액 주문은 실제 증권사 상태를 바꾸지 않으므로 별도 차단 없이 사용할 수 있습니다. */
	@Override
	public void requireSubmissionAvailable() {
		// MOCK 모드는 실제 네트워크 요청을 만들지 않습니다.
	}

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
	 * 네트워크 호출 없이 최초 제출과 같은 결정적인 모의 금액 주문번호를 다시 반환합니다.
	 *
	 * @param accountSeq 최초 금액 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 완전히 동일한 금액 주문
	 * @return 기존 모의 금액 주문번호를 나타내는 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse recoverAmountOrder(
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

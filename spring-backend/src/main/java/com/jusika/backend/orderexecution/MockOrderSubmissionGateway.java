package com.jusika.backend.orderexecution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;

/**
 * 실제 증권사에 연결하지 않고 안전한 모의 주문번호를 반환합니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "mock", matchIfMissing = true)
class MockOrderSubmissionGateway implements OrderSubmissionGateway {

	/**
	 * 네트워크를 사용하지 않는 MOCK 제출 경계가 항상 사용 가능함을 확인합니다.
	 */
	@Override
	public void requireSubmissionAvailable() {
		// MOCK 구현은 실제 증권사 주문을 전송하지 않으므로 추가 차단 조건이 없습니다.
	}

	/**
	 * 네트워크 호출 없이 멱등성 식별값으로 결정적인 모의 주문번호를 만듭니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 수량 기반 주문
	 * @return 실제 주문이 아닌 모의 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse submitQuantityOrder(
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		return new OrderCreationResponse("mock-" + request.clientOrderId(), request.clientOrderId());
	}

	/**
	 * 네트워크 호출 없이 최초 제출과 같은 결정적인 모의 주문번호를 다시 반환합니다.
	 *
	 * @param accountSeq 최초 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 완전히 동일한 수량 기반 주문
	 * @return 기존 모의 주문번호를 나타내는 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse recoverQuantityOrder(
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		return new OrderCreationResponse("mock-" + request.clientOrderId(), request.clientOrderId());
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

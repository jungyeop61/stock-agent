package com.jusika.backend.conditionalordercreation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.SingleConditionalOrderSubmissionRequest;

/**
 * 외부 증권사에 연결하지 않고 단일 조건 주문 접수를 안전하게 재현합니다.
 */
@Component
@ConditionalOnProperty(name = "jusika.broker.mode", havingValue = "mock", matchIfMissing = true)
class MockSingleConditionalOrderGateway implements SingleConditionalOrderGateway {

	/** 모의 생성은 실제 증권사 조건 주문을 만들지 않으므로 별도 차단 없이 사용할 수 있습니다. */
	@Override
	public void requireSubmissionAvailable() {
		// MOCK 모드는 실제 네트워크 생성 요청을 만들지 않습니다.
	}

	/**
	 * 네트워크 호출 없이 멱등성 식별값으로 결정적인 모의 조건 주문번호를 만듭니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 단일 조건 주문
	 * @return 실제 주문이 아닌 모의 조건 주문 생성 결과
	 */
	@Override
	public ConditionalOrderCreationResponse submit(
			long accountSeq,
			SingleConditionalOrderSubmissionRequest request) {
		return new ConditionalOrderCreationResponse(
				"mock-conditional-" + request.clientOrderId(), request.clientOrderId());
	}

	/**
	 * 실제 조건 주문을 만들지 않는 모의 모드 이름을 반환합니다.
	 *
	 * @return 모의 실행을 뜻하는 MOCK
	 */
	@Override
	public String mode() {
		return "MOCK";
	}
}

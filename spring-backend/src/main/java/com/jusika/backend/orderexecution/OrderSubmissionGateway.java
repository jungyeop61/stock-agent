package com.jusika.backend.orderexecution;

import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;

/**
 * 주문 실행 서비스가 모의 주문과 실제 증권사 구현을 구분하지 않고 호출할 경계입니다.
 */
public interface OrderSubmissionGateway {

	/**
	 * 수량 기반 주문을 현재 설정된 증권사 모드로 제출합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 수량 기반 주문
	 * @return 모의 또는 실제 증권사가 반환한 주문 식별값
	 */
	OrderCreationResponse submitQuantityOrder(long accountSeq, QuantityOrderSubmissionRequest request);

	/**
	 * 현재 주문 제출 구현이 사용하는 안전 모드 이름을 반환합니다.
	 *
	 * @return 예를 들어 MOCK과 같은 주문 제출 모드
	 */
	String mode();
}

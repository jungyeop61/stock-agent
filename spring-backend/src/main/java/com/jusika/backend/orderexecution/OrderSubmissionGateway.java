package com.jusika.backend.orderexecution;

import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;

/**
 * 주문 실행 서비스가 모의 주문과 실제 증권사 구현을 구분하지 않고 호출할 경계입니다.
 */
public interface OrderSubmissionGateway {

	/**
	 * 미리보기 소비나 복구권 확보 전에 현재 주문 제출 경계가 안전하게 사용 가능한지 확인합니다.
	 * 실제 주문 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireSubmissionAvailable();

	/**
	 * 계좌 식별값을 아는 실행 단계에서 제출 경계와 계좌 허용 여부를 함께 확인합니다.
	 * MOCK 구현은 기존 안전 검사만 유지하고 LIVE 구현은 계좌 허용 목록을 추가 검사합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 */
	default void requireSubmissionAvailable(long accountSeq) {
		requireSubmissionAvailable();
	}

	/**
	 * 수량 기반 주문을 현재 설정된 증권사 모드로 제출합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 수량 기반 주문
	 * @return 모의 또는 실제 증권사가 반환한 주문 식별값
	 */
	OrderCreationResponse submitQuantityOrder(long accountSeq, QuantityOrderSubmissionRequest request);

	/**
	 * 결과 불명 주문을 최초 본문과 동일한 멱등성 식별값으로 한 번만 복구합니다.
	 *
	 * @param accountSeq 최초 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 완전히 동일한 수량 기반 주문
	 * @return 기존 주문이 있었다면 그 주문의 식별값을 담은 응답
	 */
	OrderCreationResponse recoverQuantityOrder(long accountSeq, QuantityOrderSubmissionRequest request);

	/**
	 * 현재 주문 제출 구현이 사용하는 안전 모드 이름을 반환합니다.
	 *
	 * @return 예를 들어 MOCK과 같은 주문 제출 모드
	 */
	String mode();
}

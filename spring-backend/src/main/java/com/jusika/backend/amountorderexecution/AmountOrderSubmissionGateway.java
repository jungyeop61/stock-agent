package com.jusika.backend.amountorderexecution;

import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;

/**
 * 금액 주문 실행 서비스가 실제 HTTP 클라이언트를 직접 알지 않도록 분리한 제출 경계입니다.
 */
public interface AmountOrderSubmissionGateway {

	/**
	 * 금액 주문을 현재 설정된 모의 또는 향후 실제 증권사 모드로 제출합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 미국 주식 달러 금액 주문
	 * @return 모의 또는 향후 실제 증권사가 반환한 주문 생성 결과
	 */
	OrderCreationResponse submitAmountOrder(long accountSeq, AmountOrderSubmissionRequest request);

	/**
	 * 결과 불명 금액 주문을 최초 본문과 같은 멱등성 식별값으로 한 번만 복구합니다.
	 *
	 * @param accountSeq 최초 금액 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 완전히 동일한 금액 주문 본문
	 * @return 기존 금액 주문이 있었다면 그 주문의 식별값을 담은 응답
	 */
	OrderCreationResponse recoverAmountOrder(long accountSeq, AmountOrderSubmissionRequest request);

	/**
	 * 현재 금액 주문 제출 구현이 사용하는 안전 모드 이름을 반환합니다.
	 *
	 * @return 예를 들어 MOCK과 같은 주문 제출 모드
	 */
	String mode();
}

package com.jusika.backend.amountorderexecution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.toss.order.TossOrderClient;
import com.jusika.backend.toss.order.TossOrderException;

/**
 * 미국 주식 금액 주문의 실제 토스 클라이언트 연결 위치이며 현재는 전역 안전정책에서 항상 차단합니다.
 * 금액 주문 어댑터 준비 상태가 false이므로 클라이언트 호출 코드까지 도달할 수 없습니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveAmountOrderSubmissionGateway implements AmountOrderSubmissionGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;
	private final TossOrderClient orderClient;

	/**
 	 * 실제 금액 주문 경계 진입을 차단할 중앙 안전정책과 연결 대상 클라이언트를 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 * @param orderClient 안전정책 통과 뒤에만 호출할 토스증권 주문 클라이언트
	 */
	LiveAmountOrderSubmissionGateway(
			BrokerMutationSafetyPolicy safetyPolicy,
			TossOrderClient orderClient) {
		this.safetyPolicy = safetyPolicy;
		this.orderClient = orderClient;
	}

	/**
	 * 금액 주문 상태를 변경하기 전에 전역 LIVE 안전정책을 통과할 수 있는지 확인합니다.
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireSubmissionAvailable() {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.AMOUNT_ORDER_SUBMISSION);
	}

	/**
 	 * 중앙 안전정책을 다시 확인한 뒤 검증된 금액 주문을 토스 클라이언트에 전달합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 미국 주식 달러 금액 주문
	 * @return 현재 단계에서는 절대 반환되지 않는 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse submitAmountOrder(
			long accountSeq,
			AmountOrderSubmissionRequest request) {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.AMOUNT_ORDER_SUBMISSION);
		return createAmountOrder(accountSeq, request);
	}

	/**
 	 * 중앙 안전정책을 다시 확인한 뒤 최초와 동일한 멱등성 요청으로 토스 클라이언트를 호출합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 최초 금액 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 완전히 동일한 금액 주문 본문
	 * @return 현재 단계에서는 절대 반환되지 않는 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse recoverAmountOrder(
			long accountSeq,
			AmountOrderSubmissionRequest request) {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.AMOUNT_ORDER_SUBMISSION);
		return createAmountOrder(accountSeq, request);
	}

	/**
	 * 이 구현이 향후 실제 증권사 제출을 담당할 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 주문 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

	/** 토스 클라이언트 오류를 실행 서비스가 처리하는 확정 거절 또는 결과 불명 오류로 변환합니다. */
	private OrderCreationResponse createAmountOrder(
			long accountSeq,
			AmountOrderSubmissionRequest request) {
		try {
			return orderClient.createAmountOrder(accountSeq, request);
		} catch (TossOrderException exception) {
			throw new OrderSubmissionException(
					"토스증권 금액 주문 제출에 실패했습니다.",
					exception.isSubmissionStateUnknown());
		}
	}
}

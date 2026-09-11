package com.jusika.backend.orderexecution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;
import com.jusika.backend.toss.order.TossOrderClient;
import com.jusika.backend.toss.order.TossOrderException;

/**
 * 일반 수량 주문의 실제 토스 클라이언트 연결 위치이며 현재는 전역 안전정책에서 항상 차단합니다.
 * 수량 주문 어댑터 준비 상태가 false이므로 클라이언트 호출 코드까지 도달할 수 없습니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveOrderSubmissionGateway implements OrderSubmissionGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;
	private final TossOrderClient orderClient;

	/**
 	 * 실제 주문 경계 진입을 차단할 중앙 안전정책과 연결 대상 클라이언트를 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 * @param orderClient 안전정책 통과 뒤에만 호출할 토스증권 주문 클라이언트
	 */
	LiveOrderSubmissionGateway(
			BrokerMutationSafetyPolicy safetyPolicy,
			TossOrderClient orderClient) {
		this.safetyPolicy = safetyPolicy;
		this.orderClient = orderClient;
	}

	/**
	 * 주문 상태를 변경하기 전에 전역 LIVE 안전정책을 통과할 수 있는지 확인합니다.
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireSubmissionAvailable() {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION);
	}

	/** 계좌 식별값을 아는 실행 단계에서 수량 주문 기능과 계좌 허용 목록을 함께 검사합니다. */
	@Override
	public void requireSubmissionAvailable(long accountSeq) {
		requireSubmissionAvailable();
		safetyPolicy.requireLiveAccountAllowed(accountSeq);
	}

	/** 최종 계산한 수량과 주문금액이 LIVE 1회 한도를 넘지 않는지 검사합니다. */
	@Override
	public void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveOrderWithinLimits(riskSnapshot);
	}

	/** 내부 실행 상태 생성 전에 계좌별 일일 누적 위험을 사전 검사합니다. */
	@Override
	public void requireDailyOrderWithinLimits(
			long accountSeq,
			BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveDailyOrderWithinLimits(accountSeq, riskSnapshot);
	}

	/** 안전 복구 전에는 기존 수량 주문 예약을 인식하면서 일일 한도를 검사합니다. */
	@Override
	public void requireDailyOrderWithinLimits(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveDailyOrderWithinLimits(
				accountSeq, reservationKey, riskSnapshot);
	}

	/** 토스 호출 직전에 수량 주문 위험을 일일 누적값에 멱등하게 예약합니다. */
	@Override
	public void reserveDailyOrderRisk(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.reserveLiveDailyOrderRisk(accountSeq, reservationKey, riskSnapshot);
	}

	/**
 	 * 중앙 안전정책을 다시 확인한 뒤 검증된 수량 주문을 토스 클라이언트에 전달합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 수량 기반 주문
	 * @return 현재 단계에서는 절대 반환되지 않는 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse submitQuantityOrder(
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		requireSubmissionAvailable(accountSeq);
		requireOrderWithinLimits(request.riskSnapshot());
		reserveDailyOrderRisk(
				accountSeq, "QUANTITY_ORDER:" + request.clientOrderId(), request.riskSnapshot());
		return createQuantityOrder(accountSeq, request);
	}

	/**
 	 * 중앙 안전정책을 다시 확인한 뒤 최초와 동일한 멱등성 요청으로 토스 클라이언트를 호출합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 최초 주문에 사용한 계좌 식별값
	 * @param request 최초 제출과 완전히 동일한 수량 기반 주문
	 * @return 현재 단계에서는 절대 반환되지 않는 주문 생성 결과
	 */
	@Override
	public OrderCreationResponse recoverQuantityOrder(
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		requireSubmissionAvailable(accountSeq);
		requireOrderWithinLimits(request.riskSnapshot());
		reserveDailyOrderRisk(
				accountSeq, "QUANTITY_ORDER:" + request.clientOrderId(), request.riskSnapshot());
		return createQuantityOrder(accountSeq, request);
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
	private OrderCreationResponse createQuantityOrder(
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		try {
			return orderClient.createQuantityOrder(accountSeq, request);
		} catch (TossOrderException exception) {
			throw new OrderSubmissionException(
					"토스증권 수량 주문 제출에 실패했습니다.",
					exception.isSubmissionStateUnknown());
		}
	}
}

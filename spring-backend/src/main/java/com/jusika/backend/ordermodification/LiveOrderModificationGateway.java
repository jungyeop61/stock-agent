package com.jusika.backend.ordermodification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.order.OrderModificationSubmissionRequest;
import com.jusika.backend.order.OrderOperationResponse;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.toss.order.TossOrderClient;
import com.jusika.backend.toss.order.TossOrderException;

/**
 * 일반 미체결 주문 정정의 실제 토스 클라이언트 연결 위치이며 현재는 전역 안전정책에서 항상 차단합니다.
 * 일반 주문 정정 어댑터 준비 상태가 false이므로 클라이언트 호출 코드까지 도달할 수 없습니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveOrderModificationGateway implements OrderModificationGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;
	private final TossOrderClient orderClient;

	/**
	 * 실제 정정 경계 진입을 차단할 중앙 안전정책과 연결 대상 클라이언트를 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 * @param orderClient 안전정책 통과 뒤에만 호출할 토스증권 주문 클라이언트
	 */
	LiveOrderModificationGateway(
			BrokerMutationSafetyPolicy safetyPolicy,
			TossOrderClient orderClient) {
		this.safetyPolicy = safetyPolicy;
		this.orderClient = orderClient;
	}

	/**
	 * 주문·현재가 조회나 내부 상태 변경 전에 전역 LIVE 안전정책을 통과할 수 있는지 확인합니다.
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireModificationAvailable() {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.NORMAL_ORDER_MODIFICATION);
	}

	/** 계좌 식별값을 아는 실행 단계에서 정정 기능과 계좌 허용 목록을 함께 검사합니다. */
	@Override
	public void requireModificationAvailable(long accountSeq) {
		requireModificationAvailable();
		safetyPolicy.requireLiveAccountAllowed(accountSeq);
	}

	/** 최종 계산한 정정 수량과 주문금액이 LIVE 1회 한도를 넘지 않는지 검사합니다. */
	@Override
	public void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveOrderWithinLimits(riskSnapshot);
	}

	/**
	 * 중앙 안전정책을 다시 확인한 뒤 최종 검증된 정정 내용을 토스 클라이언트에 전달합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 정정할 주문의 계좌 식별값
	 * @param originalOrderId 정정할 원주문 식별값
	 * @param request 최종 재검증을 마친 정정 요청 본문
	 * @return 토스증권이 정정 접수 뒤 새로 발급한 주문 식별값
	 */
	@Override
	public OrderOperationResponse modifyOrder(
			long accountSeq,
			String originalOrderId,
			OrderModificationSubmissionRequest request) {
		requireModificationAvailable(accountSeq);
		requireOrderWithinLimits(request.riskSnapshot());
		try {
			return orderClient.modifyOrder(accountSeq, originalOrderId, request);
		} catch (TossOrderException exception) {
			throw new OrderSubmissionException(
					"토스증권 일반 주문 정정에 실패했습니다.",
					exception.isSubmissionStateUnknown());
		}
	}

	/**
	 * 이 구현이 향후 실제 증권사 정정을 담당할 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 정정 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

}

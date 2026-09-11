package com.jusika.backend.conditionalordercreation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.OtoConditionalOrderSubmissionRequest;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/**
 * OTO 조건 주문의 실제 토스 클라이언트 연결 위치이며 현재는 전역 안전정책에서 항상 차단합니다.
 * OTO 조건 주문 어댑터 준비 상태가 false이므로 클라이언트 호출 코드까지 도달할 수 없습니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveOtoConditionalOrderGateway implements OtoConditionalOrderGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;
	private final TossConditionalOrderClient conditionalOrderClient;

	/**
	 * 실제 OTO 생성 경계 진입을 차단할 중앙 안전정책과 연결 대상 클라이언트를 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 * @param conditionalOrderClient 안전정책 통과 뒤에만 호출할 토스증권 조건 주문 클라이언트
	 */
	LiveOtoConditionalOrderGateway(
			BrokerMutationSafetyPolicy safetyPolicy,
			TossConditionalOrderClient conditionalOrderClient) {
		this.safetyPolicy = safetyPolicy;
		this.conditionalOrderClient = conditionalOrderClient;
	}

	/**
	 * 금융정보 조회나 내부 상태 변경 전에 전역 LIVE 안전정책을 통과할 수 있는지 확인합니다.
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireSubmissionAvailable() {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.OTO_CONDITIONAL_ORDER_CREATION);
	}

	/** 계좌 식별값을 아는 실행 단계에서 OTO 생성 기능과 계좌 허용 목록을 함께 검사합니다. */
	@Override
	public void requireSubmissionAvailable(long accountSeq) {
		requireSubmissionAvailable();
		safetyPolicy.requireLiveAccountAllowed(accountSeq);
	}

	/** OTO 종목이 시장별 LIVE 허용 목록에 등록됐는지 검사합니다. */
	@Override
	public void requireInstrumentAllowed(String symbol, String currency) {
		safetyPolicy.requireLiveInstrumentAllowed(symbol, currency);
	}

	/** 두 조건 중 큰 주문금액과 수량이 LIVE 1회 한도를 넘지 않는지 검사합니다. */
	@Override
	public void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveOrderWithinLimits(riskSnapshot);
	}

	/** 내부 실행 상태 생성 전에 계좌별 일일 누적 OTO 위험을 사전 검사합니다. */
	@Override
	public void requireDailyOrderWithinLimits(
			long accountSeq,
			BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveDailyOrderWithinLimits(accountSeq, riskSnapshot);
	}

	/** 토스 호출 직전에 OTO 위험을 일일 누적값에 멱등하게 예약합니다. */
	@Override
	public void reserveDailyOrderRisk(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.reserveLiveDailyOrderRisk(accountSeq, reservationKey, riskSnapshot);
	}

	/**
	 * 중앙 안전정책을 다시 확인한 뒤 최종 검증된 OTO 조건 주문을 토스 클라이언트에 전달합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 조건 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 OTO 조건 주문
	 * @return 토스증권이 발급한 조건 주문 식별값과 요청 멱등성 식별값
	 */
	@Override
	public ConditionalOrderCreationResponse submit(
			long accountSeq,
			OtoConditionalOrderSubmissionRequest request) {
		requireSubmissionAvailable(accountSeq);
		requireInstrumentAllowed(request.symbol(),
				request.riskSnapshot() == null ? null : request.riskSnapshot().currency());
		requireOrderWithinLimits(request.riskSnapshot());
		reserveDailyOrderRisk(
				accountSeq, "OTO_CONDITIONAL_ORDER:" + request.clientOrderId(),
				request.riskSnapshot());
		return conditionalOrderClient.createOtoConditionalOrder(accountSeq, request);
	}

	/**
	 * 이 구현이 향후 실제 증권사 생성을 담당할 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 조건 주문 생성 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

}

package com.jusika.backend.ordermodification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.brokersafety.BrokerOpenOrderCapacityOperation;
import com.jusika.backend.order.OrderModificationSubmissionRequest;
import com.jusika.backend.order.OrderOperationResponse;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.toss.order.TossOrderClient;
import com.jusika.backend.toss.order.TossOrderException;

/**
 * 일반 미체결 주문 정정의 실제 토스 클라이언트 연결 위치입니다.
 * 중앙 안전정책을 통과한 요청만 실제 클라이언트에 전달합니다.
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
	 * 기본 운영 설정에서는 LIVE 기능과 어댑터 준비 상태가 비활성화되어 안전하게 차단됩니다.
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

	/** 일반 정정 원종목이 시장별 LIVE 허용 목록에 등록됐는지 검사합니다. */
	@Override
	public void requireInstrumentAllowed(String symbol, String currency) {
		safetyPolicy.requireLiveInstrumentAllowed(symbol, currency);
	}

	/** 토스 호출 전에 정정 대상 계좌·종목의 현재 활성 주문 수를 다시 검사합니다. */
	@Override
	public void requireOpenOrderCapacity(
			long accountSeq, String symbol, BrokerOpenOrderCapacityOperation operation) {
		safetyPolicy.requireLiveOpenOrderCapacity(accountSeq, symbol, operation);
	}

	/** 내부 실행 상태 생성 전에 같은 원주문 정정의 1분 빈도를 검사합니다. */
	@Override
	public void requireOrderRateAvailable(
			long accountSeq, String symbol, String reservationKey) {
		safetyPolicy.requireLiveOrderRateAvailable(accountSeq, symbol, reservationKey);
	}

	/** 토스 호출 직전에 일반 주문 정정 빈도를 멱등하게 예약합니다. */
	@Override
	public void reserveOrderRate(long accountSeq, String symbol, String reservationKey) {
		safetyPolicy.reserveLiveOrderRate(accountSeq, symbol, reservationKey);
	}

	/** 최종 계산한 정정 수량과 주문금액이 LIVE 1회 한도를 넘지 않는지 검사합니다. */
	@Override
	public void requireOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveOrderWithinLimits(riskSnapshot);
	}

	/** 내부 실행 상태 생성 전에 계좌별 일일 누적 정정 위험을 사전 검사합니다. */
	@Override
	public void requireDailyOrderWithinLimits(
			long accountSeq,
			BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.requireLiveDailyOrderWithinLimits(accountSeq, riskSnapshot);
	}

	/** 토스 호출 직전에 일반 정정 위험을 일일 누적값에 멱등하게 예약합니다. */
	@Override
	public void reserveDailyOrderRisk(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		safetyPolicy.reserveLiveDailyOrderRisk(accountSeq, reservationKey, riskSnapshot);
	}

	/**
	 * 중앙 안전정책을 다시 확인한 뒤 최종 검증된 정정 내용을 토스 클라이언트에 전달합니다.
	 * 기본 운영 설정에서는 정책 검사가 클라이언트 호출 전에 차단합니다.
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
		requireInstrumentAllowed(request.symbol(), request.currency());
		requireOpenOrderCapacity(
				accountSeq, request.symbol(), BrokerOpenOrderCapacityOperation.REPLACE_OR_RECOVER);
		requireOrderWithinLimits(request.riskSnapshot());
		reserveOrderRate(
				accountSeq, request.symbol(),
				"NORMAL_ORDER_MODIFICATION:" + originalOrderId);
		reserveDailyOrderRisk(
				accountSeq, "NORMAL_ORDER_MODIFICATION:" + originalOrderId,
				request.riskSnapshot());
		BrokerMutationCapability capability =
				BrokerMutationCapability.NORMAL_ORDER_MODIFICATION;
		safetyPolicy.recordBrokerRequestStarted(capability);
		try {
			OrderOperationResponse response = orderClient.modifyOrder(
					accountSeq, originalOrderId, request);
			safetyPolicy.recordBrokerRequestSucceeded(capability);
			return response;
		} catch (TossOrderException exception) {
			safetyPolicy.recordBrokerRequestFailed(
					capability, exception.isSubmissionStateUnknown());
			throw new OrderSubmissionException(
					"토스증권 일반 주문 정정에 실패했습니다.",
					exception.isSubmissionStateUnknown());
		} catch (RuntimeException exception) {
			safetyPolicy.recordBrokerRequestFailed(capability, true);
			throw exception;
		}
	}

	/**
	 * 이 구현이 실제 증권사 정정을 담당하는 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 정정 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

}

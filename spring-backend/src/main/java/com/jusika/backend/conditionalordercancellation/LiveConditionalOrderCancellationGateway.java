package com.jusika.backend.conditionalordercancellation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/**
 * 조건 주문 취소의 실제 토스 클라이언트 연결 위치이며 중앙 안전정책을 통과한 요청만 전달합니다.
 * 현재 운영 설정은 조건 주문 취소 어댑터 준비 상태가 false이므로 클라이언트 호출 전에 차단됩니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveConditionalOrderCancellationGateway implements ConditionalOrderCancellationGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;
	private final TossConditionalOrderClient conditionalOrderClient;

	/**
	 * 실제 조건 주문 취소 경계 진입을 차단할 중앙 안전정책과 연결 대상 클라이언트를 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 * @param conditionalOrderClient 안전정책 통과 뒤에만 호출할 토스증권 조건 주문 클라이언트
	 */
	LiveConditionalOrderCancellationGateway(
			BrokerMutationSafetyPolicy safetyPolicy,
			TossConditionalOrderClient conditionalOrderClient) {
		this.safetyPolicy = safetyPolicy;
		this.conditionalOrderClient = conditionalOrderClient;
	}

	/**
	 * 조건 주문 재조회나 내부 상태 변경 전에 전역 LIVE 안전정책을 통과할 수 있는지 확인합니다.
	 * 현재 운영 설정은 실제 어댑터 연결 상태가 false이므로 안전하게 차단됩니다.
	 */
	@Override
	public void requireCancellationAvailable() {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.CONDITIONAL_ORDER_CANCELLATION);
	}

	/** 계좌 식별값을 아는 실행 단계에서 조건 주문 취소 기능과 계좌 허용 목록을 함께 검사합니다. */
	@Override
	public void requireCancellationAvailable(long accountSeq) {
		requireCancellationAvailable();
		safetyPolicy.requireLiveAccountAllowed(accountSeq);
	}

	/**
	 * 중앙 안전정책을 다시 확인한 뒤 최종 검증된 조건 주문 취소를 토스 클라이언트에 전달합니다.
	 * 현재 운영 설정에서는 준비 상태 검사가 토스 호출 전에 차단합니다.
	 *
	 * @param accountSeq 취소할 조건 주문의 계좌 식별값
	 * @param conditionalOrderId 취소할 조건 주문 식별값
	 */
	@Override
	public void cancelConditionalOrder(long accountSeq, String conditionalOrderId) {
		requireCancellationAvailable(accountSeq);
		BrokerMutationCapability capability =
				BrokerMutationCapability.CONDITIONAL_ORDER_CANCELLATION;
		safetyPolicy.recordBrokerRequestStarted(capability);
		try {
			conditionalOrderClient.cancelConditionalOrder(accountSeq, conditionalOrderId);
			safetyPolicy.recordBrokerRequestSucceeded(capability);
		} catch (OrderSubmissionException exception) {
			safetyPolicy.recordBrokerRequestFailed(
					capability, exception.isSubmissionStateUnknown());
			throw exception;
		} catch (RuntimeException exception) {
			safetyPolicy.recordBrokerRequestFailed(capability, true);
			throw exception;
		}
	}

	/**
	 * 이 구현이 실제 증권사 조건 주문 취소를 담당하는 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 조건 주문 취소 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

}

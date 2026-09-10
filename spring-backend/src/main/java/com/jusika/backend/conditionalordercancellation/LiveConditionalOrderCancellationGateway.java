package com.jusika.backend.conditionalordercancellation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/**
 * 조건 주문 취소의 실제 토스 클라이언트 연결 위치이며 현재는 전역 안전정책에서 항상 차단합니다.
 * 조건 주문 취소 어댑터 준비 상태가 false이므로 클라이언트 호출 코드까지 도달할 수 없습니다.
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
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireCancellationAvailable() {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.CONDITIONAL_ORDER_CANCELLATION);
	}

	/**
	 * 중앙 안전정책을 다시 확인한 뒤 최종 검증된 조건 주문 취소를 토스 클라이언트에 전달합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 취소할 조건 주문의 계좌 식별값
	 * @param conditionalOrderId 취소할 조건 주문 식별값
	 */
	@Override
	public void cancelConditionalOrder(long accountSeq, String conditionalOrderId) {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.CONDITIONAL_ORDER_CANCELLATION);
		conditionalOrderClient.cancelConditionalOrder(accountSeq, conditionalOrderId);
	}

	/**
	 * 이 구현이 향후 실제 증권사 조건 주문 취소를 담당할 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 조건 주문 취소 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

}

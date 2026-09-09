package com.jusika.backend.ordercancellation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.order.OrderOperationResponse;

/**
 * 일반 미체결 주문 취소의 향후 실제 실행 위치를 표시하되 현재는 전역 안전정책에서 항상 차단합니다.
 * 토스 주문 클라이언트를 의존성으로 받지 않으므로 이 구현만으로 실제 주문을 취소할 수 없습니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveOrderCancellationGateway implements OrderCancellationGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;

	/**
	 * 실제 취소 경계 진입을 최종 차단할 중앙 안전정책만 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 */
	LiveOrderCancellationGateway(BrokerMutationSafetyPolicy safetyPolicy) {
		this.safetyPolicy = safetyPolicy;
	}

	/**
	 * 주문 조회나 내부 상태 변경 전에 전역 LIVE 안전정책을 통과할 수 있는지 확인합니다.
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireCancellationAvailable() {
		safetyPolicy.requireLiveMutationAvailable();
		throw clientNotConnectedException();
	}

	/**
	 * 실제 클라이언트 호출 없이 중앙 안전정책을 다시 확인하고 주문 취소를 차단합니다.
	 *
	 * @param accountSeq 취소할 주문의 계좌 식별값
	 * @param orderId 취소할 원주문 식별값
	 * @return 현재 단계에서는 절대 반환되지 않는 취소 접수 결과
	 */
	@Override
	public OrderOperationResponse cancelOrder(long accountSeq, String orderId) {
		safetyPolicy.requireLiveMutationAvailable();
		throw clientNotConnectedException();
	}

	/**
	 * 이 구현이 향후 실제 증권사 취소를 담당할 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 취소 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

	/**
	 * 중앙 정책이 향후 열리더라도 클라이언트 연결 전에는 취소하지 못하게 하는 최종 오류를 만듭니다.
	 *
	 * @return 민감정보가 없는 실제 주문 취소 차단 오류
	 */
	private BrokerMutationBlockedException clientNotConnectedException() {
		return new BrokerMutationBlockedException("일반 주문 취소 LIVE 클라이언트가 연결되어 있지 않습니다.");
	}
}

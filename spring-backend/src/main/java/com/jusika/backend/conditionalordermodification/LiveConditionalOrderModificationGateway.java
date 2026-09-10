package com.jusika.backend.conditionalordermodification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationSubmissionRequest;

/**
 * 조건 주문 정정의 향후 실제 실행 위치를 표시하되 현재는 전역 안전정책에서 항상 차단합니다.
 * 토스 조건 주문 클라이언트를 의존성으로 받지 않으므로 기존 주문 취소와 새 주문 생성은 발생하지 않습니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveConditionalOrderModificationGateway implements ConditionalOrderModificationGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;

	/**
	 * 실제 조건 주문 정정 경계 진입을 최종 차단할 중앙 안전정책만 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 */
	LiveConditionalOrderModificationGateway(BrokerMutationSafetyPolicy safetyPolicy) {
		this.safetyPolicy = safetyPolicy;
	}

	/**
	 * 원조건 주문과 금융정보 재조회 또는 내부 상태 변경 전에 LIVE 안전정책을 확인합니다.
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireModificationAvailable() {
		safetyPolicy.requireLiveMutationAvailable();
		throw clientNotConnectedException();
	}

	/**
	 * 실제 클라이언트 호출 없이 중앙 안전정책을 다시 확인하고 조건 주문 정정을 차단합니다.
	 *
	 * @param accountSeq 정정할 조건 주문의 계좌 식별값
	 * @param originalConditionalOrderId 정정으로 취소될 기존 조건 주문 식별값
	 * @param request 최종 재검증을 마친 새 조건 주문 전체 구성
	 * @return 현재 단계에서는 절대 반환되지 않는 새 조건 주문 식별 결과
	 */
	@Override
	public ConditionalOrderModificationResponse modify(
			long accountSeq,
			String originalConditionalOrderId,
			ConditionalOrderModificationSubmissionRequest request) {
		safetyPolicy.requireLiveMutationAvailable();
		throw clientNotConnectedException();
	}

	/**
	 * 이 구현이 향후 실제 증권사 조건 주문 정정을 담당할 LIVE 경계임을 반환합니다.
	 *
	 * @return 실제 조건 주문 정정 경계를 뜻하는 LIVE
	 */
	@Override
	public String mode() {
		return "LIVE";
	}

	/**
	 * 중앙 정책이 향후 열리더라도 클라이언트 연결 전에는 정정하지 못하게 하는 최종 오류를 만듭니다.
	 *
	 * @return 민감정보가 없는 조건 주문 정정 차단 오류
	 */
	private BrokerMutationBlockedException clientNotConnectedException() {
		return new BrokerMutationBlockedException("조건 주문 정정 LIVE 클라이언트가 연결되어 있지 않습니다.");
	}
}

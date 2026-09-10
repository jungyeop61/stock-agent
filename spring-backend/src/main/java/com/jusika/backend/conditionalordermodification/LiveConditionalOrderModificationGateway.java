package com.jusika.backend.conditionalordermodification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationSubmissionRequest;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/**
 * 조건 주문 정정의 실제 토스 클라이언트 연결 위치이며 현재는 전역 안전정책에서 항상 차단합니다.
 * 조건 주문 정정 어댑터 준비 상태가 false이므로 기존 주문 취소와 대체 주문 생성 코드까지 도달할 수 없습니다.
 */
@Component
@ConditionalOnProperty(prefix = "jusika.broker", name = "mode", havingValue = "live")
class LiveConditionalOrderModificationGateway implements ConditionalOrderModificationGateway {

	private final BrokerMutationSafetyPolicy safetyPolicy;
	private final TossConditionalOrderClient conditionalOrderClient;

	/**
	 * 실제 조건 주문 정정 경계 진입을 차단할 중앙 안전정책과 연결 대상 클라이언트를 전달받습니다.
	 *
	 * @param safetyPolicy LIVE 기능 플래그, 긴급 차단 스위치와 어댑터 연결 상태 검사기
	 * @param conditionalOrderClient 안전정책 통과 뒤에만 호출할 토스증권 조건 주문 클라이언트
	 */
	LiveConditionalOrderModificationGateway(
			BrokerMutationSafetyPolicy safetyPolicy,
			TossConditionalOrderClient conditionalOrderClient) {
		this.safetyPolicy = safetyPolicy;
		this.conditionalOrderClient = conditionalOrderClient;
	}

	/**
	 * 원조건 주문과 금융정보 재조회 또는 내부 상태 변경 전에 LIVE 안전정책을 확인합니다.
	 * 현재 실제 어댑터 연결 상태가 false이므로 항상 안전하게 차단됩니다.
	 */
	@Override
	public void requireModificationAvailable() {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.CONDITIONAL_ORDER_MODIFICATION);
	}

	/**
	 * 중앙 안전정책을 다시 확인한 뒤 최종 검증된 새 전체 구성을 토스 클라이언트에 전달합니다.
	 * 현재 준비 상태에서는 정책 검사가 항상 먼저 차단합니다.
	 *
	 * @param accountSeq 정정할 조건 주문의 계좌 식별값
	 * @param originalConditionalOrderId 정정으로 취소될 기존 조건 주문 식별값
	 * @param request 최종 재검증을 마친 새 조건 주문 전체 구성
	 * @return 기존 주문 취소와 대체 주문 생성 뒤 토스증권이 발급한 새 조건 주문 식별값
	 */
	@Override
	public ConditionalOrderModificationResponse modify(
			long accountSeq,
			String originalConditionalOrderId,
			ConditionalOrderModificationSubmissionRequest request) {
		safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.CONDITIONAL_ORDER_MODIFICATION);
		return conditionalOrderClient.modifyConditionalOrder(
				accountSeq, originalConditionalOrderId, request);
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

}

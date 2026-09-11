package com.jusika.backend.brokersafety;

import java.util.List;

/**
 * 비밀정보 없이 현재 증권사 주문 변경 안전장치 상태를 표현합니다.
 *
 * @param mode 현재 요청한 증권사 실행 모드
 * @param liveEnabled 실제 주문 기능 플래그 상태
 * @param killSwitchActive 긴급 차단 스위치 상태
 * @param liveSafetyGateOpen 실제 주문 안전 설정 세 가지가 모두 통과했는지 여부
 * @param liveAdapterConnected 실제 주문 변경 어댑터가 연결되어 있는지 여부
 * @param liveAccountAllowlistConfigured 실제 주문을 허용한 계좌가 하나 이상 있는지 여부
 * @param liveMutationAvailable 현재 실제 주문 변경이 가능한지 여부
 * @param blockReason 실제 주문 변경을 차단하는 우선 사유 또는 모든 검사 통과 상태
 * @param mutationCapabilities 주문 변경 기능별 실제 어댑터 연결 상태
 */
public record BrokerSafetyStatusResponse(
		BrokerExecutionMode mode,
		boolean liveEnabled,
		boolean killSwitchActive,
		boolean liveSafetyGateOpen,
		boolean liveAdapterConnected,
		boolean liveAccountAllowlistConfigured,
		boolean liveMutationAvailable,
		BrokerSafetyBlockReason blockReason,
		List<BrokerMutationCapabilityStatus> mutationCapabilities) {

	/** 응답 생성 뒤 기능별 상태 목록을 외부에서 변경할 수 없도록 복사합니다. */
	public BrokerSafetyStatusResponse {
		mutationCapabilities = List.copyOf(mutationCapabilities);
	}
}

package com.jusika.backend.brokersafety;

/**
 * 민감정보 없이 한 주문 변경 기능의 실제 어댑터 연결 여부를 표현합니다.
 *
 * @param capability 확인할 주문 변경 기능
 * @param liveAdapterConnected 해당 기능의 실제 토스증권 어댑터 연결 여부
 */
public record BrokerMutationCapabilityStatus(
		BrokerMutationCapability capability,
		boolean liveAdapterConnected) {
}

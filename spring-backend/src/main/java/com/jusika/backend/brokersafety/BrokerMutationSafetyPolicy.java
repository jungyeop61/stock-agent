package com.jusika.backend.brokersafety;

import org.springframework.stereotype.Service;

/**
 * 실제 증권사 주문 변경 전에 LIVE 기능 플래그와 긴급 차단 스위치를 중앙에서 검사합니다.
 */
@Service
public class BrokerMutationSafetyPolicy {

	private static final boolean LIVE_ADAPTER_CONNECTED = false;

	private final BrokerSafetyProperties properties;

	/**
	 * 애플리케이션 설정에서 읽은 증권사 실행 안전값을 전달받습니다.
	 *
	 * @param properties 실행 모드, LIVE 기능 플래그와 긴급 차단 스위치 설정
	 */
	public BrokerMutationSafetyPolicy(BrokerSafetyProperties properties) {
		this.properties = properties;
	}

	/**
	 * 현재 안전 설정과 실제 어댑터 미연결 상태를 민감정보 없이 반환합니다.
	 *
	 * @return 실제 주문 가능 여부와 가장 우선적인 차단 사유
	 */
	public BrokerSafetyStatusResponse getStatus() {
		BrokerSafetyBlockReason blockReason = determineBlockReason();
		boolean safetyGateOpen = blockReason == BrokerSafetyBlockReason.LIVE_ADAPTER_NOT_CONNECTED;
		boolean mutationAvailable = safetyGateOpen && LIVE_ADAPTER_CONNECTED;
		return new BrokerSafetyStatusResponse(
				properties.mode(),
				properties.liveEnabled(),
				properties.killSwitchActive(),
				safetyGateOpen,
				LIVE_ADAPTER_CONNECTED,
				mutationAvailable,
				blockReason);
	}

	/**
	 * 향후 실제 주문 어댑터가 호출되기 전에 전역 설정과 어댑터 연결 상태를 모두 검사합니다.
	 * 현재는 실제 어댑터 연결값이 코드에서 false로 고정되어 항상 마지막 단계에서 차단됩니다.
	 */
	public void requireLiveMutationAvailable() {
		BrokerSafetyBlockReason blockReason = determineBlockReason();
		switch (blockReason) {
			case MOCK_MODE -> throw new BrokerMutationBlockedException(
					"현재 증권사 실행 모드는 MOCK입니다.");
			case LIVE_FEATURE_DISABLED -> throw new BrokerMutationBlockedException(
					"실제 주문 기능이 비활성화되어 있습니다.");
			case KILL_SWITCH_ACTIVE -> throw new BrokerMutationBlockedException(
					"긴급 주문 차단 스위치가 활성화되어 있습니다.");
			case LIVE_ADAPTER_NOT_CONNECTED -> throw new BrokerMutationBlockedException(
					"실제 주문 어댑터가 연결되어 있지 않습니다.");
		}
	}

	/**
	 * 실행 모드, 기능 플래그, 긴급 차단 스위치 순서로 가장 우선적인 차단 사유를 결정합니다.
	 *
	 * @return 실제 주문 안전 설정 또는 어댑터 상태가 막힌 이유
	 */
	private BrokerSafetyBlockReason determineBlockReason() {
		if (properties.mode() != BrokerExecutionMode.LIVE) {
			return BrokerSafetyBlockReason.MOCK_MODE;
		}
		if (!properties.liveEnabled()) {
			return BrokerSafetyBlockReason.LIVE_FEATURE_DISABLED;
		}
		if (properties.killSwitchActive()) {
			return BrokerSafetyBlockReason.KILL_SWITCH_ACTIVE;
		}
		return BrokerSafetyBlockReason.LIVE_ADAPTER_NOT_CONNECTED;
	}
}

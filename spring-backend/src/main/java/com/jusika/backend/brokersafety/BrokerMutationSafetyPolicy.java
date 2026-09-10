package com.jusika.backend.brokersafety;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 실제 증권사 주문 변경 전에 LIVE 기능 플래그와 긴급 차단 스위치를 중앙에서 검사합니다.
 */
@Service
public class BrokerMutationSafetyPolicy {

	private static final List<BrokerMutationCapabilityStatus> MUTATION_CAPABILITIES =
			Arrays.stream(BrokerMutationCapability.values())
					.map(capability -> new BrokerMutationCapabilityStatus(capability, false))
					.toList();

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
		boolean allAdaptersConnected = MUTATION_CAPABILITIES.stream()
				.allMatch(BrokerMutationCapabilityStatus::liveAdapterConnected);
		boolean mutationAvailable = safetyGateOpen && allAdaptersConnected;
		return new BrokerSafetyStatusResponse(
				properties.mode(),
				properties.liveEnabled(),
				properties.killSwitchActive(),
				safetyGateOpen,
				allAdaptersConnected,
				mutationAvailable,
				blockReason,
				MUTATION_CAPABILITIES);
	}

	/**
	 * 지정한 주문 변경 기능의 실제 어댑터가 호출되기 전에 전역 설정과 해당 연결 상태를 검사합니다.
	 * 현재는 모든 기능의 실제 어댑터 연결값이 false로 고정되어 항상 마지막 단계에서 차단됩니다.
	 *
	 * @param capability 실제 호출 직전 검사할 주문 변경 기능
	 */
	public void requireLiveMutationAvailable(BrokerMutationCapability capability) {
		if (capability == null) {
			throw new IllegalArgumentException("확인할 주문 변경 기능이 필요합니다.");
		}
		if (properties.mode() != BrokerExecutionMode.LIVE) {
			throw new BrokerMutationBlockedException("현재 증권사 실행 모드는 MOCK입니다.");
		}
		if (!properties.liveEnabled()) {
			throw new BrokerMutationBlockedException("실제 주문 기능이 비활성화되어 있습니다.");
		}
		if (properties.killSwitchActive()) {
			throw new BrokerMutationBlockedException("긴급 주문 차단 스위치가 활성화되어 있습니다.");
		}
		if (!isLiveAdapterConnected(capability)) {
			throw new BrokerMutationBlockedException("실제 주문 어댑터가 연결되어 있지 않습니다.");
		}
	}

	/** 지정한 주문 변경 기능의 실제 어댑터 연결 상태를 내부 목록에서 확인합니다. */
	private boolean isLiveAdapterConnected(BrokerMutationCapability capability) {
		return MUTATION_CAPABILITIES.stream()
				.anyMatch(status -> status.capability() == capability
						&& status.liveAdapterConnected());
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

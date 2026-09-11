package com.jusika.backend.brokersafety;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 실제 증권사 주문 변경 전에 LIVE 기능 플래그와 긴급 차단 스위치를 중앙에서 검사합니다.
 */
@Service
public class BrokerMutationSafetyPolicy {

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
	 * 현재 안전 설정과 기능별 실제 어댑터 준비 상태를 민감정보 없이 반환합니다.
	 *
	 * @return 실제 주문 가능 여부와 가장 우선적인 차단 사유
	 */
	public BrokerSafetyStatusResponse getStatus() {
		List<BrokerMutationCapabilityStatus> mutationCapabilities = createCapabilityStatuses();
		boolean allAdaptersConnected = mutationCapabilities.stream()
				.allMatch(BrokerMutationCapabilityStatus::liveAdapterConnected);
		boolean accountAllowlistConfigured = !properties.allowedAccountSeqs().isEmpty();
		BrokerSafetyBlockReason blockReason = determineBlockReason(
				allAdaptersConnected,
				accountAllowlistConfigured);
		boolean safetyGateOpen = blockReason == BrokerSafetyBlockReason.LIVE_ADAPTER_NOT_CONNECTED
				|| blockReason == BrokerSafetyBlockReason.LIVE_ACCOUNT_ALLOWLIST_EMPTY
				|| blockReason == BrokerSafetyBlockReason.NONE;
		boolean mutationAvailable = blockReason == BrokerSafetyBlockReason.NONE;
		return new BrokerSafetyStatusResponse(
				properties.mode(),
				properties.liveEnabled(),
				properties.killSwitchActive(),
				safetyGateOpen,
				allAdaptersConnected,
				accountAllowlistConfigured,
				mutationAvailable,
				blockReason,
				mutationCapabilities);
	}

	/**
	 * 지정한 주문 변경 기능의 실제 어댑터가 호출되기 전에 전역 설정과 해당 연결 상태를 검사합니다.
	 * 기능별 준비 상태는 독립적으로 검사하며 기본 설정에서는 모든 기능이 마지막 단계에서 차단됩니다.
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

	/**
	 * 실제 주문에 사용할 계좌가 명시적인 허용 목록에 포함됐는지 검사합니다.
	 * 오류에는 검사한 계좌 식별값을 포함하지 않습니다.
	 *
	 * @param accountSeq 실제 주문 변경에 사용할 계좌 식별값
	 */
	public void requireLiveAccountAllowed(long accountSeq) {
		if (accountSeq <= 0) {
			throw new IllegalArgumentException("확인할 계좌 식별값은 1 이상이어야 합니다.");
		}
		if (!properties.allowedAccountSeqs().contains(accountSeq)) {
			throw new BrokerMutationBlockedException("실제 주문이 허용된 계좌가 아닙니다.");
		}
	}

	/** 지정한 주문 변경 기능의 실제 어댑터 연결 상태를 설정에서 확인합니다. */
	private boolean isLiveAdapterConnected(BrokerMutationCapability capability) {
		return properties.liveAdapters().isConnected(capability);
	}

	/** 모든 주문 변경 기능의 현재 어댑터 준비 상태를 설정값에서 만듭니다. */
	private List<BrokerMutationCapabilityStatus> createCapabilityStatuses() {
		return java.util.Arrays.stream(BrokerMutationCapability.values())
				.map(capability -> new BrokerMutationCapabilityStatus(
						capability,
						properties.liveAdapters().isConnected(capability)))
				.toList();
	}

	/**
	 * 실행 모드, 기능 플래그, 긴급 차단 스위치와 어댑터 순서로 차단 사유를 결정합니다.
	 *
	 * @param allAdaptersConnected 모든 주문 변경 어댑터가 준비됐는지 여부
	 * @param accountAllowlistConfigured 허용한 실제 주문 계좌가 있는지 여부
	 * @return 실제 주문이 막힌 이유 또는 모든 검사가 통과한 NONE
	 */
	private BrokerSafetyBlockReason determineBlockReason(
			boolean allAdaptersConnected,
			boolean accountAllowlistConfigured) {
		if (properties.mode() != BrokerExecutionMode.LIVE) {
			return BrokerSafetyBlockReason.MOCK_MODE;
		}
		if (!properties.liveEnabled()) {
			return BrokerSafetyBlockReason.LIVE_FEATURE_DISABLED;
		}
		if (properties.killSwitchActive()) {
			return BrokerSafetyBlockReason.KILL_SWITCH_ACTIVE;
		}
		if (!allAdaptersConnected) {
			return BrokerSafetyBlockReason.LIVE_ADAPTER_NOT_CONNECTED;
		}
		return accountAllowlistConfigured
				? BrokerSafetyBlockReason.NONE
				: BrokerSafetyBlockReason.LIVE_ACCOUNT_ALLOWLIST_EMPTY;
	}
}

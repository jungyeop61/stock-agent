package com.jusika.backend.brokersafety;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실제 주문 변경을 여러 독립 설정으로 차단하는 전역 안전 설정입니다.
 *
 * @param mode 현재 요청한 증권사 실행 모드
 * @param liveEnabled 실제 주문 기능을 명시적으로 활성화했는지 여부
 * @param killSwitchActive 실제 주문을 즉시 차단하는 긴급 스위치 상태
 * @param liveAdapters 실제 주문 변경 기능별 어댑터 준비 상태
 * @param allowedAccountSeqs 실제 주문 변경을 허용한 계좌 식별값 집합
 */
@ConfigurationProperties(prefix = "jusika.broker")
public record BrokerSafetyProperties(
		@DefaultValue("MOCK") BrokerExecutionMode mode,
		@DefaultValue("false") boolean liveEnabled,
		@DefaultValue("true") boolean killSwitchActive,
		@DefaultValue BrokerLiveAdapterProperties liveAdapters,
		@DefaultValue Set<Long> allowedAccountSeqs) {

	/** 기존 호출부에서도 모든 기능이 닫힌 안전 설정을 만들 수 있게 합니다. */
	public BrokerSafetyProperties(
			BrokerExecutionMode mode,
			boolean liveEnabled,
			boolean killSwitchActive) {
		this(
				mode,
				liveEnabled,
				killSwitchActive,
				BrokerLiveAdapterProperties.allDisabled(),
				Set.of());
	}

	/** 기능별 설정을 직접 지정해도 계좌 허용 목록은 닫힌 상태로 만듭니다. */
	public BrokerSafetyProperties(
			BrokerExecutionMode mode,
			boolean liveEnabled,
			boolean killSwitchActive,
			BrokerLiveAdapterProperties liveAdapters) {
		this(mode, liveEnabled, killSwitchActive, liveAdapters, Set.of());
	}

	/**
	 * 실행 모드, 기능별 준비 상태나 계좌 허용 목록이 잘못된 설정을 생성 단계에서 거절합니다.
	 */
	@ConstructorBinding
	public BrokerSafetyProperties {
		if (mode == null) {
			throw new IllegalArgumentException("증권사 실행 모드 설정이 필요합니다.");
		}
		if (liveAdapters == null) {
			throw new IllegalArgumentException("기능별 실제 주문 어댑터 설정이 필요합니다.");
		}
		if (allowedAccountSeqs == null) {
			throw new IllegalArgumentException("실제 주문 계좌 허용 목록 설정이 필요합니다.");
		}
		if (allowedAccountSeqs.stream().anyMatch(accountSeq -> accountSeq == null || accountSeq <= 0)) {
			throw new IllegalArgumentException("계좌 허용 목록에는 1 이상의 식별값만 사용할 수 있습니다.");
		}
		allowedAccountSeqs = Set.copyOf(allowedAccountSeqs);
	}
}

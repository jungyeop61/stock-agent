package com.jusika.backend.brokersafety;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실제 주문 변경을 여러 독립 설정으로 차단하는 전역 안전 설정입니다.
 *
 * @param mode 현재 요청한 증권사 실행 모드
 * @param liveEnabled 실제 주문 기능을 명시적으로 활성화했는지 여부
 * @param killSwitchActive 실제 주문을 즉시 차단하는 긴급 스위치 상태
 */
@ConfigurationProperties(prefix = "jusika.broker")
public record BrokerSafetyProperties(
		@DefaultValue("MOCK") BrokerExecutionMode mode,
		@DefaultValue("false") boolean liveEnabled,
		@DefaultValue("true") boolean killSwitchActive) {

	/**
	 * 실행 모드가 누락된 잘못된 설정을 애플리케이션 시작 단계에서 거절합니다.
	 */
	public BrokerSafetyProperties {
		if (mode == null) {
			throw new IllegalArgumentException("증권사 실행 모드 설정이 필요합니다.");
		}
	}
}

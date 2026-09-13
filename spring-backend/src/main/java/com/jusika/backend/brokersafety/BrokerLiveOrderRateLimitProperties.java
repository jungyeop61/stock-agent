package com.jusika.backend.brokersafety;

import org.springframework.boot.context.properties.bind.DefaultValue;

/** 계좌 전체와 동일 종목에 적용할 1분 LIVE 주문 생성·정정 상한입니다. */
public record BrokerLiveOrderRateLimitProperties(
		@DefaultValue("0") int maxMutationsPerAccountPerMinute,
		@DefaultValue("0") int maxMutationsPerInstrumentPerMinute) {

	/** 음수 상한은 안전하지 않은 설정이므로 애플리케이션 시작 단계에서 거절합니다. */
	public BrokerLiveOrderRateLimitProperties {
		if (maxMutationsPerAccountPerMinute < 0
				|| maxMutationsPerInstrumentPerMinute < 0) {
			throw new IllegalArgumentException("LIVE 주문 빈도 한도는 음수일 수 없습니다.");
		}
	}

	/** 모든 LIVE 주문 빈도 제한을 닫는 기본 설정을 반환합니다. */
	public static BrokerLiveOrderRateLimitProperties allDisabled() {
		return new BrokerLiveOrderRateLimitProperties(0, 0);
	}

	/** 계좌와 종목별 1분 상한이 모두 양수로 설정됐는지 반환합니다. */
	public boolean isConfigured() {
		return maxMutationsPerAccountPerMinute > 0
				&& maxMutationsPerInstrumentPerMinute > 0;
	}
}

package com.jusika.backend.brokersafety;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실제 주문 실행 전에 허용할 계좌 전체와 동일 종목의 활성 주문 개수 상한입니다.
 * 0은 운영 상한이 승인·설정되지 않아 LIVE 주문을 차단한다는 뜻입니다.
 *
 * @param maxOpenOrdersPerAccount 한 계좌에서 동시에 유지할 수 있는 일반·조건 주문 합계
 * @param maxOpenOrdersPerInstrument 한 계좌의 동일 종목에서 동시에 유지할 수 있는 일반·조건 주문 합계
 */
public record BrokerLiveOpenOrderLimitProperties(
		@DefaultValue("0") int maxOpenOrdersPerAccount,
		@DefaultValue("0") int maxOpenOrdersPerInstrument) {

	/** 음수인 활성 주문 상한을 애플리케이션 설정 단계에서 거절합니다. */
	public BrokerLiveOpenOrderLimitProperties {
		if (maxOpenOrdersPerAccount < 0 || maxOpenOrdersPerInstrument < 0) {
			throw new IllegalArgumentException("LIVE 활성 주문 개수 한도는 음수일 수 없습니다.");
		}
	}

	/** 두 활성 주문 상한이 모두 미승인 상태인 기본 설정을 만듭니다. */
	public static BrokerLiveOpenOrderLimitProperties allDisabled() {
		return new BrokerLiveOpenOrderLimitProperties(0, 0);
	}

	/** 계좌와 동일 종목의 활성 주문 상한이 모두 양수로 설정됐는지 반환합니다. */
	public boolean isConfigured() {
		return maxOpenOrdersPerAccount > 0 && maxOpenOrdersPerInstrument > 0;
	}
}

package com.jusika.backend.brokersafety;

/**
 * 실제 주문 변경을 현재 허용하지 않는 가장 우선적인 안전 사유입니다.
 */
public enum BrokerSafetyBlockReason {
	MOCK_MODE,
	LIVE_FEATURE_DISABLED,
	KILL_SWITCH_ACTIVE,
	LIVE_ADAPTER_NOT_CONNECTED
}

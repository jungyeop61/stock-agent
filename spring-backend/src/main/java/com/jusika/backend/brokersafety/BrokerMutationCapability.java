package com.jusika.backend.brokersafety;

/**
 * 실제 토스증권 어댑터 연결 상태를 각각 추적할 주문 변경 기능입니다.
 */
public enum BrokerMutationCapability {
	QUANTITY_ORDER_SUBMISSION,
	AMOUNT_ORDER_SUBMISSION,
	NORMAL_ORDER_CANCELLATION,
	NORMAL_ORDER_MODIFICATION,
	SINGLE_CONDITIONAL_ORDER_CREATION,
	OCO_CONDITIONAL_ORDER_CREATION,
	OTO_CONDITIONAL_ORDER_CREATION,
	CONDITIONAL_ORDER_CANCELLATION,
	CONDITIONAL_ORDER_MODIFICATION;

	/** 결과 불명 사고 뒤 추가 위험을 만들 수 있어 자동 안전정지 대상인지 반환합니다. */
	public boolean increasesOrderExposure() {
		return this != NORMAL_ORDER_CANCELLATION
				&& this != CONDITIONAL_ORDER_CANCELLATION;
	}
}

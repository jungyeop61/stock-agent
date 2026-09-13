package com.jusika.backend.brokersafety;

/** 활성 주문 개수 검사에서 새 주문 한 건을 더할지 구분합니다. */
public enum BrokerOpenOrderCapacityOperation {
	/** 일반·금액·조건 주문 생성으로 활성 주문이 한 건 늘어날 수 있습니다. */
	CREATE,
	/** 기존 주문 정정 또는 동일 요청 복구로 활성 주문 수가 늘어나지 않습니다. */
	REPLACE_OR_RECOVER;

	/** 현재 활성 주문 수에 더할 보수적인 신규 주문 수를 반환합니다. */
	public int projectedIncrease() {
		return this == CREATE ? 1 : 0;
	}
}

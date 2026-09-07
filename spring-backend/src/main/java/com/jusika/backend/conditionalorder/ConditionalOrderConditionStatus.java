package com.jusika.backend.conditionalorder;

/**
 * 조건 주문 안에 포함된 개별 감시 조건의 상태를 표현합니다.
 */
public enum ConditionalOrderConditionStatus {
	WATCHING,
	HOLDING,
	PAUSED,
	ORDERING,
	ORDERED,
	COMPLETED,
	EXPIRED,
	CANCELED
}

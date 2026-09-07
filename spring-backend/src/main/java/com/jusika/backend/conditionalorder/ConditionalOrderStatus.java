package com.jusika.backend.conditionalorder;

/**
 * 조건 주문 전체의 감시·주문·종료 상태를 표현합니다.
 */
public enum ConditionalOrderStatus {
	WATCHING,
	PAUSED,
	ORDERING,
	ORDERED,
	COMPLETED,
	EXPIRED
}

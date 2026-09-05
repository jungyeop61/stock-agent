package com.jusika.backend.orderexecution;

/**
 * 승인된 주문 미리보기가 실제 주문 전송 과정에서 가질 수 있는 상태입니다.
 */
public enum OrderExecutionStatus {
	PREPARED,
	SUBMITTING,
	RECOVERING,
	ACCEPTED,
	REJECTED,
	UNKNOWN
}

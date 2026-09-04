package com.jusika.backend.orderexecution;

/**
 * 주문 실행이 정상 접수되지 않았을 때 금융정보 없이 기록할 실패 분류입니다.
 */
public enum OrderExecutionFailureType {
	BROKER_REJECTED,
	SUBMISSION_UNKNOWN,
	INTERNAL_STATE
}

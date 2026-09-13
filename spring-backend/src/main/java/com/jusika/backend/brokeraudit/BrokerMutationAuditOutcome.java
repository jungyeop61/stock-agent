package com.jusika.backend.brokeraudit;

/** LIVE 주문 변경 감사 사건의 민감정보 없는 처리 결과를 구분합니다. */
public enum BrokerMutationAuditOutcome {
	ALLOWED,
	BLOCKED,
	STARTED,
	SUCCEEDED,
	REJECTED,
	UNKNOWN
}

package com.jusika.backend.brokeraudit;

/** LIVE 주문 변경 감사 사건이 발생한 처리 단계를 구분합니다. */
public enum BrokerMutationAuditStage {
	SAFETY_GATE,
	BROKER_REQUEST
}

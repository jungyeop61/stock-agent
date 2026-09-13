package com.jusika.backend.brokeraudit;

import java.time.OffsetDateTime;

import com.jusika.backend.brokersafety.BrokerMutationCapability;

/** 민감정보 없이 외부에 제공하는 LIVE 주문 변경 감사 사건입니다. */
public record BrokerMutationAuditEventResponse(
		long auditEventId,
		String requestId,
		BrokerMutationCapability capability,
		BrokerMutationAuditStage stage,
		BrokerMutationAuditOutcome outcome,
		OffsetDateTime occurredAt) {
}

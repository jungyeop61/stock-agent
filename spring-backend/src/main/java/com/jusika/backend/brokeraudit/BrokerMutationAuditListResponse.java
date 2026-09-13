package com.jusika.backend.brokeraudit;

import java.util.List;

/** 커서 기반 LIVE 주문 변경 감사 사건 목록입니다. */
public record BrokerMutationAuditListResponse(
		List<BrokerMutationAuditEventResponse> events,
		Long nextBeforeEventId,
		boolean hasNext) {

	/** 응답 생성 뒤 감사 사건 목록을 외부에서 변경할 수 없도록 복사합니다. */
	public BrokerMutationAuditListResponse {
		events = List.copyOf(events);
	}
}

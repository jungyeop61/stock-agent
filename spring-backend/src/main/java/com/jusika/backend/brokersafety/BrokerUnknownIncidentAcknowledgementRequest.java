package com.jusika.backend.brokersafety;

/**
 * 사용자가 직접 확인한 최신 결과 불명 감사 사건을 명시해 자동 안전정지 해제를 요청합니다.
 *
 * @param unknownAuditEventId 사용자가 상태를 확인한 UNKNOWN 감사 사건 식별값
 * @param confirmed 해당 사건까지 직접 확인했음을 명시하는 값
 */
public record BrokerUnknownIncidentAcknowledgementRequest(
		long unknownAuditEventId,
		boolean confirmed) {
}

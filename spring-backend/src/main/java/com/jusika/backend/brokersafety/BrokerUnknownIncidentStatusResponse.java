package com.jusika.backend.brokersafety;

import java.time.OffsetDateTime;

/**
 * 민감한 주문 정보 없이 결과 불명 자동 안전정지와 최근 확인 상태를 반환합니다.
 *
 * @param haltActive 확인되지 않은 결과 불명 사건이 남아 있는지 여부
 * @param acknowledgedThroughAuditEventId 사용자가 확인한 마지막 감사 사건 식별값
 * @param acknowledgedAt 마지막 확인 시각
 */
public record BrokerUnknownIncidentStatusResponse(
		boolean haltActive,
		Long acknowledgedThroughAuditEventId,
		OffsetDateTime acknowledgedAt) {
}

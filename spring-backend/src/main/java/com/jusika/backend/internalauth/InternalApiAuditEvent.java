package com.jusika.backend.internalauth;

import java.time.Instant;

/**
 * 비밀키와 실제 계좌·주문 식별값을 제외한 내부 API 감사 사건입니다.
 *
 * @param occurredAt 요청을 처음 받은 시각
 * @param requestId 안전하게 검증하거나 서버가 생성한 요청 상관관계 식별값
 * @param httpMethod HTTP 메서드
 * @param routePattern 실제 식별값을 제거한 스프링 라우트 템플릿
 * @param requiredAuthority 요청 경로가 요구한 최소 권한
 * @param authenticationResult 비밀값을 제외한 인증 결과
 * @param outcome 권한 거절 또는 요청 처리 결과
 * @param httpStatus 최종 HTTP 상태 코드
 * @param durationMillis 요청 처리에 걸린 밀리초
 */
public record InternalApiAuditEvent(
		Instant occurredAt,
		String requestId,
		String httpMethod,
		String routePattern,
		InternalApiAuthority requiredAuthority,
		InternalApiAuthenticationResult authenticationResult,
		InternalApiAuditOutcome outcome,
		int httpStatus,
		long durationMillis) {
}

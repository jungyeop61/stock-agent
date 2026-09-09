package com.jusika.backend.internalauth;

/**
 * 보호 API 요청이 권한 단계에서 거절되었는지 처리까지 완료되었는지 표현합니다.
 */
public enum InternalApiAuditOutcome {
	AUTHORIZATION_REJECTED,
	REQUEST_COMPLETED,
	REQUEST_FAILED
}

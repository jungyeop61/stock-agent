package com.jusika.backend.internalauth;

/**
 * 내부 API 키 인증과 권한 검사 결과를 비밀값 없이 표현합니다.
 */
public enum InternalApiAuthenticationResult {
	ALLOWED,
	SECURITY_NOT_CONFIGURED,
	CREDENTIALS_MISSING,
	CREDENTIALS_INVALID,
	AUTHORITY_INSUFFICIENT
}

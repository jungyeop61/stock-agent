package com.jusika.backend.internalauth;

/**
 * 내부 API 감사 사건의 저장 또는 구조화 로그 기록 경계를 정의합니다.
 */
public interface InternalApiAuditRecorder {

	/**
	 * 민감정보를 제거한 내부 API 감사 사건 한 건을 기록합니다.
	 *
	 * @param event 기록할 요청 권한과 처리 결과
	 */
	void record(InternalApiAuditEvent event);
}

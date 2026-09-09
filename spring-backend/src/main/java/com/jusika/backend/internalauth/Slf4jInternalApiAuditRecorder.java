package com.jusika.backend.internalauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 내부 API 감사 사건을 검색 가능한 고정 필드의 애플리케이션 로그로 기록합니다.
 */
@Component
public class Slf4jInternalApiAuditRecorder implements InternalApiAuditRecorder {

	private static final Logger LOGGER = LoggerFactory.getLogger("JUSIKA_INTERNAL_API_AUDIT");

	/**
	 * API 키나 실제 경로값 없이 감사 사건의 고정 필드만 한 줄 로그로 남깁니다.
	 *
	 * @param event 기록할 내부 API 감사 사건
	 */
	@Override
	public void record(InternalApiAuditEvent event) {
		LOGGER.info(
				"event=internal_api_audit occurredAt={} requestId={} method={} route={} authority={} "
						+ "authentication={} outcome={} status={} durationMs={}",
				event.occurredAt(),
				event.requestId(),
				event.httpMethod(),
				event.routePattern(),
				event.requiredAuthority(),
				event.authenticationResult(),
				event.outcome(),
				event.httpStatus(),
				event.durationMillis());
	}
}

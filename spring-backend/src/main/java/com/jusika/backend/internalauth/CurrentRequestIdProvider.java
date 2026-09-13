package com.jusika.backend.internalauth;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** 현재 HTTP 요청에 배정된 검증된 요청 UUID를 안전하게 조회합니다. */
@Component
public class CurrentRequestIdProvider {

	/**
	 * 현재 요청의 감사 추적 UUID를 반환하고 HTTP 요청 밖이면 빈 값을 반환합니다.
	 *
	 * @return 현재 요청 UUID 또는 빈 값
	 */
	public Optional<String> findCurrentRequestId() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes == null) {
			return Optional.empty();
		}
		Object requestId = attributes.getAttribute(
				InternalApiAuthorizationInterceptor.AUDIT_REQUEST_ID_ATTRIBUTE,
				RequestAttributes.SCOPE_REQUEST);
		return requestId instanceof String value && !value.isBlank()
				? Optional.of(value)
				: Optional.empty();
	}
}

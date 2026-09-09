package com.jusika.backend.internalauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 권한 표시가 붙은 컨트롤러 요청만 내부 API 키로 차단하거나 통과시킵니다.
 */
@Component
public class InternalApiAuthorizationInterceptor implements HandlerInterceptor {

	public static final String API_KEY_HEADER = "X-Jusika-Api-Key";
	public static final String REQUEST_ID_HEADER = "X-Jusika-Request-Id";

	private static final String AUDIT_STARTED_AT_ATTRIBUTE =
			InternalApiAuthorizationInterceptor.class.getName() + ".auditStartedAt";
	private static final String AUDIT_STARTED_NANOS_ATTRIBUTE =
			InternalApiAuthorizationInterceptor.class.getName() + ".auditStartedNanos";
	private static final String AUDIT_REQUEST_ID_ATTRIBUTE =
			InternalApiAuthorizationInterceptor.class.getName() + ".auditRequestId";
	private static final String AUDIT_ROUTE_ATTRIBUTE =
			InternalApiAuthorizationInterceptor.class.getName() + ".auditRoute";
	private static final String AUDIT_AUTHORITY_ATTRIBUTE =
			InternalApiAuthorizationInterceptor.class.getName() + ".auditAuthority";
	private static final String AUDIT_AUTHENTICATION_ATTRIBUTE =
			InternalApiAuthorizationInterceptor.class.getName() + ".auditAuthentication";

	private final InternalApiKeyAuthenticationService authenticationService;
	private final InternalApiAuditRecorder auditRecorder;
	private final Clock clock;

	/**
	 * 비밀값을 노출하지 않고 키와 권한을 검사할 서비스를 전달받습니다.
	 *
	 * @param authenticationService 내부 API 키 인증 서비스
	 * @param auditRecorder 민감정보가 제거된 요청 결과 기록기
	 * @param clock 감사 사건의 요청 시각을 계산할 시계
	 */
	public InternalApiAuthorizationInterceptor(
			InternalApiKeyAuthenticationService authenticationService,
			InternalApiAuditRecorder auditRecorder,
			Clock clock) {
		this.authenticationService = authenticationService;
		this.auditRecorder = auditRecorder;
		this.clock = clock;
	}

	/**
	 * 컨트롤러 실행 전에 요구 권한과 제출 키를 검사하고 실패 요청을 즉시 종료합니다.
	 *
	 * @param request 현재 HTTP 요청
	 * @param response 현재 HTTP 응답
	 * @param handler 선택된 요청 처리 객체
	 * @return 인증이 필요 없거나 권한을 충족하면 true
	 * @throws IOException 안전한 오류 응답을 쓰지 못한 경우
	 */
	@Override
	public boolean preHandle(
			HttpServletRequest request,
			HttpServletResponse response,
			Object handler) throws IOException {
		String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
		response.setHeader(REQUEST_ID_HEADER, requestId);
		if (!(handler instanceof HandlerMethod handlerMethod)) {
			return true;
		}
		InternalApiAuthority requiredAuthority = findRequiredAuthority(handlerMethod);
		if (requiredAuthority == null) {
			return true;
		}
		prepareAuditContext(request, handlerMethod, requestId, requiredAuthority);
		InternalApiAuthenticationResult result = authenticationService.authenticate(
				requiredAuthority,
				request.getHeader(API_KEY_HEADER));
		request.setAttribute(AUDIT_AUTHENTICATION_ATTRIBUTE, result);
		return switch (result) {
			case ALLOWED -> true;
			case SECURITY_NOT_CONFIGURED -> reject(
					request, response, result, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
					"내부 API 인증이 안전하게 설정되지 않았습니다.");
			case AUTHORITY_INSUFFICIENT -> reject(
					request, response, result, HttpServletResponse.SC_FORBIDDEN,
					"이 요청을 실행할 주문 권한이 없습니다.");
			case CREDENTIALS_MISSING, CREDENTIALS_INVALID -> reject(
					request, response, result, HttpServletResponse.SC_UNAUTHORIZED,
					"유효한 내부 API 인증이 필요합니다.");
		};
	}

	/**
	 * 허용된 보호 API의 최종 HTTP 상태와 처리 시간을 감사 사건으로 기록합니다.
	 *
	 * @param request 완료된 HTTP 요청
	 * @param response 완료된 HTTP 응답
	 * @param handler 실행된 요청 처리 객체
	 * @param exception 처리 과정에서 해결되지 않고 전달된 예외
	 */
	@Override
	public void afterCompletion(
			HttpServletRequest request,
			HttpServletResponse response,
			Object handler,
			Exception exception) {
		if (request.getAttribute(AUDIT_AUTHENTICATION_ATTRIBUTE)
				!= InternalApiAuthenticationResult.ALLOWED) {
			return;
		}
		InternalApiAuditOutcome outcome = exception != null
				|| response.getStatus() >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR
				? InternalApiAuditOutcome.REQUEST_FAILED
				: InternalApiAuditOutcome.REQUEST_COMPLETED;
		auditRecorder.record(createAuditEvent(request, response.getStatus(), outcome));
	}

	/**
	 * 검증된 UUID 요청 식별값을 사용하고 누락되거나 잘못된 값은 새 UUID로 교체합니다.
	 *
	 * @param candidate 요청 헤더로 제출된 선택 요청 식별값
	 * @return 정규화된 UUID 문자열
	 */
	private String resolveRequestId(String candidate) {
		if (candidate != null) {
			String normalized = candidate.trim();
			try {
				UUID parsed = UUID.fromString(normalized);
				if (parsed.toString().equalsIgnoreCase(normalized)) {
					return parsed.toString();
				}
			} catch (IllegalArgumentException ignored) {
				// 잘못된 외부 문자열은 로그에 사용하지 않고 아래에서 안전한 UUID로 교체합니다.
			}
		}
		return UUID.randomUUID().toString();
	}

	/**
	 * 실제 경로값을 제외한 감사 문맥과 요청 시작 시각을 요청 객체에 보관합니다.
	 *
	 * @param request 현재 HTTP 요청
	 * @param handlerMethod 선택된 컨트롤러 메서드
	 * @param requestId 검증하거나 새로 만든 요청 식별값
	 * @param requiredAuthority 요청 경로가 요구하는 최소 권한
	 */
	private void prepareAuditContext(
			HttpServletRequest request,
			HandlerMethod handlerMethod,
			String requestId,
			InternalApiAuthority requiredAuthority) {
		request.setAttribute(AUDIT_STARTED_AT_ATTRIBUTE, clock.instant());
		request.setAttribute(AUDIT_STARTED_NANOS_ATTRIBUTE, System.nanoTime());
		request.setAttribute(AUDIT_REQUEST_ID_ATTRIBUTE, requestId);
		request.setAttribute(AUDIT_ROUTE_ATTRIBUTE, resolveRoutePattern(request, handlerMethod));
		request.setAttribute(AUDIT_AUTHORITY_ATTRIBUTE, requiredAuthority);
	}

	/**
	 * 계좌나 주문 식별값이 치환된 스프링 라우트 템플릿만 감사값으로 반환합니다.
	 *
	 * @param request 현재 HTTP 요청
	 * @param handlerMethod 선택된 컨트롤러 메서드
	 * @return 라우트 템플릿 또는 실제 URL을 포함하지 않은 컨트롤러 메서드 이름
	 */
	private String resolveRoutePattern(
			HttpServletRequest request,
			HandlerMethod handlerMethod) {
		Object routePattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (routePattern != null) {
			return routePattern.toString();
		}
		return handlerMethod.getBeanType().getSimpleName()
				+ "#" + handlerMethod.getMethod().getName();
	}

	/**
	 * 메서드 표시를 우선하고 없으면 컨트롤러 클래스의 요구 권한을 반환합니다.
	 *
	 * @param handlerMethod 선택된 컨트롤러 메서드
	 * @return 필요한 권한 또는 인증이 필요 없으면 null
	 */
	private InternalApiAuthority findRequiredAuthority(HandlerMethod handlerMethod) {
		RequiresInternalApiAuthority methodRequirement = AnnotatedElementUtils.findMergedAnnotation(
				handlerMethod.getMethod(), RequiresInternalApiAuthority.class);
		if (methodRequirement != null) {
			return methodRequirement.value();
		}
		RequiresInternalApiAuthority classRequirement = AnnotatedElementUtils.findMergedAnnotation(
				handlerMethod.getBeanType(), RequiresInternalApiAuthority.class);
		return classRequirement == null ? null : classRequirement.value();
	}

	/**
	 * 키나 계좌 정보를 포함하지 않은 고정 JSON 오류를 반환합니다.
	 *
	 * @param request 현재 HTTP 요청
	 * @param response 현재 HTTP 응답
	 * @param authenticationResult 비밀값을 제외한 인증 실패 결과
	 * @param status 반환할 HTTP 상태 코드
	 * @param message 사용자에게 보여줄 안전한 오류 설명
	 * @return 요청 처리를 중단하도록 항상 false
	 * @throws IOException 응답 본문을 쓰지 못한 경우
	 */
	private boolean reject(
			HttpServletRequest request,
			HttpServletResponse response,
			InternalApiAuthenticationResult authenticationResult,
			int status,
			String message) throws IOException {
		response.setStatus(status);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"message\":\"" + message + "\"}");
		request.setAttribute(AUDIT_AUTHENTICATION_ATTRIBUTE, authenticationResult);
		auditRecorder.record(createAuditEvent(
				request, status, InternalApiAuditOutcome.AUTHORIZATION_REJECTED));
		return false;
	}

	/**
	 * 요청 문맥과 최종 처리 결과를 비밀값 없는 감사 사건으로 변환합니다.
	 *
	 * @param request 감사 문맥이 저장된 HTTP 요청
	 * @param status 최종 HTTP 상태 코드
	 * @param outcome 권한 또는 요청 처리 결과
	 * @return 구조화 로그에 기록할 감사 사건
	 */
	private InternalApiAuditEvent createAuditEvent(
			HttpServletRequest request,
			int status,
			InternalApiAuditOutcome outcome) {
		long startedNanos = (long) request.getAttribute(AUDIT_STARTED_NANOS_ATTRIBUTE);
		long durationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
		return new InternalApiAuditEvent(
				(Instant) request.getAttribute(AUDIT_STARTED_AT_ATTRIBUTE),
				(String) request.getAttribute(AUDIT_REQUEST_ID_ATTRIBUTE),
				request.getMethod(),
				(String) request.getAttribute(AUDIT_ROUTE_ATTRIBUTE),
				(InternalApiAuthority) request.getAttribute(AUDIT_AUTHORITY_ATTRIBUTE),
				(InternalApiAuthenticationResult) request.getAttribute(
						AUDIT_AUTHENTICATION_ATTRIBUTE),
				outcome,
				status,
				durationMillis);
	}
}

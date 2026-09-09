package com.jusika.backend.internalauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 권한 표시가 붙은 컨트롤러 요청만 내부 API 키로 차단하거나 통과시킵니다.
 */
@Component
public class InternalApiAuthorizationInterceptor implements HandlerInterceptor {

	public static final String API_KEY_HEADER = "X-Jusika-Api-Key";

	private final InternalApiKeyAuthenticationService authenticationService;

	/**
	 * 비밀값을 노출하지 않고 키와 권한을 검사할 서비스를 전달받습니다.
	 *
	 * @param authenticationService 내부 API 키 인증 서비스
	 */
	public InternalApiAuthorizationInterceptor(
			InternalApiKeyAuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
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
		if (!(handler instanceof HandlerMethod handlerMethod)) {
			return true;
		}
		InternalApiAuthority requiredAuthority = findRequiredAuthority(handlerMethod);
		if (requiredAuthority == null) {
			return true;
		}
		InternalApiAuthenticationResult result = authenticationService.authenticate(
				requiredAuthority,
				request.getHeader(API_KEY_HEADER));
		return switch (result) {
			case ALLOWED -> true;
			case SECURITY_NOT_CONFIGURED -> reject(
					response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
					"내부 API 인증이 안전하게 설정되지 않았습니다.");
			case AUTHORITY_INSUFFICIENT -> reject(
					response, HttpServletResponse.SC_FORBIDDEN,
					"이 요청을 실행할 주문 권한이 없습니다.");
			case CREDENTIALS_MISSING, CREDENTIALS_INVALID -> reject(
					response, HttpServletResponse.SC_UNAUTHORIZED,
					"유효한 내부 API 인증이 필요합니다.");
		};
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
	 * @param response 현재 HTTP 응답
	 * @param status 반환할 HTTP 상태 코드
	 * @param message 사용자에게 보여줄 안전한 오류 설명
	 * @return 요청 처리를 중단하도록 항상 false
	 * @throws IOException 응답 본문을 쓰지 못한 경우
	 */
	private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"message\":\"" + message + "\"}");
		return false;
	}
}

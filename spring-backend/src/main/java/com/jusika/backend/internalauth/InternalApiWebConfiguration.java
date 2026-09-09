package com.jusika.backend.internalauth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 내부 API 권한 인터셉터를 모든 API 경로의 컨트롤러 선택 과정에 등록합니다.
 */
@Configuration
public class InternalApiWebConfiguration implements WebMvcConfigurer {

	private final InternalApiAuthorizationInterceptor authorizationInterceptor;

	/**
	 * 애플리케이션 공통 내부 API 권한 인터셉터를 전달받습니다.
	 *
	 * @param authorizationInterceptor 내부 API 키 권한 인터셉터
	 */
	public InternalApiWebConfiguration(
			InternalApiAuthorizationInterceptor authorizationInterceptor) {
		this.authorizationInterceptor = authorizationInterceptor;
	}

	/**
	 * 권한 표시가 붙은 API 컨트롤러에만 검사하도록 인터셉터를 등록합니다.
	 *
	 * @param registry 스프링 MVC 인터셉터 등록부
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(authorizationInterceptor).addPathPatterns("/api/**");
	}
}

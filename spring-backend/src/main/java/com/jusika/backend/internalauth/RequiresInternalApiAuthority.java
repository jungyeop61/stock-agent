package com.jusika.backend.internalauth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 또는 메서드가 내부 API 키로 확인해야 할 최소 권한을 지정합니다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresInternalApiAuthority {

	/**
	 * 해당 HTTP 요청에 필요한 최소 내부 API 권한을 반환합니다.
	 *
	 * @return 읽기 또는 주문 권한
	 */
	InternalApiAuthority value();
}

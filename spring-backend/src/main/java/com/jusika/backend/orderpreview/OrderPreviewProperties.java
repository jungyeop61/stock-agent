package com.jusika.backend.orderpreview;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 주문 미리보기의 안전한 승인 유효시간 설정을 표현합니다.
 *
 * @param expiration 생성된 미리보기를 승인할 수 있는 기간
 */
@ConfigurationProperties(prefix = "jusika.order-preview")
public record OrderPreviewProperties(Duration expiration) {

	/**
	 * 승인 유효시간이 반드시 양수인지 확인합니다.
	 */
	public OrderPreviewProperties {
		if (expiration == null || expiration.isZero() || expiration.isNegative()) {
			throw new IllegalArgumentException("주문 미리보기 유효시간은 0보다 커야 합니다.");
		}
	}
}

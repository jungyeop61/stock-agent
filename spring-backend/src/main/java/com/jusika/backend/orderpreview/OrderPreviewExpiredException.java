package com.jusika.backend.orderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 주문 미리보기의 승인 유효시간이 지나 더 이상 승인할 수 없음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.GONE)
public class OrderPreviewExpiredException extends RuntimeException {

	/**
	 * 사용자에게 전달할 안전한 오류 메시지를 저장합니다.
	 *
	 * @param message 오류 원인을 설명하는 메시지
	 */
	public OrderPreviewExpiredException(String message) {
		super(message);
	}
}

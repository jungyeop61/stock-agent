package com.jusika.backend.orderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 요청한 주문 미리보기 식별값을 서버에서 찾지 못했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class OrderPreviewNotFoundException extends RuntimeException {

	/**
	 * 사용자에게 전달할 안전한 오류 메시지를 저장합니다.
	 *
	 * @param message 오류 원인을 설명하는 메시지
	 */
	public OrderPreviewNotFoundException(String message) {
		super(message);
	}
}

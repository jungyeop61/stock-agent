package com.jusika.backend.orderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 이미 승인되거나 사용된 미리보기에 같은 상태 변경을 다시 요청했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class OrderPreviewStateException extends RuntimeException {

	/**
	 * 사용자에게 전달할 안전한 오류 메시지를 저장합니다.
	 *
	 * @param message 오류 원인을 설명하는 메시지
	 */
	public OrderPreviewStateException(String message) {
		super(message);
	}
}

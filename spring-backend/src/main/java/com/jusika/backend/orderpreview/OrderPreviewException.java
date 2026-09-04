package com.jusika.backend.orderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 주문 미리보기 입력값이나 계좌 주문 가능 조건을 통과하지 못했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OrderPreviewException extends RuntimeException {

	/**
	 * 금융정보나 인증정보를 포함하지 않은 안전한 메시지로 예외를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public OrderPreviewException(String message) {
		super(message);
	}
}

package com.jusika.backend.conditionalorder;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 조건 주문 조회에 필요한 계좌·필터·식별값 형식이 올바르지 않음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ConditionalOrderRequestException extends RuntimeException {

	/**
	 * 민감정보를 포함하지 않은 조건 주문 조회 요청 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public ConditionalOrderRequestException(String message) {
		super(message);
	}
}

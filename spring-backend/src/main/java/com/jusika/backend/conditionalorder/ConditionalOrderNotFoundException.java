package com.jusika.backend.conditionalorder;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 지정한 계좌에서 조건 주문을 찾지 못했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ConditionalOrderNotFoundException extends RuntimeException {

	/**
	 * 조건 주문 식별값을 노출하지 않는 조회 실패 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public ConditionalOrderNotFoundException(String message) {
		super(message);
	}
}

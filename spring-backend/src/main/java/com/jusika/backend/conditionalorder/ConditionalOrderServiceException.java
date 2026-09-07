package com.jusika.backend.conditionalorder;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 토스증권 조건 주문 조회 서버 통신이나 응답 변환에 실패했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class ConditionalOrderServiceException extends RuntimeException {

	/**
	 * 원본 응답과 금융정보를 포함하지 않은 외부 서비스 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public ConditionalOrderServiceException(String message) {
		super(message);
	}
}

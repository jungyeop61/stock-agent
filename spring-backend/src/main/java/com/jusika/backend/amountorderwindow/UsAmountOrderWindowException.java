package com.jusika.backend.amountorderwindow;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 미국 장 운영 일정으로 안전한 금액 주문 접수 구간을 계산할 수 없음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class UsAmountOrderWindowException extends RuntimeException {

	/**
	 * 민감정보가 포함되지 않은 안전한 시간 판정 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public UsAmountOrderWindowException(String message) {
		super(message);
	}
}

package com.jusika.backend.amountorderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 금액 주문 미리보기의 승인 유효시간이 지났음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.GONE)
public class AmountOrderPreviewExpiredException extends RuntimeException {

	/**
	 * 민감정보가 포함되지 않은 안전한 만료 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public AmountOrderPreviewExpiredException(String message) {
		super(message);
	}
}

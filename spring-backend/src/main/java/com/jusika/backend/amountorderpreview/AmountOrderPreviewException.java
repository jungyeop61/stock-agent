package com.jusika.backend.amountorderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 금액 주문 미리보기 요청이나 계산 결과가 안전 규칙을 통과하지 못했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class AmountOrderPreviewException extends RuntimeException {

	/**
	 * 민감정보가 포함되지 않은 안전한 미리보기 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public AmountOrderPreviewException(String message) {
		super(message);
	}
}

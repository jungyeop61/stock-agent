package com.jusika.backend.amountorderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 요청한 금액 주문 미리보기를 저장소에서 찾지 못했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AmountOrderPreviewNotFoundException extends RuntimeException {

	/**
	 * 민감정보가 포함되지 않은 안전한 찾을 수 없음 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public AmountOrderPreviewNotFoundException(String message) {
		super(message);
	}
}

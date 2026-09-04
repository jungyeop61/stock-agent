package com.jusika.backend.toss.order;

/**
 * 토스증권 주문 요청 검증 또는 주문 생성 통신 실패를 안전하게 표현합니다.
 */
public class TossOrderException extends RuntimeException {

	private final Integer httpStatus;
	private final boolean submissionStateUnknown;

	/**
	 * 주문이 전송되기 전에 발견한 입력 오류를 만듭니다.
	 *
	 * @param message 금융정보를 포함하지 않은 오류 설명
	 */
	public TossOrderException(String message) {
		this(message, null, false);
	}

	/**
	 * HTTP 상태와 주문 접수 여부의 불확실성을 포함한 통신 오류를 만듭니다.
	 *
	 * @param message 금융정보를 포함하지 않은 오류 설명
	 * @param httpStatus 토스증권 HTTP 상태이며 응답을 받지 못했으면 null
	 * @param submissionStateUnknown 토스증권의 주문 접수 여부를 확정할 수 없으면 true
	 */
	public TossOrderException(String message, Integer httpStatus, boolean submissionStateUnknown) {
		super(message);
		this.httpStatus = httpStatus;
		this.submissionStateUnknown = submissionStateUnknown;
	}

	/**
	 * 토스증권에서 받은 HTTP 상태를 반환합니다.
	 *
	 * @return HTTP 상태 숫자이며 응답을 받지 못했으면 null
	 */
	public Integer getHttpStatus() {
		return httpStatus;
	}

	/**
	 * 네트워크 오류 등으로 주문 접수 여부를 확인할 수 없는지 반환합니다.
	 *
	 * @return 주문 접수 여부가 불확실하면 true
	 */
	public boolean isSubmissionStateUnknown() {
		return submissionStateUnknown;
	}
}

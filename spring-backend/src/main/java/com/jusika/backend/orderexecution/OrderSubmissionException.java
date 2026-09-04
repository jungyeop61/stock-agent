package com.jusika.backend.orderexecution;

/**
 * 주문 제출 게이트웨이가 확정 거절 또는 결과 불명 상태를 서비스에 전달합니다.
 */
public class OrderSubmissionException extends RuntimeException {

	private final boolean submissionStateUnknown;

	/**
	 * 금융정보를 포함하지 않은 실패 메시지와 주문 결과의 불확실성을 저장합니다.
	 *
	 * @param message 안전한 주문 제출 실패 설명
	 * @param submissionStateUnknown 주문 접수 여부를 확정할 수 없으면 true
	 */
	public OrderSubmissionException(String message, boolean submissionStateUnknown) {
		super(message);
		this.submissionStateUnknown = submissionStateUnknown;
	}

	/**
	 * 주문 접수 여부를 증권사 응답으로 확정할 수 없는지 반환합니다.
	 *
	 * @return 접수 여부가 불확실하면 true
	 */
	public boolean isSubmissionStateUnknown() {
		return submissionStateUnknown;
	}
}

package com.jusika.backend.orderexecution;

/**
 * 안전 복구 검증에 필요한 실행 기록과 외부에 공개하지 않는 최초 요청 지문을 묶습니다.
 *
 * @param execution 저장된 주문 실행 기록
 * @param requestFingerprint 계좌와 최초 주문 본문을 함께 계산한 변경 감지용 지문
 */
public record OrderExecutionRecoveryCandidate(
		OrderExecutionResponse execution,
		String requestFingerprint) {

	/**
	 * 객체가 로그에 기록되더라도 주문 식별값과 요청 지문이 노출되지 않도록 가립니다.
	 *
	 * @return 민감한 식별값이 제거된 복구 후보 설명
	 */
	@Override
	public String toString() {
		return "OrderExecutionRecoveryCandidate[execution=***, requestFingerprint=***]";
	}
}

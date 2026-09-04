package com.jusika.backend.orderexecution;

import java.time.OffsetDateTime;

/**
 * 승인된 미리보기의 주문 실행 상태와 식별값을 표현합니다.
 *
 * @param executionId 우리 서버가 만든 주문 실행 식별값
 * @param previewId 실행에 사용한 주문 미리보기 식별값
 * @param clientOrderId 증권사 중복 주문 방지에 사용할 멱등성 식별값
 * @param brokerMode 모의 주문 또는 실제 증권사 연결 모드
 * @param status 현재 주문 실행 상태
 * @param brokerOrderId 증권사가 반환한 주문 식별값이며 접수 전에는 null
 * @param failureType 안전하게 분류한 실패 종류이며 정상이면 null
 * @param createdAt 주문 실행 기록을 만든 시각
 * @param updatedAt 주문 실행 상태를 마지막으로 바꾼 시각
 * @param submittedAt 증권사 전송을 시작한 시각이며 시작 전에는 null
 * @param completedAt 접수 또는 거절 결과를 확정한 시각이며 미확정이면 null
 */
public record OrderExecutionResponse(
		String executionId,
		String previewId,
		String clientOrderId,
		String brokerMode,
		OrderExecutionStatus status,
		String brokerOrderId,
		OrderExecutionFailureType failureType,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		OffsetDateTime submittedAt,
		OffsetDateTime completedAt) {

	/**
	 * 객체가 로그에 기록되더라도 주문 관련 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 주문 식별값이 제거된 실행 상태 설명
	 */
	@Override
	public String toString() {
		return "OrderExecutionResponse[executionId=***, previewId=***, clientOrderId=***"
				+ ", brokerMode=" + brokerMode + ", status=" + status
				+ ", brokerOrderId=***, failureType=" + failureType
				+ ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
				+ ", submittedAt=" + submittedAt + ", completedAt=" + completedAt + "]";
	}
}

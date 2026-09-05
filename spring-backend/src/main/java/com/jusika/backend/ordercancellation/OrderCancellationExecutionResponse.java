package com.jusika.backend.ordercancellation;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 승인된 취소 미리보기의 모의 또는 향후 실제 취소 실행 상태를 표현합니다.
 *
 * @param executionId 우리 서버가 만든 취소 실행 식별값
 * @param previewId 실행에 사용한 취소 미리보기 식별값
 * @param orderId 취소 대상 토스증권 주문 식별값
 * @param brokerMode 모의 취소 또는 실제 증권사 연결 모드
 * @param status 현재 취소 실행 상태
 * @param failureType 안전하게 분류한 실패 종류이며 정상이면 null
 * @param createdAt 취소 실행 기록을 만든 시각
 * @param updatedAt 취소 실행 상태를 마지막으로 바꾼 시각
 * @param submittedAt 취소 전송을 시작한 시각이며 시작 전에는 null
 * @param completedAt 접수 또는 거절 결과를 확정한 시각이며 미확정이면 null
 */
public record OrderCancellationExecutionResponse(
		String executionId,
		String previewId,
		String orderId,
		String brokerMode,
		OrderExecutionStatus status,
		OrderExecutionFailureType failureType,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		OffsetDateTime submittedAt,
		OffsetDateTime completedAt) {

	/**
	 * 객체가 로그에 기록되더라도 주문과 실행 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 민감한 식별값이 제거된 취소 실행 설명
	 */
	@Override
	public String toString() {
		return "OrderCancellationExecutionResponse[executionId=***, previewId=***, orderId=***"
				+ ", brokerMode=" + brokerMode + ", status=" + status
				+ ", failureType=" + failureType + ", createdAt=" + createdAt
				+ ", updatedAt=" + updatedAt + ", submittedAt=" + submittedAt
				+ ", completedAt=" + completedAt + "]";
	}
}

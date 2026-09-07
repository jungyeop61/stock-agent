package com.jusika.backend.conditionalordercancellation;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 승인된 조건 주문 취소의 모의 또는 향후 실제 실행 상태입니다.
 *
 * @param executionId 우리 서버가 만든 취소 실행 식별값
 * @param previewId 실행에 사용한 취소 미리보기 식별값
 * @param accountSeq 취소 대상 계좌 식별값
 * @param conditionalOrderId 취소 대상 토스증권 조건 주문 식별값
 * @param brokerMode 모의 취소 또는 실제 증권사 연결 모드
 * @param status 현재 취소 실행 상태
 * @param failureType 안전하게 분류한 실패 종류이며 정상이면 null
 * @param createdAt 실행 기록 생성 시각
 * @param updatedAt 상태를 마지막으로 바꾼 시각
 * @param submittedAt 취소 전송을 시작한 시각
 * @param completedAt 성공 또는 거절을 확정한 시각
 */
public record ConditionalOrderCancellationExecutionResponse(
		String executionId,
		String previewId,
		long accountSeq,
		String conditionalOrderId,
		String brokerMode,
		OrderExecutionStatus status,
		OrderExecutionFailureType failureType,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		OffsetDateTime submittedAt,
		OffsetDateTime completedAt) {

	/** 로그에 계좌와 조건 주문 식별값이 노출되지 않는 실행 설명을 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderCancellationExecutionResponse[executionId=***, previewId=***"
				+ ", accountSeq=***, conditionalOrderId=***, brokerMode=" + brokerMode
				+ ", status=" + status + ", failureType=" + failureType
				+ ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
				+ ", submittedAt=" + submittedAt + ", completedAt=" + completedAt + "]";
	}
}

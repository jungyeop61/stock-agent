package com.jusika.backend.amountorderexecution;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 승인된 금액 주문 미리보기의 실행 상태와 비공개 주문 식별값을 표현합니다.
 *
 * @param executionId 우리 서버가 만든 금액 주문 실행 식별값
 * @param previewId 실행에 사용한 금액 주문 미리보기 식별값
 * @param clientOrderId 증권사 중복 주문 방지에 사용할 멱등성 식별값
 * @param brokerMode 모의 주문 또는 실제 증권사 연결 모드
 * @param status 현재 금액 주문 실행 상태
 * @param brokerOrderId 증권사가 반환한 주문 식별값이며 접수 전에는 null
 * @param failureType 금융정보를 포함하지 않은 실패 분류이며 정상이면 null
 * @param createdAt 실행 기록을 만든 시각
 * @param updatedAt 실행 상태를 마지막으로 바꾼 시각
 * @param submittedAt 주문 제출을 시작한 시각이며 시작 전에는 null
 * @param recoveryAttemptedAt 결과 불명 주문의 안전 복구를 시작한 시각이며 시도 전에는 null
 * @param completedAt 접수 또는 거절을 확정한 시각이며 결과 불명이면 null
 */
public record AmountOrderExecutionResponse(
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
		OffsetDateTime recoveryAttemptedAt,
		OffsetDateTime completedAt) {

	/**
	 * 객체가 로그에 기록되더라도 금액 주문 관련 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 주문 식별값이 제거된 실행 상태 설명
	 */
	@Override
	public String toString() {
		return "AmountOrderExecutionResponse[executionId=***, previewId=***, clientOrderId=***"
				+ ", brokerMode=" + brokerMode + ", status=" + status
				+ ", brokerOrderId=***, failureType=" + failureType
				+ ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
				+ ", submittedAt=" + submittedAt
				+ ", recoveryAttemptedAt=" + recoveryAttemptedAt
				+ ", completedAt=" + completedAt + "]";
	}
}

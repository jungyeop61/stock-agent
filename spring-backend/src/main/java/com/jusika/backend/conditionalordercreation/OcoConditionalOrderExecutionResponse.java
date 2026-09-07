package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 승인된 OCO 조건 주문의 모의 또는 향후 실제 실행 상태를 표현합니다.
 */
public record OcoConditionalOrderExecutionResponse(
		String executionId,
		String previewId,
		String clientOrderId,
		String conditionalOrderId,
		String brokerMode,
		OrderExecutionStatus status,
		OrderExecutionFailureType failureType,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		OffsetDateTime submittedAt,
		OffsetDateTime completedAt) {

	/** 로그에 실행·미리보기·멱등성·조건 주문 식별값이 노출되지 않도록 가립니다. */
	@Override
	public String toString() {
		return "OcoConditionalOrderExecutionResponse[executionId=***, previewId=***"
				+ ", clientOrderId=***, conditionalOrderId=***, brokerMode=" + brokerMode
				+ ", status=" + status + ", failureType=" + failureType
				+ ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
				+ ", submittedAt=" + submittedAt + ", completedAt=" + completedAt + "]";
	}
}

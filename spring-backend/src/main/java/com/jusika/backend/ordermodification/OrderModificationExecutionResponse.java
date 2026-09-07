package com.jusika.backend.ordermodification;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/** 승인된 주문 정정의 모의 또는 향후 실제 실행 상태를 표현합니다. */
public record OrderModificationExecutionResponse(
		String executionId,
		String previewId,
		String originalOrderId,
		String operationOrderId,
		String brokerMode,
		OrderExecutionStatus status,
		OrderExecutionFailureType failureType,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		OffsetDateTime submittedAt,
		OffsetDateTime completedAt) {

	/** 로그에 원주문과 새 주문의 식별값이 노출되지 않도록 가립니다. */
	@Override
	public String toString() {
		return "OrderModificationExecutionResponse[executionId=***, previewId=***"
				+ ", originalOrderId=***, operationOrderId=***, brokerMode=" + brokerMode
				+ ", status=" + status + ", failureType=" + failureType
				+ ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
				+ ", submittedAt=" + submittedAt + ", completedAt=" + completedAt + "]";
	}
}

package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 조건 주문 정정의 모의 또는 향후 실제 제출 상태와 새 조건 주문 식별값입니다.
 *
 * @param executionId 우리 서버의 정정 실행 식별값
 * @param previewId 실행에 사용한 미리보기 식별값
 * @param accountSeq 정정 대상 계좌 식별값
 * @param originalConditionalOrderId 정정 전 조건 주문 식별값
 * @param replacementConditionalOrderId 성공 시 새로 발급된 조건 주문 식별값
 * @param brokerMode 모의 실행 또는 실제 증권사 연결 모드
 * @param status 현재 실행 상태
 * @param failureType 안전하게 분류한 실패 유형
 * @param createdAt 실행 기록 생성 시각
 * @param updatedAt 마지막 상태 변경 시각
 * @param submittedAt 정정 제출을 시작한 시각
 * @param completedAt 성공 또는 거절을 확정한 시각
 */
public record ConditionalOrderModificationExecutionResponse(
		String executionId,
		String previewId,
		long accountSeq,
		String originalConditionalOrderId,
		String replacementConditionalOrderId,
		String brokerMode,
		OrderExecutionStatus status,
		OrderExecutionFailureType failureType,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		OffsetDateTime submittedAt,
		OffsetDateTime completedAt) {

	/** 로그에 계좌와 모든 조건 주문 식별값이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderModificationExecutionResponse[executionId=***, previewId=***"
				+ ", accountSeq=***, originalConditionalOrderId=***"
				+ ", replacementConditionalOrderId=***, brokerMode=" + brokerMode
				+ ", status=" + status + ", failureType=" + failureType
				+ ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
				+ ", submittedAt=" + submittedAt + ", completedAt=" + completedAt + "]";
	}
}

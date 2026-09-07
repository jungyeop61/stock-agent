package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

/**
 * 승인된 단일 조건 주문의 모의 또는 향후 실제 실행 상태를 표현합니다.
 *
 * @param executionId 우리 서버가 만든 실행 식별값
 * @param previewId 실행에 사용한 미리보기 식별값
 * @param clientOrderId 중복 생성을 막는 멱등성 식별값
 * @param conditionalOrderId 모의 또는 증권사가 반환한 조건 주문 식별값
 * @param brokerMode 실행 경계가 모의인지 실제인지 나타내는 이름
 * @param status 실행 처리 상태
 * @param failureType 실패가 확정인지 결과 불명인지 나타내는 유형
 * @param createdAt 실행 기록 생성 시각
 * @param updatedAt 마지막 상태 변경 시각
 * @param submittedAt 제출 시작 시각
 * @param completedAt 접수·거절·결과 불명 처리가 끝난 시각
 */
public record SingleConditionalOrderExecutionResponse(
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

	/**
	 * 로그에 모든 실행·주문·멱등성 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 식별값이 제거된 조건 주문 실행 설명
	 */
	@Override
	public String toString() {
		return "SingleConditionalOrderExecutionResponse[executionId=***, previewId=***"
				+ ", clientOrderId=***, conditionalOrderId=***, brokerMode=" + brokerMode
				+ ", status=" + status + ", failureType=" + failureType
				+ ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
				+ ", submittedAt=" + submittedAt + ", completedAt=" + completedAt + "]";
	}
}

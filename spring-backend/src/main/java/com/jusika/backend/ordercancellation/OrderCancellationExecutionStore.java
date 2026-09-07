package com.jusika.backend.ordercancellation;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 취소 실행의 중복 방지와 상태 저장 기능을 서비스에서 분리합니다. */
interface OrderCancellationExecutionStore {

	/** 원주문당 한 번만 취소 실행권을 확보합니다. */
	boolean claim(OrderCancellationExecutionResponse execution);

	/** 준비된 실행을 제출 중으로 변경합니다. */
	boolean markSubmitting(String executionId, OffsetDateTime submittedAt);

	/** 제출 전 내부 오류로 종료합니다. */
	boolean markPreparationFailed(String executionId, OffsetDateTime failedAt);

	/** 증권사 취소 접수를 확인한 상태로 변경합니다. */
	boolean markAccepted(String executionId, String operationOrderId, OffsetDateTime completedAt);

	/** 증권사의 확정 거절 상태로 변경합니다. */
	boolean markRejected(String executionId, OffsetDateTime failedAt);

	/** 취소 접수 여부를 알 수 없는 상태로 변경합니다. */
	boolean markUnknown(String executionId, OffsetDateTime failedAt);

	/** 실행 식별값으로 취소 실행을 조회합니다. */
	Optional<OrderCancellationExecutionResponse> findById(String executionId);

	/** 미리보기 식별값으로 취소 실행을 조회합니다. */
	Optional<OrderCancellationExecutionResponse> findByPreviewId(String previewId);

	/** 원주문 식별값으로 취소 실행을 조회합니다. */
	Optional<OrderCancellationExecutionResponse> findByOrderId(String orderId);
}

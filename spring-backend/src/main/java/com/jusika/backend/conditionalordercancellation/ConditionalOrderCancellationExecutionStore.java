package com.jusika.backend.conditionalordercancellation;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 조건 주문 취소 실행의 중복 방지와 상태 저장 기능을 분리합니다. */
interface ConditionalOrderCancellationExecutionStore {

	/** 같은 계좌의 같은 조건 주문에 첫 취소 실행권만 부여합니다. */
	boolean claim(ConditionalOrderCancellationExecutionResponse execution);

	/** 준비된 실행을 제출 중 상태로 변경합니다. */
	boolean markSubmitting(String executionId, OffsetDateTime submittedAt);

	/** 제출 전 내부 오류로 실행을 종료합니다. */
	boolean markPreparationFailed(String executionId, OffsetDateTime failedAt);

	/** 조건 주문 취소 성공을 접수 상태로 기록합니다. */
	boolean markAccepted(String executionId, OffsetDateTime completedAt);

	/** 증권사의 확정 거절 상태로 기록합니다. */
	boolean markRejected(String executionId, OffsetDateTime failedAt);

	/** 취소 여부를 알 수 없는 상태로 기록합니다. */
	boolean markUnknown(String executionId, OffsetDateTime failedAt);

	/** 실행 식별값으로 저장된 취소 실행을 조회합니다. */
	Optional<ConditionalOrderCancellationExecutionResponse> findById(String executionId);

	/** 미리보기 식별값으로 저장된 취소 실행을 조회합니다. */
	Optional<ConditionalOrderCancellationExecutionResponse> findByPreviewId(String previewId);

	/** 계좌와 조건 주문 식별값으로 기존 취소 실행을 조회합니다. */
	Optional<ConditionalOrderCancellationExecutionResponse> findByTarget(
			long accountSeq, String conditionalOrderId);
}

package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 단일 조건 주문 실행의 중복 방지와 상태 저장 기능을 서비스에서 분리합니다.
 */
interface SingleConditionalOrderExecutionStore {

	/** 미리보기 한 건당 한 번만 실행권을 확보합니다. */
	boolean claim(SingleConditionalOrderExecutionResponse execution);

	/** 준비된 실행을 제출 중으로 변경합니다. */
	boolean markSubmitting(String executionId, OffsetDateTime submittedAt);

	/** 제출 전 내부 오류로 실행을 종료합니다. */
	boolean markPreparationFailed(String executionId, OffsetDateTime failedAt);

	/** 조건 주문 식별값과 함께 접수 상태로 변경합니다. */
	boolean markAccepted(String executionId, String conditionalOrderId, OffsetDateTime completedAt);

	/** 증권사의 확정 거절 상태로 변경합니다. */
	boolean markRejected(String executionId, OffsetDateTime failedAt);

	/** 조건 주문 접수 여부를 알 수 없는 상태로 변경합니다. */
	boolean markUnknown(String executionId, OffsetDateTime failedAt);

	/** 실행 식별값으로 저장된 실행 기록을 조회합니다. */
	Optional<SingleConditionalOrderExecutionResponse> findById(String executionId);

	/** 미리보기 식별값으로 저장된 실행 기록을 조회합니다. */
	Optional<SingleConditionalOrderExecutionResponse> findByPreviewId(String previewId);
}

package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 조건 주문 정정 실행의 중복 방지와 상태 저장 기능을 분리합니다. */
interface ConditionalOrderModificationExecutionStore {

	/** 같은 계좌의 같은 원조건 주문에 첫 정정 실행권만 부여합니다. */
	boolean claim(ConditionalOrderModificationExecutionResponse execution);

	/** 준비된 정정 실행을 제출 중 상태로 변경합니다. */
	boolean markSubmitting(String executionId, OffsetDateTime submittedAt);

	/** 제출 전 내부 오류로 실행을 종료합니다. */
	boolean markPreparationFailed(String executionId, OffsetDateTime failedAt);

	/** 새 조건 주문 식별값과 정정 성공 상태를 기록합니다. */
	boolean markAccepted(String executionId, String replacementId, OffsetDateTime completedAt);

	/** 증권사의 확정 거절 상태를 기록합니다. */
	boolean markRejected(String executionId, OffsetDateTime failedAt);

	/** 정정 결과를 알 수 없는 상태로 기록합니다. */
	boolean markUnknown(String executionId, OffsetDateTime failedAt);

	/** 실행 식별값으로 저장된 정정 결과를 조회합니다. */
	Optional<ConditionalOrderModificationExecutionResponse> findById(String executionId);

	/** 계좌와 원조건 주문 식별값으로 기존 실행을 조회합니다. */
	Optional<ConditionalOrderModificationExecutionResponse> findByTarget(
			long accountSeq, String conditionalOrderId);
}

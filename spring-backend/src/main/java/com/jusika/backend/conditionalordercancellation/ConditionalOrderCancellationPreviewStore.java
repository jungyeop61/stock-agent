package com.jusika.backend.conditionalordercancellation;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 조건 주문 취소 미리보기와 승인 상태를 보관하는 저장소 경계입니다. */
public interface ConditionalOrderCancellationPreviewStore {

	/** 새 조건 주문 취소 미리보기 전체 내용을 저장합니다. */
	ConditionalOrderCancellationPreviewResponse save(
			ConditionalOrderCancellationPreviewResponse preview);

	/** 유효한 승인 대기 미리보기만 승인 상태로 변경합니다. */
	boolean approvePending(String previewId, OffsetDateTime approvedAt);

	/** 유효시간이 지난 승인 대기 미리보기만 만료 상태로 변경합니다. */
	boolean expirePending(String previewId, OffsetDateTime now);

	/** 승인된 미리보기만 취소 실행에 사용된 상태로 변경합니다. */
	boolean consumeApproved(String previewId, OffsetDateTime consumedAt);

	/** 식별값으로 저장된 취소 미리보기를 조회합니다. */
	Optional<ConditionalOrderCancellationPreviewResponse> findById(String previewId);
}

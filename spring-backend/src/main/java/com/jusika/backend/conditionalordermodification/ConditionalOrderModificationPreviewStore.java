package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 조건 주문 정정 미리보기와 승인 상태를 보관하는 저장소 경계입니다. */
public interface ConditionalOrderModificationPreviewStore {

	/** 원주문 사본과 정정 후 전체 구성을 새 미리보기로 저장합니다. */
	ConditionalOrderModificationPreviewResponse save(
			ConditionalOrderModificationPreviewResponse preview);

	/** 유효시간 안의 승인 대기 미리보기만 승인합니다. */
	boolean approvePending(String previewId, OffsetDateTime approvedAt);

	/** 승인하지 않은 채 유효시간이 지난 미리보기를 만료시킵니다. */
	boolean expirePending(String previewId, OffsetDateTime now);

	/** 승인된 미리보기만 정정 실행에 사용된 상태로 변경합니다. */
	boolean consumeApproved(String previewId, OffsetDateTime consumedAt);

	/** 식별값으로 저장된 조건 주문 정정 미리보기를 조회합니다. */
	Optional<ConditionalOrderModificationPreviewResponse> findById(String previewId);
}

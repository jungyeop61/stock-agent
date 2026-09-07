package com.jusika.backend.ordermodification;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 정정 미리보기의 변경 불가 값과 승인 상태를 보관하는 저장소 경계입니다. */
public interface OrderModificationPreviewStore {
	/** 새 정정 미리보기를 저장합니다. */
	OrderModificationPreviewResponse save(OrderModificationPreviewResponse preview);
	/** 유효한 승인 대기 미리보기만 승인합니다. */
	boolean approvePending(String previewId, OffsetDateTime approvedAt);
	/** 유효시간이 지난 승인 대기 미리보기를 만료 처리합니다. */
	boolean expirePending(String previewId, OffsetDateTime now);
	/** 승인된 정정 미리보기를 실행에 사용된 상태로 변경합니다. */
	boolean consumeApproved(String previewId, OffsetDateTime consumedAt);
	/** 식별값으로 정정 미리보기를 조회합니다. */
	Optional<OrderModificationPreviewResponse> findById(String previewId);
}

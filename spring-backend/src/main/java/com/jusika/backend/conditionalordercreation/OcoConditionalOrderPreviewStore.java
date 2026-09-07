package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;
import java.util.Optional;

/** OCO 조건 주문 미리보기의 저장과 원자적 상태 변경을 서비스에서 분리합니다. */
interface OcoConditionalOrderPreviewStore {
	/** 저장할 OCO 미리보기 전체 내용을 새 행으로 보관합니다. */
	OcoConditionalOrderPreviewResponse save(OcoConditionalOrderPreviewResponse preview);
	/** 승인 가능한 미리보기 한 건만 승인 상태로 변경합니다. */
	boolean approvePending(String previewId, OffsetDateTime approvedAt);
	/** 유효시간이 지난 승인 대기 미리보기 한 건만 만료 처리합니다. */
	boolean expirePending(String previewId, OffsetDateTime now);
	/** 승인된 미리보기 한 건만 실행에 사용된 상태로 변경합니다. */
	boolean consumeApproved(String previewId, OffsetDateTime consumedAt);
	/** 식별값으로 저장된 OCO 미리보기를 조회합니다. */
	Optional<OcoConditionalOrderPreviewResponse> findById(String previewId);
}

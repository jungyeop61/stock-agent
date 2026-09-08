package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA로 조건 주문 정정 미리보기 저장소 경계를 구현합니다. */
@Repository
class JpaConditionalOrderModificationPreviewStore
		implements ConditionalOrderModificationPreviewStore {

	private final ConditionalOrderModificationPreviewJpaRepository repository;

	/** 조건 주문 정정 미리보기 JPA 저장소를 전달받습니다. */
	JpaConditionalOrderModificationPreviewStore(
			ConditionalOrderModificationPreviewJpaRepository repository) {
		this.repository = repository;
	}

	/** 새 조건 주문 정정 미리보기의 변경 불가 내용을 저장합니다. */
	@Override
	@Transactional
	public ConditionalOrderModificationPreviewResponse save(
			ConditionalOrderModificationPreviewResponse preview) {
		return repository.save(
				ConditionalOrderModificationPreviewEntity.from(preview)).toResponse();
	}

	/** 유효한 승인 대기 미리보기만 승인 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
		return repository.approvePending(previewId, approvedAt) == 1;
	}

	/** 승인하지 않은 채 만료된 미리보기만 만료 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean expirePending(String previewId, OffsetDateTime now) {
		return repository.expirePending(previewId, now) == 1;
	}

	/** 승인된 미리보기만 정정 실행에 사용된 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
		return repository.consumeApproved(previewId, consumedAt) == 1;
	}

	/** 식별값으로 저장된 조건 주문 정정 미리보기를 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<ConditionalOrderModificationPreviewResponse> findById(String previewId) {
		return repository.findById(previewId)
				.map(ConditionalOrderModificationPreviewEntity::toResponse);
	}
}

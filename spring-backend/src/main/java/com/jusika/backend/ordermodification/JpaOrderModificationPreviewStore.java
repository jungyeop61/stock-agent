package com.jusika.backend.ordermodification;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA로 정정 미리보기 저장소 경계를 구현합니다. */
@Repository
class JpaOrderModificationPreviewStore implements OrderModificationPreviewStore {
	private final OrderModificationPreviewJpaRepository repository;

	/** 정정 미리보기 JPA 저장소를 전달받습니다. */
	JpaOrderModificationPreviewStore(OrderModificationPreviewJpaRepository repository) {
		this.repository = repository;
	}

	/** 새 정정 미리보기를 저장합니다. */
	@Override @Transactional
	public OrderModificationPreviewResponse save(OrderModificationPreviewResponse preview) {
		return repository.save(OrderModificationPreviewEntity.from(preview)).toResponse();
	}
	/** 유효한 승인 대기 미리보기만 승인합니다. */
	@Override @Transactional
	public boolean approvePending(String id, OffsetDateTime at) { return repository.approvePending(id, at) == 1; }
	/** 유효시간이 지난 승인 대기 미리보기를 만료 처리합니다. */
	@Override @Transactional
	public boolean expirePending(String id, OffsetDateTime now) { return repository.expirePending(id, now) == 1; }
	/** 승인된 정정 미리보기를 실행에 사용된 상태로 변경합니다. */
	@Override @Transactional
	public boolean consumeApproved(String id, OffsetDateTime at) { return repository.consumeApproved(id, at) == 1; }
	/** 식별값으로 저장된 정정 미리보기를 조회합니다. */
	@Override @Transactional(readOnly = true)
	public Optional<OrderModificationPreviewResponse> findById(String id) {
		return repository.findById(id).map(OrderModificationPreviewEntity::toResponse);
	}
}

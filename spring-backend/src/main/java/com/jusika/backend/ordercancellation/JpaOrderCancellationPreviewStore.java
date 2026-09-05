package com.jusika.backend.ordercancellation;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA를 사용해 취소 미리보기 저장소 경계를 구현합니다.
 */
@Repository
class JpaOrderCancellationPreviewStore implements OrderCancellationPreviewStore {

	private final OrderCancellationPreviewJpaRepository repository;

	/**
	 * 취소 미리보기 JPA 저장소를 전달받습니다.
	 *
	 * @param repository 실제 데이터베이스 접근을 담당할 저장소
	 */
	JpaOrderCancellationPreviewStore(OrderCancellationPreviewJpaRepository repository) {
		this.repository = repository;
	}

	/** 새 취소 미리보기의 변경 불가 내용을 저장합니다. */
	@Override
	@Transactional
	public OrderCancellationPreviewResponse save(OrderCancellationPreviewResponse preview) {
		return repository.save(OrderCancellationPreviewEntity.from(preview)).toResponse();
	}

	/** 유효한 승인 대기 미리보기만 승인 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
		return repository.approvePending(previewId, approvedAt) == 1;
	}

	/** 유효시간이 지난 승인 대기 미리보기만 만료 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean expirePending(String previewId, OffsetDateTime now) {
		return repository.expirePending(previewId, now) == 1;
	}

	/** 승인된 취소 미리보기만 실행에 사용된 상태로 변경합니다. */
	@Override
	@Transactional
	public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
		return repository.consumeApproved(previewId, consumedAt) == 1;
	}

	/** 식별값으로 저장된 취소 미리보기를 조회합니다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<OrderCancellationPreviewResponse> findById(String previewId) {
		return repository.findById(previewId).map(OrderCancellationPreviewEntity::toResponse);
	}
}

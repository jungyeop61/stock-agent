package com.jusika.backend.conditionalordercreation;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 스프링 데이터 JPA와 조건부 갱신으로 OCO 미리보기 저장소를 구현합니다. */
@Repository
class JpaOcoConditionalOrderPreviewStore implements OcoConditionalOrderPreviewStore {
	private final OcoConditionalOrderPreviewJpaRepository repository;

	/** OCO 미리보기 JPA 저장소를 전달받습니다. */
	JpaOcoConditionalOrderPreviewStore(OcoConditionalOrderPreviewJpaRepository repository) {
		this.repository = repository;
	}

	/** OCO 미리보기 전체 내용을 새 행으로 저장합니다. */
	@Override @Transactional
	public OcoConditionalOrderPreviewResponse save(OcoConditionalOrderPreviewResponse preview) {
		return repository.save(OcoConditionalOrderPreviewEntity.from(preview)).toResponse();
	}

	/** 승인 가능한 행만 원자적으로 승인 상태로 변경합니다. */
	@Override @Transactional
	public boolean approvePending(String id, OffsetDateTime at) {
		return repository.approvePending(id, at) == 1;
	}

	/** 유효시간이 지난 승인 대기 행만 만료 처리합니다. */
	@Override @Transactional
	public boolean expirePending(String id, OffsetDateTime now) {
		return repository.expirePending(id, now) == 1;
	}

	/** 승인된 행 한 건만 실행에 사용된 상태로 변경합니다. */
	@Override @Transactional
	public boolean consumeApproved(String id, OffsetDateTime at) {
		return repository.consumeApproved(id, at) == 1;
	}

	/** 식별값으로 저장된 OCO 미리보기를 읽기 전용으로 조회합니다. */
	@Override @Transactional(readOnly = true)
	public Optional<OcoConditionalOrderPreviewResponse> findById(String id) {
		return repository.findById(id).map(OcoConditionalOrderPreviewEntity::toResponse);
	}
}

package com.jusika.backend.amountorderpreview;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스프링 데이터 JPA를 사용해 금액 주문 미리보기 저장소 경계를 구현합니다.
 */
@Repository
class JpaAmountOrderPreviewStore implements AmountOrderPreviewStore {

	private final AmountOrderPreviewJpaRepository repository;

	/**
	 * 실제 데이터베이스 접근을 담당하는 JPA 저장소를 전달받습니다.
	 *
	 * @param repository 금액 주문 미리보기 JPA 저장소
	 */
	JpaAmountOrderPreviewStore(AmountOrderPreviewJpaRepository repository) {
		this.repository = repository;
	}

	/**
	 * 금액 주문 미리보기 전체 내용을 새 데이터베이스 행으로 저장합니다.
	 *
	 * @param preview 저장할 금액 주문 미리보기
	 * @return 데이터베이스에 저장된 금액 주문 미리보기
	 */
	@Override
	@Transactional
	public AmountOrderPreviewResponse save(AmountOrderPreviewResponse preview) {
		return repository.save(AmountOrderPreviewEntity.from(preview)).toResponse();
	}

	/**
	 * 승인 가능한 한 행만 조건부 갱신해 중복 승인을 막습니다.
	 *
	 * @param previewId 승인할 금액 미리보기 식별값
	 * @param approvedAt 승인 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	@Override
	@Transactional
	public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
		return repository.approvePending(previewId, approvedAt) == 1;
	}

	/**
	 * 승인 대기 중인 만료 행만 조건부 갱신합니다.
	 *
	 * @param previewId 만료 여부를 반영할 금액 미리보기 식별값
	 * @param now 현재 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	@Override
	@Transactional
	public boolean expirePending(String previewId, OffsetDateTime now) {
		return repository.expirePending(previewId, now) == 1;
	}

	/**
	 * 식별값에 해당하는 저장 행을 API 응답 형태로 반환합니다.
	 *
	 * @param previewId 조회할 미리보기 식별값
	 * @return 저장된 금액 주문 미리보기이며 없으면 빈 값
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<AmountOrderPreviewResponse> findById(String previewId) {
		return repository.findById(previewId).map(AmountOrderPreviewEntity::toResponse);
	}
}

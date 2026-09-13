package com.jusika.backend.brokeraudit;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** LIVE 주문 변경 감사 사건의 추가 전용 저장과 역순 조회를 담당합니다. */
interface BrokerMutationAuditEventJpaRepository
		extends JpaRepository<BrokerMutationAuditEventEntity, Long> {

	/** 토스 요청 결과 불명 사건이 하나라도 저장되어 있는지 확인합니다. */
	boolean existsByStageAndOutcome(
			BrokerMutationAuditStage stage,
			BrokerMutationAuditOutcome outcome);

	/** 가장 최근 사건부터 지정한 개수만큼 조회합니다. */
	List<BrokerMutationAuditEventEntity> findAllByOrderByAuditEventIdDesc(Pageable pageable);

	/** 커서보다 오래된 사건을 최근 순서로 조회합니다. */
	List<BrokerMutationAuditEventEntity> findByAuditEventIdLessThanOrderByAuditEventIdDesc(
			long auditEventId,
			Pageable pageable);
}

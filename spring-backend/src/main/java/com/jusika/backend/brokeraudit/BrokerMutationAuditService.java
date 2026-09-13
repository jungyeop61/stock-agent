package com.jusika.backend.brokeraudit;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.internalauth.CurrentRequestIdProvider;

/** LIVE 주문 변경 사건을 민감정보 없이 별도 트랜잭션으로 저장하고 조회합니다. */
@Service
public class BrokerMutationAuditService {

	private static final int MAX_PAGE_SIZE = 100;

	private final BrokerMutationAuditEventJpaRepository repository;
	private final CurrentRequestIdProvider requestIdProvider;
	private final Clock clock;

	/** 감사 저장소, 현재 요청 추적기와 시스템 시계를 연결합니다. */
	public BrokerMutationAuditService(
			BrokerMutationAuditEventJpaRepository repository,
			CurrentRequestIdProvider requestIdProvider,
			Clock clock) {
		this.repository = repository;
		this.requestIdProvider = requestIdProvider;
		this.clock = clock;
	}

	/**
	 * 계좌·종목·금액·주문 식별값 없이 LIVE 주문 변경 사건 한 건을 즉시 저장합니다.
	 *
	 * @param capability 주문 변경 기능 종류
	 * @param stage 사건이 발생한 처리 단계
	 * @param outcome 민감정보 없는 처리 결과
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(
			BrokerMutationCapability capability,
			BrokerMutationAuditStage stage,
			BrokerMutationAuditOutcome outcome) {
		if (capability == null || stage == null || outcome == null) {
			throw new IllegalArgumentException("LIVE 주문 변경 감사 사건 정보가 필요합니다.");
		}
		repository.saveAndFlush(new BrokerMutationAuditEventEntity(
				requestIdProvider.findCurrentRequestId().orElse(null),
				capability,
				stage,
				outcome,
				OffsetDateTime.now(clock)));
	}

	/**
	 * 최신 감사 사건을 식별값 역순의 커서 페이지로 반환합니다.
	 *
	 * @param beforeEventId 이 값보다 작은 사건만 조회하는 선택 커서
	 * @param limit 한 번에 반환할 사건 수
	 * @return 민감정보 없는 감사 사건 페이지
	 */
	@Transactional(readOnly = true)
	public BrokerMutationAuditListResponse getEvents(Long beforeEventId, int limit) {
		if (beforeEventId != null && beforeEventId <= 0) {
			throw new IllegalArgumentException("감사 사건 커서는 1 이상이어야 합니다.");
		}
		if (limit < 1 || limit > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("감사 사건 조회 개수는 1 이상 100 이하여야 합니다.");
		}
		PageRequest page = PageRequest.of(0, limit + 1);
		List<BrokerMutationAuditEventEntity> found = beforeEventId == null
				? repository.findAllByOrderByAuditEventIdDesc(page)
				: repository.findByAuditEventIdLessThanOrderByAuditEventIdDesc(
						beforeEventId, page);
		boolean hasNext = found.size() > limit;
		List<BrokerMutationAuditEventResponse> events = found.stream()
				.limit(limit)
				.map(this::toResponse)
				.toList();
		Long nextCursor = hasNext && !events.isEmpty()
				? events.get(events.size() - 1).auditEventId()
				: null;
		return new BrokerMutationAuditListResponse(events, nextCursor, hasNext);
	}

	/** 저장된 감사 사건을 민감정보 없는 HTTP 응답으로 변환합니다. */
	private BrokerMutationAuditEventResponse toResponse(BrokerMutationAuditEventEntity event) {
		return new BrokerMutationAuditEventResponse(
				event.auditEventId(),
				event.requestId(),
				event.capability(),
				event.stage(),
				event.outcome(),
				event.occurredAt());
	}
}

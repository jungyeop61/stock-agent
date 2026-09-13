package com.jusika.backend.brokersafety;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jusika.backend.brokeraudit.BrokerMutationAuditService;

/** 결과 불명 LIVE 주문 사고의 수동 확인 이력을 저장하고 자동 안전정지 상태를 계산합니다. */
@Service
public class BrokerUnknownIncidentAcknowledgementService {

	private final JdbcTemplate jdbcTemplate;
	private final BrokerMutationAuditService auditService;
	private final Clock clock;

	/** 확인 이력 데이터베이스, 변경 감사 조회 서비스와 시스템 시계를 연결합니다. */
	public BrokerUnknownIncidentAcknowledgementService(
			JdbcTemplate jdbcTemplate,
			BrokerMutationAuditService auditService,
			Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.auditService = auditService;
		this.clock = clock;
	}

	/** 마지막 확인 시점 이후 결과 불명 토스 요청이 남아 있는지 반환합니다. */
	@Transactional(readOnly = true)
	public boolean isHaltActive() {
		Acknowledgement latest = findLatestAcknowledgement();
		long acknowledgedThrough = latest == null ? 0L : latest.auditEventId();
		return auditService.hasUnknownBrokerRequestAfter(acknowledgedThrough);
	}

	/** 민감정보 없이 현재 자동 안전정지와 마지막 사용자 확인 상태를 반환합니다. */
	@Transactional(readOnly = true)
	public BrokerUnknownIncidentStatusResponse getStatus() {
		Acknowledgement latest = findLatestAcknowledgement();
		long acknowledgedThrough = latest == null ? 0L : latest.auditEventId();
		return new BrokerUnknownIncidentStatusResponse(
				auditService.hasUnknownBrokerRequestAfter(acknowledgedThrough),
				latest == null ? null : latest.auditEventId(),
				latest == null ? null : latest.acknowledgedAt());
	}

	/**
	 * 사용자가 명시한 UNKNOWN 감사 사건까지 확인 이력을 원자적으로 추가합니다.
	 * 더 최신 결과 불명 사건은 확인 범위에 포함하지 않아 자동 정지를 유지합니다.
	 */
	@Transactional
	public BrokerUnknownIncidentStatusResponse acknowledge(
			BrokerUnknownIncidentAcknowledgementRequest request) {
		validateRequest(request);
		lockAcknowledgements();
		if (!auditService.isUnknownBrokerRequest(request.unknownAuditEventId())) {
			throw new BrokerUnknownIncidentAcknowledgementException(
					"확인 대상은 실제 토스 요청의 결과 불명 감사 사건이어야 합니다.");
		}
		Acknowledgement latest = findLatestAcknowledgement();
		if (latest != null && request.unknownAuditEventId() <= latest.auditEventId()) {
			return createStatus(latest);
		}
		OffsetDateTime acknowledgedAt = OffsetDateTime.now(clock);
		jdbcTemplate.update(
				"""
				insert into broker_unknown_incident_acknowledgements
				    (acknowledged_through_audit_event_id, acknowledged_at)
				values (?, ?)
				""",
				request.unknownAuditEventId(),
				acknowledgedAt);
		return createStatus(new Acknowledgement(
				request.unknownAuditEventId(), acknowledgedAt));
	}

	/** 누락되거나 명시적으로 확인하지 않은 요청을 데이터베이스 접근 전에 거절합니다. */
	private void validateRequest(BrokerUnknownIncidentAcknowledgementRequest request) {
		if (request == null) {
			throw new BrokerUnknownIncidentAcknowledgementException("결과 불명 사고 확인 요청이 필요합니다.");
		}
		if (request.unknownAuditEventId() <= 0) {
			throw new BrokerUnknownIncidentAcknowledgementException(
					"확인할 감사 사건 식별값은 1 이상이어야 합니다.");
		}
		if (!request.confirmed()) {
			throw new BrokerUnknownIncidentAcknowledgementException(
					"결과 불명 주문 상태를 직접 확인했다는 명시가 필요합니다.");
		}
	}

	/** 동시에 들어온 확인 요청을 한 줄로 직렬화합니다. */
	private void lockAcknowledgements() {
		jdbcTemplate.queryForObject(
				"select lock_id from broker_unknown_incident_acknowledgement_lock "
						+ "where lock_id = 1 for update",
				Integer.class);
	}

	/** 가장 최근에 사용자가 확인한 UNKNOWN 감사 사건과 시각을 조회합니다. */
	private Acknowledgement findLatestAcknowledgement() {
		List<Acknowledgement> found = jdbcTemplate.query(
				"""
				select acknowledged_through_audit_event_id, acknowledged_at
				from broker_unknown_incident_acknowledgements
				order by acknowledgement_id desc
				limit 1
				""",
				this::mapAcknowledgement);
		return found.isEmpty() ? null : found.getFirst();
	}

	/** 데이터베이스 확인 이력 한 행을 내부 판단 객체로 변환합니다. */
	private Acknowledgement mapAcknowledgement(ResultSet resultSet, int rowNumber)
			throws SQLException {
		return new Acknowledgement(
				resultSet.getLong("acknowledged_through_audit_event_id"),
				resultSet.getObject("acknowledged_at", OffsetDateTime.class));
	}

	/** 지정 확인 시점 이후 사고 여부를 다시 계산해 외부 상태로 변환합니다. */
	private BrokerUnknownIncidentStatusResponse createStatus(Acknowledgement acknowledgement) {
		return new BrokerUnknownIncidentStatusResponse(
				auditService.hasUnknownBrokerRequestAfter(acknowledgement.auditEventId()),
				acknowledgement.auditEventId(),
				acknowledgement.acknowledgedAt());
	}

	/** 데이터베이스에서 읽은 확인 기준 감사 사건과 시각을 묶습니다. */
	private record Acknowledgement(long auditEventId, OffsetDateTime acknowledgedAt) {
	}
}

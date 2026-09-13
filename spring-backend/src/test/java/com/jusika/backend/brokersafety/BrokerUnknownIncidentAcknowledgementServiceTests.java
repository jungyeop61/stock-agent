package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jusika.backend.brokeraudit.BrokerMutationAuditOutcome;
import com.jusika.backend.brokeraudit.BrokerMutationAuditService;
import com.jusika.backend.brokeraudit.BrokerMutationAuditStage;

/** V19 확인 이력과 결과 불명 자동 안전정지 해제 규칙을 검사합니다. */
@SpringBootTest
class BrokerUnknownIncidentAcknowledgementServiceTests {

	@Autowired
	private BrokerUnknownIncidentAcknowledgementService acknowledgementService;

	@Autowired
	private BrokerMutationAuditService auditService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 각 테스트가 독립된 결과 불명 사건과 확인 이력을 사용하도록 비웁니다. */
	@BeforeEach
	void 결과_불명_확인_자료를_비운다() {
		clearData();
	}

	/** 다음 통합 테스트에 확인 기준이 남지 않도록 자료를 정리합니다. */
	@AfterEach
	void 결과_불명_확인_자료를_정리한다() {
		clearData();
	}

	/** 결과 불명 사건이 없으면 자동 안전정지가 비활성 상태인지 검사합니다. */
	@Test
	@DisplayName("결과 불명 사건과 확인 이력이 없으면 정지하지 않는다")
	void 결과_불명_사건과_확인_이력이_없으면_정지하지_않는다() {
		BrokerUnknownIncidentStatusResponse status = acknowledgementService.getStatus();

		assertThat(status.haltActive()).isFalse();
		assertThat(status.acknowledgedThroughAuditEventId()).isNull();
		assertThat(status.acknowledgedAt()).isNull();
	}

	/** 사용자가 확인한 사건보다 더 최신 UNKNOWN이 있으면 정지를 유지하는지 검사합니다. */
	@Test
	@DisplayName("확인 시점보다 최신 결과 불명 사건이 있으면 정지를 유지한다")
	void 확인_시점보다_최신_결과_불명_사건이_있으면_정지를_유지한다() {
		long firstUnknownId = createUnknownEvent();
		long secondUnknownId = createUnknownEvent();

		BrokerUnknownIncidentStatusResponse first = acknowledgementService.acknowledge(
				new BrokerUnknownIncidentAcknowledgementRequest(firstUnknownId, true));
		BrokerUnknownIncidentStatusResponse second = acknowledgementService.acknowledge(
				new BrokerUnknownIncidentAcknowledgementRequest(secondUnknownId, true));

		assertThat(first.haltActive()).isTrue();
		assertThat(first.acknowledgedThroughAuditEventId()).isEqualTo(firstUnknownId);
		assertThat(second.haltActive()).isFalse();
		assertThat(second.acknowledgedThroughAuditEventId()).isEqualTo(secondUnknownId);
		assertThat(countAcknowledgements()).isEqualTo(2);
	}

	/** 같은 확인 요청을 반복해도 이력을 중복 생성하지 않는지 검사합니다. */
	@Test
	@DisplayName("같은 결과 불명 사건 확인은 멱등하게 처리한다")
	void 같은_결과_불명_사건_확인은_멱등하게_처리한다() {
		long unknownId = createUnknownEvent();

		BrokerUnknownIncidentStatusResponse first = acknowledgementService.acknowledge(
				new BrokerUnknownIncidentAcknowledgementRequest(unknownId, true));
		BrokerUnknownIncidentStatusResponse repeated = acknowledgementService.acknowledge(
				new BrokerUnknownIncidentAcknowledgementRequest(unknownId, true));

		assertThat(first).isEqualTo(repeated);
		assertThat(countAcknowledgements()).isEqualTo(1);
	}

	/** 명시적 확인이 없거나 UNKNOWN이 아닌 사건을 확인할 수 없는지 검사합니다. */
	@Test
	@DisplayName("명시하지 않았거나 결과 불명이 아닌 사건 확인을 거절한다")
	void 명시하지_않았거나_결과_불명이_아닌_사건_확인을_거절한다() {
		auditService.record(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.REJECTED);
		long rejectedId = latestAuditEventId();

		assertThatThrownBy(() -> acknowledgementService.acknowledge(
				new BrokerUnknownIncidentAcknowledgementRequest(rejectedId, false)))
				.isInstanceOf(BrokerUnknownIncidentAcknowledgementException.class)
				.hasMessage("결과 불명 주문 상태를 직접 확인했다는 명시가 필요합니다.");
		assertThatThrownBy(() -> acknowledgementService.acknowledge(
				new BrokerUnknownIncidentAcknowledgementRequest(rejectedId, true)))
				.isInstanceOf(BrokerUnknownIncidentAcknowledgementException.class)
				.hasMessage("확인 대상은 실제 토스 요청의 결과 불명 감사 사건이어야 합니다.");
		assertThat(countAcknowledgements()).isZero();
	}

	/** 민감정보 없는 테스트용 토스 요청 결과 불명 사건을 만들고 식별값을 반환합니다. */
	private long createUnknownEvent() {
		auditService.record(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.UNKNOWN);
		return latestAuditEventId();
	}

	/** 가장 최근 감사 사건의 내부 식별값만 테스트 판단에 사용합니다. */
	private long latestAuditEventId() {
		return auditService.getEvents(null, 1).events().getFirst().auditEventId();
	}

	/** 저장된 확인 이력 행 수를 반환합니다. */
	private long countAcknowledgements() {
		Long count = jdbcTemplate.queryForObject(
				"select count(*) from broker_unknown_incident_acknowledgements",
				Long.class);
		return count == null ? 0L : count;
	}

	/** 확인 이력을 먼저 지운 뒤 참조 대상 감사 사건을 비웁니다. */
	private void clearData() {
		jdbcTemplate.update("delete from broker_unknown_incident_acknowledgements");
		jdbcTemplate.update("delete from broker_mutation_audit_events");
	}
}

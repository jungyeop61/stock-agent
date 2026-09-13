package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jusika.backend.brokeraudit.BrokerMutationAuditOutcome;
import com.jusika.backend.brokeraudit.BrokerMutationAuditService;
import com.jusika.backend.brokeraudit.BrokerMutationAuditStage;

/** V16 감사 사건과 LIVE 자동 안전정지 정책·HTTP 상태의 실제 연결을 검사합니다. */
@SpringBootTest(properties = {
		"jusika.broker.mode=live",
		"jusika.broker.live-enabled=true",
		"jusika.broker.kill-switch-active=false",
		"jusika.broker.allowed-account-seqs=1",
		"jusika.broker.allowed-instruments=KR:005930",
		"jusika.broker.live-order-limits.max-quantity=10",
		"jusika.broker.live-order-limits.max-krw-order-amount=1000000",
		"jusika.broker.live-order-limits.max-usd-order-amount=1000",
		"jusika.broker.live-daily-order-limits.max-quantity=100",
		"jusika.broker.live-daily-order-limits.max-krw-order-amount=10000000",
		"jusika.broker.live-daily-order-limits.max-usd-order-amount=10000",
		"jusika.broker.live-open-order-limits.max-open-orders-per-account=10",
		"jusika.broker.live-open-order-limits.max-open-orders-per-instrument=5",
		"jusika.broker.live-order-rate-limits.max-mutations-per-account-per-minute=5",
		"jusika.broker.live-order-rate-limits.max-mutations-per-instrument-per-minute=3",
		"jusika.broker.live-adapters.quantity-order-submission=true",
		"jusika.broker.live-adapters.amount-order-submission=true",
		"jusika.broker.live-adapters.normal-order-cancellation=true",
		"jusika.broker.live-adapters.normal-order-modification=true",
		"jusika.broker.live-adapters.single-conditional-order-creation=true",
		"jusika.broker.live-adapters.oco-conditional-order-creation=true",
		"jusika.broker.live-adapters.oto-conditional-order-creation=true",
		"jusika.broker.live-adapters.conditional-order-cancellation=true",
		"jusika.broker.live-adapters.conditional-order-modification=true"
})
@AutoConfigureMockMvc
class BrokerUnknownIncidentHaltIntegrationTests {

	@Autowired
	private BrokerMutationAuditService auditService;

	@Autowired
	private BrokerMutationSafetyPolicy safetyPolicy;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	/** 각 검사 전에 다른 테스트가 만든 감사 사건을 제거합니다. */
	@BeforeEach
	void 감사_사건을_비운다() {
		jdbcTemplate.update("delete from broker_mutation_audit_events");
	}

	/** 통합 검사 뒤 결과 불명 상태가 다른 테스트 문맥으로 전파되지 않게 정리합니다. */
	@AfterEach
	void 감사_사건을_정리한다() {
		jdbcTemplate.update("delete from broker_mutation_audit_events");
	}

	/** 저장된 결과 불명 사건이 상태 API와 실제 안전 관문에 함께 반영되는지 검사합니다. */
	@Test
	@DisplayName("결과 불명 사건은 신규 위험을 정지하고 취소는 허용한다")
	void 결과_불명_사건은_신규_위험을_정지하고_취소는_허용한다() throws Exception {
		auditService.record(
				BrokerMutationCapability.CONDITIONAL_ORDER_MODIFICATION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.UNKNOWN);

		mockMvc.perform(get("/api/broker/safety"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.liveUnknownIncidentHaltActive").value(true))
				.andExpect(jsonPath("$.liveMutationAvailable").value(false))
				.andExpect(jsonPath("$.blockReason")
						.value("LIVE_UNKNOWN_INCIDENT_HALT_ACTIVE"));
		assertThatThrownBy(() -> safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
				.isInstanceOf(BrokerMutationBlockedException.class);
		assertThatCode(() -> safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.NORMAL_ORDER_CANCELLATION))
				.doesNotThrowAnyException();
	}
}

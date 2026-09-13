package com.jusika.backend.brokersafety;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jusika.backend.brokeraudit.BrokerMutationAuditOutcome;
import com.jusika.backend.brokeraudit.BrokerMutationAuditService;
import com.jusika.backend.brokeraudit.BrokerMutationAuditStage;
import com.jusika.backend.internalauth.InternalApiAuthorizationInterceptor;

/** 결과 불명 사고 조회·확인 API의 권한과 안전 응답을 검사합니다. */
@SpringBootTest(properties = {
		"jusika.internal-api.read-key=테스트-읽기-키",
		"jusika.internal-api.order-key=테스트-주문-키"
})
@AutoConfigureMockMvc
class BrokerUnknownIncidentControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BrokerMutationAuditService auditService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 각 HTTP 검사가 독립된 감사 사건과 확인 이력을 사용하도록 비웁니다. */
	@BeforeEach
	void 결과_불명_확인_자료를_비운다() {
		clearData();
	}

	/** 다음 통합 테스트에 자동 안전정지 상태가 남지 않도록 자료를 정리합니다. */
	@AfterEach
	void 결과_불명_확인_자료를_정리한다() {
		clearData();
	}

	/** 상태 조회에는 읽기 권한, 확인 처리에는 주문 권한이 필요한지 검사합니다. */
	@Test
	@DisplayName("결과 불명 사고 조회와 확인 권한을 분리한다")
	void 결과_불명_사고_조회와_확인_권한을_분리한다() throws Exception {
		long unknownId = createUnknownEvent();

		mockMvc.perform(get("/api/broker/unknown-incidents"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/broker/unknown-incidents")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "테스트-읽기-키"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.haltActive").value(true))
				.andExpect(jsonPath("$.acknowledgedThroughAuditEventId").doesNotExist());
		mockMvc.perform(post("/api/broker/unknown-incidents/acknowledgements")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "테스트-읽기-키")
					.contentType(MediaType.APPLICATION_JSON)
					.content(acknowledgementJson(unknownId, true)))
				.andExpect(status().isForbidden());
	}

	/** 주문 권한과 정확한 UNKNOWN 식별값으로만 정지를 해제하는지 검사합니다. */
	@Test
	@DisplayName("주문 권한으로 확인한 결과 불명 사건까지 안전정지를 해제한다")
	void 주문_권한으로_확인한_결과_불명_사건까지_안전정지를_해제한다() throws Exception {
		long unknownId = createUnknownEvent();

		mockMvc.perform(post("/api/broker/unknown-incidents/acknowledgements")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "테스트-주문-키")
					.contentType(MediaType.APPLICATION_JSON)
					.content(acknowledgementJson(unknownId, true)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.haltActive").value(false))
				.andExpect(jsonPath("$.acknowledgedThroughAuditEventId").value(unknownId))
				.andExpect(jsonPath("$.acknowledgedAt").isNotEmpty());
	}

	/** 확인하지 않았다고 보낸 요청은 이력을 만들지 않고 거절하는지 검사합니다. */
	@Test
	@DisplayName("명시적 확인이 없는 안전정지 해제 요청을 거절한다")
	void 명시적_확인이_없는_안전정지_해제_요청을_거절한다() throws Exception {
		long unknownId = createUnknownEvent();

		mockMvc.perform(post("/api/broker/unknown-incidents/acknowledgements")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "테스트-주문-키")
					.contentType(MediaType.APPLICATION_JSON)
					.content(acknowledgementJson(unknownId, false)))
				.andExpect(status().isBadRequest());
	}

	/** 토스 요청 결과 불명 감사 사건을 만들고 생성된 비식별 감사 ID를 반환합니다. */
	private long createUnknownEvent() {
		auditService.record(
				BrokerMutationCapability.CONDITIONAL_ORDER_MODIFICATION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.UNKNOWN);
		return auditService.getEvents(null, 1).events().getFirst().auditEventId();
	}

	/** 확인 API에 보낼 최소 JSON 본문을 만듭니다. */
	private String acknowledgementJson(long auditEventId, boolean confirmed) {
		return "{\"unknownAuditEventId\":" + auditEventId
				+ ",\"confirmed\":" + confirmed + "}";
	}

	/** 확인 이력을 먼저 지운 뒤 참조 대상 감사 사건을 비웁니다. */
	private void clearData() {
		jdbcTemplate.update("delete from broker_unknown_incident_acknowledgements");
		jdbcTemplate.update("delete from broker_mutation_audit_events");
	}
}

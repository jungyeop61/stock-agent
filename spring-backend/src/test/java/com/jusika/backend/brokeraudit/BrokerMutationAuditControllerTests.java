package com.jusika.backend.brokeraudit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.internalauth.InternalApiAuthorizationInterceptor;

/** LIVE 주문 변경 감사 조회 API의 권한과 응답 형식을 검사합니다. */
@SpringBootTest(properties = {
		"jusika.internal-api.read-key=테스트-읽기-키",
		"jusika.internal-api.order-key=테스트-주문-키"
})
@AutoConfigureMockMvc
class BrokerMutationAuditControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BrokerMutationAuditEventJpaRepository repository;

	@Autowired
	private BrokerMutationAuditService auditService;

	/** 각 HTTP 테스트가 독립된 감사 사건 목록을 사용하도록 기존 테스트 자료를 비웁니다. */
	@BeforeEach
	void 감사_사건을_비운다() {
		repository.deleteAll();
	}

	/** 다른 통합 테스트에 결과 불명 사고 상태가 남지 않도록 감사 사건을 정리합니다. */
	@AfterEach
	void 감사_사건을_정리한다() {
		repository.deleteAll();
	}

	/** 읽기 키가 없으면 감사 목록 내용을 반환하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 변경 감사 조회는 내부 읽기 권한을 요구한다")
	void LIVE_변경_감사_조회는_내부_읽기_권한을_요구한다() throws Exception {
		mockMvc.perform(get("/api/broker/mutation-audits"))
				.andExpect(status().isUnauthorized());
	}

	/** 읽기 권한으로 민감정보 없는 감사 사건과 커서 정보만 받는지 검사합니다. */
	@Test
	@DisplayName("LIVE 변경 감사 사건을 읽기 권한으로 조회한다")
	void LIVE_변경_감사_사건을_읽기_권한으로_조회한다() throws Exception {
		auditService.record(
				BrokerMutationCapability.CONDITIONAL_ORDER_MODIFICATION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.UNKNOWN);

		mockMvc.perform(get("/api/broker/mutation-audits")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "테스트-읽기-키")
					.param("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events.length()").value(1))
				.andExpect(jsonPath("$.events[0].capability")
						.value("CONDITIONAL_ORDER_MODIFICATION"))
				.andExpect(jsonPath("$.events[0].stage").value("BROKER_REQUEST"))
				.andExpect(jsonPath("$.events[0].outcome").value("UNKNOWN"))
				.andExpect(jsonPath("$.events[0].requestId").doesNotExist())
				.andExpect(jsonPath("$.nextBeforeEventId").doesNotExist())
				.andExpect(jsonPath("$.hasNext").value(false));
	}
}

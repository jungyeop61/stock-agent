package com.jusika.backend.brokersafety;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 기본 실행 설정의 민감정보 없는 증권사 안전 상태 조회 API를 검사합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrokerSafetyControllerTests {

	@Autowired
	private MockMvc mockMvc;

	/** 기본 설정이 실제 주문 불가 상태로 반환되는지 검사합니다. */
	@Test
	@DisplayName("기본 MOCK 안전 상태를 HTTP로 조회한다")
	void 기본_MOCK_안전_상태를_HTTP로_조회한다() throws Exception {
		mockMvc.perform(get("/api/broker/safety"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mode").value("MOCK"))
				.andExpect(jsonPath("$.liveEnabled").value(false))
				.andExpect(jsonPath("$.killSwitchActive").value(true))
				.andExpect(jsonPath("$.liveSafetyGateOpen").value(false))
				.andExpect(jsonPath("$.liveAdapterConnected").value(false))
				.andExpect(jsonPath("$.liveAccountAllowlistConfigured").value(false))
				.andExpect(jsonPath("$.liveOrderLimitsConfigured").value(false))
				.andExpect(jsonPath("$.liveMutationAvailable").value(false))
				.andExpect(jsonPath("$.blockReason").value("MOCK_MODE"))
				.andExpect(jsonPath("$.mutationCapabilities.length()").value(9))
				.andExpect(jsonPath("$.mutationCapabilities[0].capability")
						.value("QUANTITY_ORDER_SUBMISSION"))
				.andExpect(jsonPath("$.mutationCapabilities[0].liveAdapterConnected")
						.value(false))
				.andExpect(jsonPath("$.mutationCapabilities[8].capability")
						.value("CONDITIONAL_ORDER_MODIFICATION"))
				.andExpect(jsonPath("$.mutationCapabilities[8].liveAdapterConnected")
						.value(false));
	}
}

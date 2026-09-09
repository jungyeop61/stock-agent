package com.jusika.backend.internalauth;

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
 * 애플리케이션에 등록된 내부 API 권한 인터셉터의 기본 차단 범위를 검사합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalApiAuthorizationIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	/** 기본 빈 키 설정에서 계좌 API가 외부 조회 전에 차단되는지 검사합니다. */
	@Test
	@DisplayName("기본 설정은 민감한 계좌 조회 API를 503으로 차단한다")
	void 기본_설정은_민감한_계좌_조회_API를_503으로_차단한다() throws Exception {
		mockMvc.perform(get("/api/accounts"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message")
						.value("내부 API 인증이 안전하게 설정되지 않았습니다."));
	}

	/** 인증 대상이 아닌 안전 상태 조회 API는 기존처럼 공개되는지 검사합니다. */
	@Test
	@DisplayName("증권사 안전 상태 조회 API는 내부 키 없이 사용할 수 있다")
	void 증권사_안전_상태_조회_API는_내부_키_없이_사용할_수_있다() throws Exception {
		mockMvc.perform(get("/api/broker/safety"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.liveMutationAvailable").value(false));
	}
}

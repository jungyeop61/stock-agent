package com.jusika.backend.internalauth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실제 컨트롤러 호출 전에 내부 API 키 인터셉터가 상태 코드와 권한을 올바르게 적용하는지 검사합니다.
 */
class InternalApiAuthorizationInterceptorTests {

	/** 인증 표시가 없는 공개 API는 키 없이 통과하는지 검사합니다. */
	@Test
	@DisplayName("인증 표시가 없는 API는 키 없이 통과한다")
	void 인증_표시가_없는_API는_키_없이_통과한다() throws Exception {
		MockMvc mockMvc = MVC를_만든다("읽기키", "주문키");

		mockMvc.perform(get("/검사/공개"))
				.andExpect(status().isOk());
	}

	/** 서버 키가 비어 있으면 보호 경로가 서비스 불가로 안전 차단되는지 검사합니다. */
	@Test
	@DisplayName("서버 키가 없으면 보호 API를 503으로 차단한다")
	void 서버_키가_없으면_보호_API를_503으로_차단한다() throws Exception {
		MockMvc mockMvc = MVC를_만든다("", "");

		mockMvc.perform(post("/검사/보호/주문"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message")
						.value("내부 API 인증이 안전하게 설정되지 않았습니다."));
	}

	/** 요청 키가 빠졌거나 틀리면 같은 인증 필요 응답을 반환하는지 검사합니다. */
	@Test
	@DisplayName("누락되거나 잘못된 키는 401로 차단한다")
	void 누락되거나_잘못된_키는_401로_차단한다() throws Exception {
		MockMvc mockMvc = MVC를_만든다("읽기키", "주문키");

		mockMvc.perform(get("/검사/보호/읽기"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("유효한 내부 API 인증이 필요합니다."));
		mockMvc.perform(get("/검사/보호/읽기")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "잘못된키"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("유효한 내부 API 인증이 필요합니다."));
	}

	/** 읽기 키로 주문 경로를 호출하면 인증과 권한을 구분해 거절하는지 검사합니다. */
	@Test
	@DisplayName("읽기 키의 주문 요청은 403으로 차단한다")
	void 읽기_키의_주문_요청은_403으로_차단한다() throws Exception {
		MockMvc mockMvc = MVC를_만든다("읽기키", "주문키");

		mockMvc.perform(post("/검사/보호/주문")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "읽기키"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("이 요청을 실행할 주문 권한이 없습니다."));
	}

	/** 읽기 키는 메서드가 읽기로 낮춘 경로에 사용할 수 있는지 검사합니다. */
	@Test
	@DisplayName("읽기 키는 읽기 권한 경로를 통과한다")
	void 읽기_키는_읽기_권한_경로를_통과한다() throws Exception {
		MockMvc mockMvc = MVC를_만든다("읽기키", "주문키");

		mockMvc.perform(get("/검사/보호/읽기")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "읽기키"))
				.andExpect(status().isOk());
	}

	/** 주문 키는 주문 경로와 읽기 경로를 모두 통과하는지 검사합니다. */
	@Test
	@DisplayName("주문 키는 주문과 읽기 권한 경로를 모두 통과한다")
	void 주문_키는_주문과_읽기_권한_경로를_모두_통과한다() throws Exception {
		MockMvc mockMvc = MVC를_만든다("읽기키", "주문키");

		mockMvc.perform(post("/검사/보호/주문")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "주문키"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/검사/보호/읽기")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "주문키"))
				.andExpect(status().isOk());
	}

	/** 지정한 가짜 키로 인터셉터와 검사 전용 컨트롤러를 연결합니다. */
	private MockMvc MVC를_만든다(String readKey, String orderKey) {
		InternalApiKeyAuthenticationService authenticationService =
				new InternalApiKeyAuthenticationService(
						new InternalApiKeyProperties(readKey, orderKey));
		InternalApiAuthorizationInterceptor interceptor =
				new InternalApiAuthorizationInterceptor(authenticationService);
		return MockMvcBuilders
				.standaloneSetup(new 보호_검사용_컨트롤러(), new 공개_검사용_컨트롤러())
				.addInterceptors(interceptor)
				.build();
	}

	/** 클래스 기본 주문 권한과 메서드별 읽기 권한 재정의를 검사할 컨트롤러입니다. */
	@RestController
	@RequestMapping("/검사/보호")
	@RequiresInternalApiAuthority(InternalApiAuthority.ORDER)
	private static class 보호_검사용_컨트롤러 {

		/** 주문 권한을 충족한 검사 요청에 고정 응답을 반환합니다. */
		@PostMapping("/주문")
		String 주문() {
			return "주문";
		}

		/** 클래스 기본값을 읽기 권한으로 낮춘 검사 요청에 고정 응답을 반환합니다. */
		@GetMapping("/읽기")
		@RequiresInternalApiAuthority(InternalApiAuthority.READ)
		String 읽기() {
			return "읽기";
		}
	}

	/** 인증 표시가 없는 경로의 기존 동작을 검사할 공개 컨트롤러입니다. */
	@RestController
	@RequestMapping("/검사/공개")
	private static class 공개_검사용_컨트롤러 {

		/** 인증 표시 없이 검사 요청에 고정 응답을 반환합니다. */
		@GetMapping
		String 공개() {
			return "공개";
		}
	}
}

package com.jusika.backend.internalauth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

	/** 유효한 요청 UUID와 치환된 라우트 템플릿만 성공 감사 사건에 남는지 검사합니다. */
	@Test
	@DisplayName("허용된 요청은 실제 주문 식별값 없이 감사 사건을 남긴다")
	void 허용된_요청은_실제_주문_식별값_없이_감사_사건을_남긴다() throws Exception {
		기록용_감사_기록기 recorder = new 기록용_감사_기록기();
		MockMvc mockMvc = MVC를_만든다("읽기키", "주문키", recorder);
		String requestId = UUID.randomUUID().toString();

		mockMvc.perform(get("/검사/보호/실행/{executionId}", "민감-주문-식별값")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "읽기키")
					.header(InternalApiAuthorizationInterceptor.REQUEST_ID_HEADER, requestId))
				.andExpect(status().isOk())
				.andExpect(header().string(
						InternalApiAuthorizationInterceptor.REQUEST_ID_HEADER, requestId));

		InternalApiAuditEvent event = recorder.마지막_사건();
		Assertions.assertThat(event.requestId()).isEqualTo(requestId);
		Assertions.assertThat(event.routePattern()).isEqualTo("/검사/보호/실행/{executionId}");
		Assertions.assertThat(event.routePattern()).doesNotContain("민감-주문-식별값");
		Assertions.assertThat(event.requiredAuthority()).isEqualTo(InternalApiAuthority.READ);
		Assertions.assertThat(event.authenticationResult())
				.isEqualTo(InternalApiAuthenticationResult.ALLOWED);
		Assertions.assertThat(event.outcome()).isEqualTo(InternalApiAuditOutcome.REQUEST_COMPLETED);
		Assertions.assertThat(event.httpStatus()).isEqualTo(200);
	}

	/** UUID가 아닌 외부 요청 식별값을 감사 로그에 사용하지 않는지 검사합니다. */
	@Test
	@DisplayName("잘못된 요청 식별값은 서버 UUID로 교체한다")
	void 잘못된_요청_식별값은_서버_UUID로_교체한다() throws Exception {
		기록용_감사_기록기 recorder = new 기록용_감사_기록기();
		MockMvc mockMvc = MVC를_만든다("읽기키", "주문키", recorder);

		String responseRequestId = mockMvc.perform(post("/검사/보호/주문")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, "읽기키")
					.header(InternalApiAuthorizationInterceptor.REQUEST_ID_HEADER, "로그에-남기면-안됨"))
				.andExpect(status().isForbidden())
				.andReturn().getResponse().getHeader(
						InternalApiAuthorizationInterceptor.REQUEST_ID_HEADER);

		Assertions.assertThat(responseRequestId).isNotEqualTo("로그에-남기면-안됨");
		Assertions.assertThatCode(() -> UUID.fromString(responseRequestId))
				.doesNotThrowAnyException();
		InternalApiAuditEvent event = recorder.마지막_사건();
		Assertions.assertThat(event.requestId()).isEqualTo(responseRequestId);
		Assertions.assertThat(event.authenticationResult())
				.isEqualTo(InternalApiAuthenticationResult.AUTHORITY_INSUFFICIENT);
		Assertions.assertThat(event.outcome())
				.isEqualTo(InternalApiAuditOutcome.AUTHORIZATION_REJECTED);
		Assertions.assertThat(event.httpStatus()).isEqualTo(403);
	}

	/** 지정한 가짜 키로 인터셉터와 검사 전용 컨트롤러를 연결합니다. */
	private MockMvc MVC를_만든다(String readKey, String orderKey) {
		return MVC를_만든다(readKey, orderKey, new 기록용_감사_기록기());
	}

	/** 지정한 가짜 키와 기록기로 감사 사건까지 검사할 MVC 환경을 만듭니다. */
	private MockMvc MVC를_만든다(
			String readKey,
			String orderKey,
			InternalApiAuditRecorder auditRecorder) {
		InternalApiKeyAuthenticationService authenticationService =
				new InternalApiKeyAuthenticationService(
						new InternalApiKeyProperties(readKey, orderKey));
		InternalApiAuthorizationInterceptor interceptor =
				new InternalApiAuthorizationInterceptor(
						authenticationService,
						auditRecorder,
						Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC));
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

		/** 경로 식별값이 감사 사건에서 제거되는지 검사할 고정 응답을 반환합니다. */
		@GetMapping("/실행/{executionId}")
		@RequiresInternalApiAuthority(InternalApiAuthority.READ)
		String 실행_조회(@PathVariable String executionId) {
			return executionId;
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

	/** 테스트 중 받은 감사 사건을 메모리에만 보관하는 기록기입니다. */
	private static class 기록용_감사_기록기 implements InternalApiAuditRecorder {

		private final List<InternalApiAuditEvent> events = new ArrayList<>();

		/** 전달된 감사 사건을 외부 출력 없이 메모리 목록에 추가합니다. */
		@Override
		public void record(InternalApiAuditEvent event) {
			events.add(event);
		}

		/** 가장 최근에 기록된 감사 사건을 반환합니다. */
		InternalApiAuditEvent 마지막_사건() {
			return events.get(events.size() - 1);
		}
	}
}

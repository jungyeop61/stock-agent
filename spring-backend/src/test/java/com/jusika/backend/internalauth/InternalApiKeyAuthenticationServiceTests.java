package com.jusika.backend.internalauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 외부 요청이나 로그 없이 내부 API 키의 읽기·주문 권한 판정을 검사합니다.
 */
class InternalApiKeyAuthenticationServiceTests {

	/** 서버 주문 키가 없으면 제출값과 관계없이 주문 요청을 차단하는지 검사합니다. */
	@Test
	@DisplayName("주문 키가 설정되지 않으면 주문 요청을 기본 차단한다")
	void 주문_키가_설정되지_않으면_주문_요청을_기본_차단한다() {
		InternalApiKeyAuthenticationService service = 인증_서비스를_만든다("읽기키", "");

		assertThat(service.authenticate(InternalApiAuthority.ORDER, "제출키"))
				.isEqualTo(InternalApiAuthenticationResult.SECURITY_NOT_CONFIGURED);
	}

	/** 읽기 키와 주문 키를 같은 값으로 잘못 설정하면 주문 요청을 차단하는지 검사합니다. */
	@Test
	@DisplayName("읽기 키와 주문 키가 같으면 주문 요청을 기본 차단한다")
	void 읽기_키와_주문_키가_같으면_주문_요청을_기본_차단한다() {
		InternalApiKeyAuthenticationService service = 인증_서비스를_만든다("같은키", "같은키");

		assertThat(service.authenticate(InternalApiAuthority.ORDER, "같은키"))
				.isEqualTo(InternalApiAuthenticationResult.SECURITY_NOT_CONFIGURED);
	}

	/** 읽기 키와 주문 키가 모두 없으면 민감 조회도 차단하는지 검사합니다. */
	@Test
	@DisplayName("모든 키가 설정되지 않으면 읽기 요청을 기본 차단한다")
	void 모든_키가_설정되지_않으면_읽기_요청을_기본_차단한다() {
		InternalApiKeyAuthenticationService service = 인증_서비스를_만든다("", "");

		assertThat(service.authenticate(InternalApiAuthority.READ, null))
				.isEqualTo(InternalApiAuthenticationResult.SECURITY_NOT_CONFIGURED);
	}

	/** 주문 키가 주문 요청과 읽기 요청 모두에 사용 가능한지 검사합니다. */
	@Test
	@DisplayName("주문 키는 주문과 읽기 요청을 모두 허용한다")
	void 주문_키는_주문과_읽기_요청을_모두_허용한다() {
		InternalApiKeyAuthenticationService service = 인증_서비스를_만든다("읽기키", "주문키");

		assertThat(service.authenticate(InternalApiAuthority.ORDER, "주문키"))
				.isEqualTo(InternalApiAuthenticationResult.ALLOWED);
		assertThat(service.authenticate(InternalApiAuthority.READ, "주문키"))
				.isEqualTo(InternalApiAuthenticationResult.ALLOWED);
	}

	/** 읽기 키가 조회는 허용하지만 주문 요청은 거절하는지 검사합니다. */
	@Test
	@DisplayName("읽기 키는 주문 권한을 갖지 않는다")
	void 읽기_키는_주문_권한을_갖지_않는다() {
		InternalApiKeyAuthenticationService service = 인증_서비스를_만든다("읽기키", "주문키");

		assertThat(service.authenticate(InternalApiAuthority.READ, "읽기키"))
				.isEqualTo(InternalApiAuthenticationResult.ALLOWED);
		assertThat(service.authenticate(InternalApiAuthority.ORDER, "읽기키"))
				.isEqualTo(InternalApiAuthenticationResult.AUTHORITY_INSUFFICIENT);
	}

	/** 누락되거나 일치하지 않는 키를 서로 다른 안전 결과로 판별하는지 검사합니다. */
	@Test
	@DisplayName("누락 키와 잘못된 키를 인증 실패로 처리한다")
	void 누락_키와_잘못된_키를_인증_실패로_처리한다() {
		InternalApiKeyAuthenticationService service = 인증_서비스를_만든다("읽기키", "주문키");

		assertThat(service.authenticate(InternalApiAuthority.READ, null))
				.isEqualTo(InternalApiAuthenticationResult.CREDENTIALS_MISSING);
		assertThat(service.authenticate(InternalApiAuthority.READ, "잘못된키"))
				.isEqualTo(InternalApiAuthenticationResult.CREDENTIALS_INVALID);
	}

	/** 지정한 가짜 키 설정으로 네트워크를 사용하지 않는 인증 서비스를 만듭니다. */
	private InternalApiKeyAuthenticationService 인증_서비스를_만든다(
			String readKey,
			String orderKey) {
		return new InternalApiKeyAuthenticationService(
				new InternalApiKeyProperties(readKey, orderKey));
	}
}

package com.jusika.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 주식아 스프링 애플리케이션의 기본 설정이 정상인지 확인합니다.
 */
@SpringBootTest(properties = {
		"jusika.toss.client-id=테스트-클라이언트-아이디",
		"jusika.toss.client-secret=테스트-클라이언트-비밀키"
})
class JusikaBackendApplicationTests {

	/**
	 * 스프링이 모든 설정과 객체를 오류 없이 준비할 수 있는지 확인합니다.
	 */
	@Test
	@DisplayName("스프링 애플리케이션 설정을 정상적으로 불러온다")
	void 스프링_애플리케이션_설정을_정상적으로_불러온다() {
	}
}

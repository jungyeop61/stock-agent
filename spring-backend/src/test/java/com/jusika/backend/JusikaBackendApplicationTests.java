package com.jusika.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 주식아 스프링 애플리케이션의 기본 설정이 정상인지 확인합니다.
 */
@SpringBootTest(properties = {
		"jusika.toss.client-id=테스트-클라이언트-아이디",
		"jusika.toss.client-secret=테스트-클라이언트-비밀키"
})
class JusikaBackendApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * 스프링이 모든 설정과 객체를 오류 없이 준비할 수 있는지 확인합니다.
	 */
	@Test
	@DisplayName("스프링 애플리케이션 설정을 정상적으로 불러온다")
	void 스프링_애플리케이션_설정을_정상적으로_불러온다() {
	}

	/** 기본 실행 모드에서 아홉 주문 변경 기능이 모두 MOCK 경계로 조립되는지 확인합니다. */
	@Test
	@DisplayName("기본 실행 모드는 아홉 주문 변경 기능에 MOCK 경계를 선택한다")
	void 기본_실행_모드는_아홉_주문_변경_기능에_MOCK_경계를_선택한다() {
		assertThat(applicationContext.containsBean("mockOrderSubmissionGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockAmountOrderSubmissionGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockOrderCancellationGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockOrderModificationGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockSingleConditionalOrderGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockOcoConditionalOrderGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockOtoConditionalOrderGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockConditionalOrderCancellationGateway")).isTrue();
		assertThat(applicationContext.containsBean("mockConditionalOrderModificationGateway")).isTrue();

		assertThat(applicationContext.containsBean("liveOrderSubmissionGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveAmountOrderSubmissionGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveOrderCancellationGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveOrderModificationGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveSingleConditionalOrderGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveOcoConditionalOrderGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveOtoConditionalOrderGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveConditionalOrderCancellationGateway")).isFalse();
		assertThat(applicationContext.containsBean("liveConditionalOrderModificationGateway")).isFalse();
	}
}

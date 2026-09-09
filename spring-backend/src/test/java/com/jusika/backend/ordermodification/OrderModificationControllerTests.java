package com.jusika.backend.ordermodification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jusika.backend.internalauth.InternalApiAuthorizationInterceptor;

/** 실제 증권사를 호출하지 않고 정정 HTTP 주소와 입력 오류 상태를 검사합니다. */
@SpringBootTest(properties = {
		"jusika.internal-api.read-key=테스트-읽기-키",
		"jusika.internal-api.order-key=테스트-주문-키"
})
@AutoConfigureMockMvc
class OrderModificationControllerTests {
	private static final String TEST_READ_KEY = "테스트-읽기-키";
	private static final String TEST_ORDER_KEY = "테스트-주문-키";

	@Autowired
	private MockMvc mockMvc;

	/** 잘못된 계좌와 식별값을 각 정정 주소에서 HTTP 400으로 차단하는지 검사합니다. */
	@Test
	@DisplayName("정정 HTTP 주소에서 잘못된 입력을 외부 호출 전에 차단한다")
	void 정정_HTTP_주소에서_잘못된_입력을_외부_호출_전에_차단한다() throws Exception {
		mockMvc.perform(post("/api/orders/modifications/preview")
					.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, TEST_ORDER_KEY)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "accountSeq": 0,
							  "orderId": "test-order",
							  "orderType": "LIMIT",
							  "quantity": 1,
							  "price": 70000
							}
							"""))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post(
				"/api/orders/modifications/previews/{previewId}/approve", "invalid-id")
				.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, TEST_ORDER_KEY))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post(
				"/api/orders/modifications/previews/{previewId}/execute", "invalid-id")
				.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, TEST_ORDER_KEY))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(
				"/api/orders/modifications/executions/{executionId}", "invalid-id")
				.header(InternalApiAuthorizationInterceptor.API_KEY_HEADER, TEST_READ_KEY))
				.andExpect(status().isBadRequest());
	}
}

package com.jusika.backend.orderexecution;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jusika.backend.orderpreview.OrderPreviewResponse;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderPreviewStore;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 우리 데이터베이스의 주문 실행 기록 조회 HTTP 주소와 오류 상태를 검사합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderExecutionControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderPreviewStore previewStore;

	@Autowired
	private OrderExecutionStore executionStore;

	/**
	 * 저장된 주문 실행 기록을 외부 증권사 호출이나 상태 변경 없이 그대로 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("저장된 주문 실행 기록을 식별값으로 조회한다")
	void 저장된_주문_실행_기록을_식별값으로_조회한다() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(now);
		OrderExecutionResponse execution = new OrderExecutionResponse(
				UUID.randomUUID().toString(), preview.previewId(), UUID.randomUUID().toString(),
				"MOCK", OrderExecutionStatus.PREPARED, null, null, now, now, null, null);
		executionStore.claim(execution);

		mockMvc.perform(get("/api/orders/executions/{executionId}", execution.executionId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.executionId").value(execution.executionId()))
				.andExpect(jsonPath("$.previewId").value(preview.previewId()))
				.andExpect(jsonPath("$.brokerMode").value("MOCK"))
				.andExpect(jsonPath("$.status").value("PREPARED"))
				.andExpect(jsonPath("$.brokerOrderId").isEmpty());
	}

	/**
	 * 존재하지 않는 UUID와 잘못된 식별값을 각각 404와 400으로 구분하는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 실행 기록 조회에서 없음과 형식 오류를 구분한다")
	void 주문_실행_기록_조회에서_없음과_형식_오류를_구분한다() throws Exception {
		mockMvc.perform(get("/api/orders/executions/{executionId}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/orders/executions/{executionId}", "잘못된-식별값"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * 실행 기록의 외래키가 가리킬 승인된 국내 지정가 매수 미리보기를 저장합니다.
	 *
	 * @param now 미리보기 생성과 승인 시각
	 * @return 데이터베이스에 저장된 승인 미리보기
	 */
	private OrderPreviewResponse 승인된_미리보기를_저장한다(OffsetDateTime now) {
		return previewStore.save(new OrderPreviewResponse(
				UUID.randomUUID().toString(), now, now.plusMinutes(2), 1L, "005930",
				OrderSide.BUY, OrderType.LIMIT, BigDecimal.ONE, new BigDecimal("70000"),
				new BigDecimal("72000"), new BigDecimal("70000"), "KRW", "KR",
				new BigDecimal("0.00015"), new BigDecimal("70000"), new BigDecimal("10.5"),
				new BigDecimal("70010.5"), false, false, true,
				OrderPreviewStatus.APPROVED, now));
	}
}

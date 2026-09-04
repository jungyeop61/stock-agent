package com.jusika.backend.orderpreview;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * 주문 미리보기 승인 HTTP 주소의 응답 본문과 상태 코드를 검사합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPreviewControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderPreviewStore previewStore;

	/**
	 * 저장된 미리보기는 승인 API에서 한 번 성공하고 두 번째 요청은 충돌로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("승인 API는 저장된 주문 내용을 유지하고 중복 승인을 차단한다")
	void 승인_API는_저장된_주문_내용을_유지하고_중복_승인을_차단한다() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		OrderPreviewResponse pending = 승인_대기_미리보기를_만든다(now.minusSeconds(10), now.plusMinutes(1));
		previewStore.save(pending);

		mockMvc.perform(post("/api/orders/previews/{previewId}/approve", pending.previewId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.previewId").value(pending.previewId()))
				.andExpect(jsonPath("$.symbol").value("005930"))
				.andExpect(jsonPath("$.quantity").value(1))
				.andExpect(jsonPath("$.requestedPrice").value(70000))
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.approvedAt").isNotEmpty());

		mockMvc.perform(post("/api/orders/previews/{previewId}/approve", pending.previewId()))
				.andExpect(status().isConflict());
	}

	/**
	 * 만료되었거나 존재하지 않거나 형식이 잘못된 식별값에 각각 안전한 상태 코드를 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("승인 API는 만료와 식별값 오류를 구분한다")
	void 승인_API는_만료와_식별값_오류를_구분한다() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		OrderPreviewResponse expired = 승인_대기_미리보기를_만든다(
				now.minusMinutes(3), now.minusMinutes(1));
		previewStore.save(expired);

		mockMvc.perform(post("/api/orders/previews/{previewId}/approve", expired.previewId()))
				.andExpect(status().isGone());
		mockMvc.perform(post("/api/orders/previews/{previewId}/approve", UUID.randomUUID()))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/orders/previews/{previewId}/approve", "잘못된-식별값"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * 모의 실행 API가 승인되지 않은 미리보기와 잘못된 식별값을 주문 조회 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("모의 실행 API는 승인 상태와 식별값을 먼저 검사한다")
	void 모의_실행_API는_승인_상태와_식별값을_먼저_검사한다() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		OrderPreviewResponse pending = 승인_대기_미리보기를_만든다(
				now.minusSeconds(10), now.plusMinutes(1));
		previewStore.save(pending);

		mockMvc.perform(post("/api/orders/previews/{previewId}/execute", pending.previewId()))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/orders/previews/{previewId}/execute", "잘못된-식별값"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * 승인 API 검증에 사용할 주문 계산 결과와 승인 대기 상태를 만듭니다.
	 *
	 * @param createdAt 미리보기 생성 시각
	 * @param expiresAt 미리보기 승인 만료 시각
	 * @return 데이터베이스에 저장할 승인 대기 미리보기
	 */
	private OrderPreviewResponse 승인_대기_미리보기를_만든다(
			OffsetDateTime createdAt,
			OffsetDateTime expiresAt) {
		return new OrderPreviewResponse(
				UUID.randomUUID().toString(),
				createdAt,
				expiresAt,
				1L,
				"005930",
				OrderSide.BUY,
				OrderType.LIMIT,
				BigDecimal.ONE,
				new BigDecimal("70000"),
				new BigDecimal("72000"),
				new BigDecimal("70000"),
				"KRW",
				"KR",
				new BigDecimal("0.00015"),
				new BigDecimal("70000"),
				new BigDecimal("10.5"),
				new BigDecimal("70010.5"),
				false,
				false,
				true,
				OrderPreviewStatus.PENDING_APPROVAL,
				null);
	}
}

package com.jusika.backend.amountorderexecution;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.jusika.backend.amountorderpreview.AmountOrderPreviewResponse;
import com.jusika.backend.amountorderpreview.AmountOrderPreviewStore;
import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 금액 주문 MOCK 실행과 저장 결과 조회 HTTP 주소의 상태 코드를 검사합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AmountOrderExecutionControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AmountOrderPreviewStore previewStore;

	@Autowired
	private AmountOrderExecutionStore executionStore;

	@Autowired
	private AmountOrderRequestFingerprint requestFingerprint;

	/** 저장된 금액 주문 실행 기록을 외부 호출 없이 그대로 반환하는지 검사합니다. */
	@Test
	@DisplayName("저장된 금액 주문 실행 기록을 식별값으로 조회한다")
	void 저장된_금액_주문_실행_기록을_식별값으로_조회한다() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		AmountOrderPreviewResponse preview = 미리보기를_저장한다(now, OrderPreviewStatus.APPROVED);
		AmountOrderExecutionResponse execution = new AmountOrderExecutionResponse(
				UUID.randomUUID().toString(), preview.previewId(), UUID.randomUUID().toString(),
				"MOCK", OrderExecutionStatus.PREPARED, null, null, now, now, null, null, null);
		executionStore.claim(execution, "a".repeat(64));

		mockMvc.perform(get(
				"/api/orders/amount/executions/{executionId}", execution.executionId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.executionId").value(execution.executionId()))
				.andExpect(jsonPath("$.previewId").value(preview.previewId()))
				.andExpect(jsonPath("$.brokerMode").value("MOCK"))
				.andExpect(jsonPath("$.status").value("PREPARED"))
				.andExpect(jsonPath("$.brokerOrderId").isEmpty());
	}

	/** 승인되지 않은 미리보기 실행과 잘못된 조회 식별값을 HTTP 오류로 구분하는지 검사합니다. */
	@Test
	@DisplayName("금액 주문 실행 API가 상태 충돌과 식별값 오류를 구분한다")
	void 금액_주문_실행_API가_상태_충돌과_식별값_오류를_구분한다() throws Exception {
		AmountOrderPreviewResponse pending = 미리보기를_저장한다(
				OffsetDateTime.now(), OrderPreviewStatus.PENDING_APPROVAL);

		mockMvc.perform(post(
				"/api/orders/amount/previews/{previewId}/execute", pending.previewId()))
				.andExpect(status().isConflict());
		mockMvc.perform(get(
				"/api/orders/amount/executions/{executionId}", "잘못된-식별값"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(
				"/api/orders/amount/executions/{executionId}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	/** 결과 불명 금액 주문을 HTTP 주소에서 같은 요청으로 한 번 복구하는지 검사합니다. */
	@Test
	@DisplayName("결과 불명 금액 주문을 복구 API로 접수 상태로 만든다")
	void 결과_불명_금액_주문을_복구_API로_접수_상태로_만든다() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		AmountOrderPreviewResponse savedPreview = 미리보기를_저장한다(
				now.minusMinutes(1), OrderPreviewStatus.CONSUMED);
		AmountOrderPreviewResponse preview = previewStore
				.findById(savedPreview.previewId()).orElseThrow();
		AmountOrderExecutionResponse execution = new AmountOrderExecutionResponse(
				UUID.randomUUID().toString(), preview.previewId(), UUID.randomUUID().toString(),
				"MOCK", OrderExecutionStatus.PREPARED, null, null,
				now.minusSeconds(3), now.minusSeconds(3), null, null, null);
		AmountOrderSubmissionRequest request = new AmountOrderSubmissionRequest(
				execution.clientOrderId(), preview.symbol(), preview.side(), preview.orderAmount(),
				preview.requiresHighValueConfirmation());
		executionStore.claim(
				execution, requestFingerprint.calculate(preview.accountSeq(), request));
		executionStore.markSubmitting(execution.executionId(), now.minusSeconds(2));
		executionStore.markUnknown(execution.executionId(), now.minusSeconds(1));

		mockMvc.perform(post(
				"/api/orders/amount/executions/{executionId}/recover", execution.executionId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.executionId").value(execution.executionId()))
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.failureType").isEmpty())
				.andExpect(jsonPath("$.brokerOrderId").value(
						"mock-amount-" + execution.clientOrderId()))
				.andExpect(jsonPath("$.recoveryAttemptedAt").isNotEmpty());
	}

	/** HTTP 테스트가 참조할 일관된 금액 주문 미리보기를 저장합니다. */
	private AmountOrderPreviewResponse 미리보기를_저장한다(
			OffsetDateTime now,
			OrderPreviewStatus status) {
		return previewStore.save(new AmountOrderPreviewResponse(
				UUID.randomUUID().toString(), now.minusMinutes(1), now.plusMinutes(1), 1L,
				"AAPL", OrderSide.BUY, OrderType.MARKET, new BigDecimal("100"), "USD", "US",
				new BigDecimal("200"), new BigDecimal("0.5"), new BigDecimal("0.001"),
				new BigDecimal("0.1"), new BigDecimal("100.1"), new BigDecimal("1400"),
				now.minusMinutes(2), now.plusMinutes(2), new BigDecimal("140000"),
				false, true, status, status == OrderPreviewStatus.APPROVED ? now : null));
	}
}

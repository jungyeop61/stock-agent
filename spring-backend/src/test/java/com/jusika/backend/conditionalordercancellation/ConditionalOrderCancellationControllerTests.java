package com.jusika.backend.conditionalordercancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewResponse.ConditionSnapshot;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderType;

/** 조건 주문 취소 HTTP 주소가 요청과 식별값을 서비스에 올바르게 전달하는지 검사합니다. */
class ConditionalOrderCancellationControllerTests {

	private RecordingService service;
	private MockMvc mockMvc;

	/** 각 테스트에서 실제 토스증권 호출 없이 조건 주문 취소 컨트롤러만 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_조건_주문_취소_컨트롤러를_준비한다() {
		service = new RecordingService();
		mockMvc = MockMvcBuilders.standaloneSetup(
				new ConditionalOrderCancellationController(service)).build();
	}

	/** 취소 대상 JSON을 요청 자료형으로 변환해 서비스에 전달하는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 취소 미리보기 HTTP 요청을 처리한다")
	void 조건_주문_취소_미리보기_HTTP_요청을_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();

		mockMvc.perform(post("/api/conditional-orders/cancellations/preview")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "accountSeq": 1,
								  "conditionalOrderId": "conditional-id"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conditionalOrderType").value("OCO"))
				.andExpect(jsonPath("$.originalStatus").value("WATCHING"));

		assertThat(service.previewCallCount).isEqualTo(1);
		assertThat(service.request.accountSeq()).isEqualTo(1L);
		assertThat(service.request.conditionalOrderId()).isEqualTo("conditional-id");
	}

	/** 승인·실행·결과 조회 경로가 각각 식별값을 전달하는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 취소 승인과 실행 경로를 처리한다")
	void 조건_주문_취소_승인과_실행_경로를_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();
		service.executionResponse = 실행_응답을_만든다();

		mockMvc.perform(post(
				"/api/conditional-orders/cancellations/previews/{previewId}/approve",
				"preview-id"))
				.andExpect(status().isOk());
		mockMvc.perform(post(
				"/api/conditional-orders/cancellations/previews/{previewId}/execute",
				"preview-id"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.brokerMode").value("MOCK"));
		mockMvc.perform(get(
				"/api/conditional-orders/cancellations/executions/{executionId}",
				"execution-id"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		assertThat(service.approvedId).isEqualTo("preview-id");
		assertThat(service.executedId).isEqualTo("preview-id");
		assertThat(service.queriedExecutionId).isEqualTo("execution-id");
	}

	/** 컨트롤러 테스트에 사용할 조건 주문 취소 미리보기 응답을 만듭니다. */
	private ConditionalOrderCancellationPreviewResponse 미리보기_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(
				2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		ConditionSnapshot first = new ConditionSnapshot(
				ConditionalOrderConditionType.STOP,
				ConditionalOrderConditionStatus.WATCHING,
				new BigDecimal("210"), null, new BigDecimal("209"), null);
		ConditionSnapshot second = new ConditionSnapshot(
				ConditionalOrderConditionType.STOP,
				ConditionalOrderConditionStatus.WATCHING,
				new BigDecimal("190"), null, new BigDecimal("189"), null);
		return new ConditionalOrderCancellationPreviewResponse(
				"preview-id", now, now.plusMinutes(2), 1L, "conditional-id",
				ConditionalOrderType.OCO, ConditionalOrderStatus.WATCHING, "AAPL",
				ConditionalOrderMarket.US, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"), first, second, now.minusMinutes(5),
				ConditionalOrderCancellationPreviewStatus.PENDING_APPROVAL, null);
	}

	/** 컨트롤러 테스트에 사용할 접수 완료 조건 주문 취소 실행 응답을 만듭니다. */
	private ConditionalOrderCancellationExecutionResponse 실행_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(
				2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		return new ConditionalOrderCancellationExecutionResponse(
				"execution-id", "preview-id", 1L, "conditional-id", "MOCK",
				OrderExecutionStatus.ACCEPTED, null, now, now, now, now);
	}

	/** 실제 조회나 취소 없이 컨트롤러 호출 인수만 기록하는 테스트 전용 서비스입니다. */
	private static final class RecordingService
			extends ConditionalOrderCancellationService {
		private ConditionalOrderCancellationPreviewResponse previewResponse;
		private ConditionalOrderCancellationExecutionResponse executionResponse;
		private ConditionalOrderCancellationPreviewRequest request;
		private int previewCallCount;
		private String approvedId;
		private String executedId;
		private String queriedExecutionId;

		/** 실제 의존 객체 없이 기록용 부모 서비스를 초기화합니다. */
		private RecordingService() {
			super(null, null, null, null, null, null);
		}

		/** 취소 미리보기 요청을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public ConditionalOrderCancellationPreviewResponse createPreview(
				ConditionalOrderCancellationPreviewRequest request) {
			previewCallCount++;
			this.request = request;
			return previewResponse;
		}

		/** 승인할 취소 미리보기 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public ConditionalOrderCancellationPreviewResponse approvePreview(String previewId) {
			approvedId = previewId;
			return previewResponse;
		}

		/** 실행할 취소 미리보기 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public ConditionalOrderCancellationExecutionResponse executeApprovedPreview(
				String previewId) {
			executedId = previewId;
			return executionResponse;
		}

		/** 조회할 조건 주문 취소 실행 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public ConditionalOrderCancellationExecutionResponse getExecution(String executionId) {
			queriedExecutionId = executionId;
			return executionResponse;
		}
	}
}

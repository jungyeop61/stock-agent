package com.jusika.backend.conditionalordercreation;

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

import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 단일 조건 주문 HTTP 주소가 요청값과 식별값을 서비스에 올바르게 전달하는지 검사합니다.
 */
class SingleConditionalOrderControllerTests {

	private RecordingService service;
	private MockMvc mockMvc;

	/** 각 테스트에서 실제 토스증권 호출 없이 컨트롤러만 검사할 환경을 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_단일_조건_주문_컨트롤러를_준비한다() {
		service = new RecordingService();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new SingleConditionalOrderController(service))
				.build();
	}

	/** 미리보기 JSON을 자료형으로 변환해 서비스에 전달하고 응답을 반환하는지 검사합니다. */
	@Test
	@DisplayName("단일 조건 주문 미리보기 HTTP 요청을 처리한다")
	void 단일_조건_주문_미리보기_HTTP_요청을_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();

		mockMvc.perform(post("/api/conditional-orders/single/preview")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "accountSeq": 1,
								  "symbol": "005930",
								  "side": "BUY",
								  "orderType": "LIMIT",
								  "quantity": 10,
								  "triggerPrice": 72000,
								  "orderPrice": 71000,
								  "expireDate": "2026-09-10"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conditionalOrderType").value("SINGLE"))
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

		assertThat(service.previewCallCount).isEqualTo(1);
		assertThat(service.request.accountSeq()).isEqualTo(1L);
		assertThat(service.request.side()).isEqualTo(OrderSide.BUY);
		assertThat(service.request.triggerPrice()).isEqualByComparingTo("72000");
	}

	/** 승인·실행·실행 결과 조회 경로가 각각 식별값을 전달하는지 검사합니다. */
	@Test
	@DisplayName("단일 조건 주문 승인과 실행 경로를 처리한다")
	void 단일_조건_주문_승인과_실행_경로를_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();
		service.executionResponse = 실행_응답을_만든다();

		mockMvc.perform(post(
				"/api/conditional-orders/single/previews/{previewId}/approve", "preview-id"))
				.andExpect(status().isOk());
		mockMvc.perform(post(
				"/api/conditional-orders/single/previews/{previewId}/execute", "preview-id"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.brokerMode").value("MOCK"));
		mockMvc.perform(get(
				"/api/conditional-orders/single/executions/{executionId}", "execution-id"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		assertThat(service.approvedId).isEqualTo("preview-id");
		assertThat(service.executedId).isEqualTo("preview-id");
		assertThat(service.queriedExecutionId).isEqualTo("execution-id");
	}

	/** 컨트롤러 테스트에 사용할 단일 조건 주문 미리보기 응답을 만듭니다. */
	private SingleConditionalOrderPreviewResponse 미리보기_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(
				2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		return new SingleConditionalOrderPreviewResponse(
				"preview-id", now, now.plusMinutes(2), 1L, "005930",
				ConditionalOrderType.SINGLE, OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.TEN, new BigDecimal("72000"), new BigDecimal("71000"),
				LocalDate.parse("2026-09-10"), new BigDecimal("70000"),
				new BigDecimal("71000"), "KRW", "KR", new BigDecimal("0.001"),
				new BigDecimal("710000"), new BigDecimal("710"),
				new BigDecimal("710710"), false, false,
				OrderPreviewStatus.PENDING_APPROVAL, null);
	}

	/** 컨트롤러 테스트에 사용할 접수 완료 실행 응답을 만듭니다. */
	private SingleConditionalOrderExecutionResponse 실행_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(
				2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		return new SingleConditionalOrderExecutionResponse(
				"execution-id", "preview-id", "client-id", "conditional-id", "MOCK",
				OrderExecutionStatus.ACCEPTED, null, now, now, now, now);
	}

	/** 실제 조회나 주문 없이 컨트롤러 호출 인수만 기록하는 테스트 전용 서비스입니다. */
	private static final class RecordingService extends SingleConditionalOrderService {

		private SingleConditionalOrderPreviewResponse previewResponse;
		private SingleConditionalOrderExecutionResponse executionResponse;
		private SingleConditionalOrderPreviewRequest request;
		private int previewCallCount;
		private String approvedId;
		private String executedId;
		private String queriedExecutionId;

		/** 실제 의존 객체 없이 기록용 부모 서비스를 초기화합니다. */
		private RecordingService() {
			super(null, null, null, null, null, null, null, null, null);
		}

		/** 미리보기 요청을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public SingleConditionalOrderPreviewResponse createPreview(
				SingleConditionalOrderPreviewRequest request) {
			previewCallCount++;
			this.request = request;
			return previewResponse;
		}

		/** 승인할 미리보기 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public SingleConditionalOrderPreviewResponse approvePreview(String previewId) {
			approvedId = previewId;
			return previewResponse;
		}

		/** 실행할 미리보기 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public SingleConditionalOrderExecutionResponse executeApprovedPreview(String previewId) {
			executedId = previewId;
			return executionResponse;
		}

		/** 조회할 실행 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public SingleConditionalOrderExecutionResponse getExecution(String executionId) {
			queriedExecutionId = executionId;
			return executionResponse;
		}
	}
}

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

/** OTO HTTP 주소가 두 조건과 식별값을 서비스에 올바르게 전달하는지 검사합니다. */
class OtoConditionalOrderControllerTests {

	private RecordingService service;
	private MockMvc mockMvc;

	/** 각 테스트에서 실제 토스증권 호출 없이 OTO 컨트롤러만 검사할 환경을 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_OTO_컨트롤러를_준비한다() {
		service = new RecordingService();
		mockMvc = MockMvcBuilders.standaloneSetup(new OtoConditionalOrderController(service)).build();
	}

	/** OTO 미리보기 JSON의 매수·매도 조건을 자료형으로 변환해 서비스에 전달합니다. */
	@Test
	@DisplayName("OTO 미리보기 HTTP 요청을 처리한다")
	void OTO_미리보기_HTTP_요청을_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();

		mockMvc.perform(post("/api/conditional-orders/oto/preview")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "accountSeq": 1,
								  "symbol": "005930",
								  "quantity": 10,
								  "orderType": "LIMIT",
								  "expireDate": "2026-09-10",
								  "first": {"side":"BUY","triggerPrice":68000,"orderPrice":69000},
								  "second": {"side":"SELL","triggerPrice":79000,"orderPrice":80000}
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conditionalOrderType").value("OTO"))
				.andExpect(jsonPath("$.first.side").value("BUY"))
				.andExpect(jsonPath("$.second.side").value("SELL"));

		assertThat(service.previewCallCount).isEqualTo(1);
		assertThat(service.request.first().triggerPrice()).isEqualByComparingTo("68000");
		assertThat(service.request.second().orderPrice()).isEqualByComparingTo("80000");
	}

	/** OTO 승인·실행·실행 결과 조회 경로가 각각 식별값을 전달하는지 검사합니다. */
	@Test
	@DisplayName("OTO 승인과 실행 경로를 처리한다")
	void OTO_승인과_실행_경로를_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();
		service.executionResponse = 실행_응답을_만든다();

		mockMvc.perform(post(
				"/api/conditional-orders/oto/previews/{previewId}/approve", "preview-id"))
				.andExpect(status().isOk());
		mockMvc.perform(post(
				"/api/conditional-orders/oto/previews/{previewId}/execute", "preview-id"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.brokerMode").value("MOCK"));
		mockMvc.perform(get(
				"/api/conditional-orders/oto/executions/{executionId}", "execution-id"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		assertThat(service.approvedId).isEqualTo("preview-id");
		assertThat(service.executedId).isEqualTo("preview-id");
		assertThat(service.queriedExecutionId).isEqualTo("execution-id");
	}

	/** 컨트롤러 테스트에 사용할 OTO 미리보기 응답을 만듭니다. */
	private OtoConditionalOrderPreviewResponse 미리보기_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(
				2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		return new OtoConditionalOrderPreviewResponse(
				"preview-id", now, now.plusMinutes(2), 1L, "005930",
				ConditionalOrderType.OTO, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"), new BigDecimal("70000"), "KRW", "KR",
				new BigDecimal("0.001"),
				new OtoConditionalOrderPreviewResponse.Condition(
						OrderSide.BUY, new BigDecimal("68000"), new BigDecimal("69000"),
						new BigDecimal("690000"), new BigDecimal("690"),
						new BigDecimal("690690")),
				new OtoConditionalOrderPreviewResponse.Condition(
						OrderSide.SELL, new BigDecimal("79000"), new BigDecimal("80000"),
						new BigDecimal("800000"), new BigDecimal("800"),
						new BigDecimal("799200")),
				true, true, false, OrderPreviewStatus.PENDING_APPROVAL, null);
	}

	/** 컨트롤러 테스트에 사용할 접수 완료 OTO 실행 응답을 만듭니다. */
	private OtoConditionalOrderExecutionResponse 실행_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(
				2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		return new OtoConditionalOrderExecutionResponse(
				"execution-id", "preview-id", "client-id", "conditional-id", "MOCK",
				OrderExecutionStatus.ACCEPTED, null, now, now, now, now);
	}

	/** 실제 조회나 주문 없이 OTO 컨트롤러 호출 인수만 기록하는 테스트 전용 서비스입니다. */
	private static final class RecordingService extends OtoConditionalOrderService {
		private OtoConditionalOrderPreviewResponse previewResponse;
		private OtoConditionalOrderExecutionResponse executionResponse;
		private OtoConditionalOrderPreviewRequest request;
		private int previewCallCount;
		private String approvedId;
		private String executedId;
		private String queriedExecutionId;

		/** 실제 의존 객체 없이 기록용 부모 서비스를 초기화합니다. */
		private RecordingService() {
			super(null, null, null, null, null, null, null, null);
		}

		/** OTO 미리보기 요청을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public OtoConditionalOrderPreviewResponse createPreview(
				OtoConditionalOrderPreviewRequest request) {
			previewCallCount++;
			this.request = request;
			return previewResponse;
		}

		/** 승인할 OTO 미리보기 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public OtoConditionalOrderPreviewResponse approvePreview(String previewId) {
			approvedId = previewId;
			return previewResponse;
		}

		/** 실행할 OTO 미리보기 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public OtoConditionalOrderExecutionResponse executeApprovedPreview(String previewId) {
			executedId = previewId;
			return executionResponse;
		}

		/** 조회할 OTO 실행 식별값을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public OtoConditionalOrderExecutionResponse getExecution(String executionId) {
			queriedExecutionId = executionId;
			return executionResponse;
		}
	}
}

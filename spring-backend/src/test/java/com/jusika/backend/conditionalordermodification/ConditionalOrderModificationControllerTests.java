package com.jusika.backend.conditionalordermodification;

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
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.OriginalCondition;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.RequestedCondition;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/** 실제 주문 없이 조건 주문 정정 HTTP 주소와 JSON 변환을 검사합니다. */
class ConditionalOrderModificationControllerTests {

	private RecordingService service;
	private MockMvc mockMvc;

	/** 각 테스트에서 실제 의존 객체 없이 조건 주문 정정 컨트롤러를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_조건_주문_정정_컨트롤러를_준비한다() {
		service = new RecordingService();
		mockMvc = MockMvcBuilders.standaloneSetup(
				new ConditionalOrderModificationController(service)).build();
	}

	/** SINGLE 원주문을 OCO 전체 구성으로 전환하는 JSON을 서비스에 전달하는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 유형 전환 정정 미리보기 요청을 처리한다")
	void 조건_주문_유형_전환_정정_미리보기_요청을_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();

		mockMvc.perform(post("/api/conditional-orders/modifications/preview")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "accountSeq": 1,
								  "conditionalOrderId": "original-id",
								  "type": "OCO",
								  "quantity": 10,
								  "orderType": "LIMIT",
								  "expireDate": "2026-09-10",
								  "first": {"side":"SELL","triggerPrice":210,"orderPrice":209},
								  "second": {"side":"SELL","triggerPrice":190,"orderPrice":189}
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.originalType").value("SINGLE"))
				.andExpect(jsonPath("$.requestedType").value("OCO"));

		assertThat(service.request.type()).isEqualTo(ConditionalOrderType.OCO);
		assertThat(service.request.second().side()).isEqualTo(OrderSide.SELL);
	}

	/** 승인·실행·저장 결과 조회 경로가 각각 식별값을 전달하는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 정정 승인과 실행 및 결과 조회 경로를 처리한다")
	void 조건_주문_정정_승인과_실행_및_결과_조회_경로를_처리한다() throws Exception {
		service.previewResponse = 미리보기_응답을_만든다();
		service.executionResponse = 실행_응답을_만든다();

		mockMvc.perform(post("/api/conditional-orders/modifications/previews/{id}/approve", "preview-id"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/conditional-orders/modifications/previews/{id}/execute", "preview-id"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.brokerMode").value("MOCK"));
		mockMvc.perform(get("/api/conditional-orders/modifications/executions/{id}", "execution-id"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

		assertThat(service.approvedId).isEqualTo("preview-id");
		assertThat(service.executedId).isEqualTo("preview-id");
		assertThat(service.queriedId).isEqualTo("execution-id");
	}

	/** 컨트롤러 응답에 사용할 유형 전환 미리보기를 만듭니다. */
	private ConditionalOrderModificationPreviewResponse 미리보기_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		OriginalCondition original = new OriginalCondition(
				ConditionalOrderConditionType.STOP, ConditionalOrderConditionStatus.WATCHING,
				new BigDecimal("200"), null, new BigDecimal("199"), null);
		RequestedCondition first = new RequestedCondition(
				OrderSide.SELL, new BigDecimal("210"), new BigDecimal("209"));
		RequestedCondition second = new RequestedCondition(
				OrderSide.SELL, new BigDecimal("190"), new BigDecimal("189"));
		return new ConditionalOrderModificationPreviewResponse(
				"preview-id", now, now.plusMinutes(2), 1L, "original-id",
				ConditionalOrderType.SINGLE, ConditionalOrderStatus.WATCHING, "AAPL",
				ConditionalOrderMarket.US, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.of(2026, 9, 9), original, null, now.minusMinutes(5),
				ConditionalOrderType.OCO, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.of(2026, 9, 10), first, second, new BigDecimal("200"), "USD",
				false, ConditionalOrderModificationPreviewStatus.PENDING_APPROVAL, null);
	}

	/** 컨트롤러 응답에 사용할 접수 완료 정정 실행 결과를 만듭니다. */
	private ConditionalOrderModificationExecutionResponse 실행_응답을_만든다() {
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);
		return new ConditionalOrderModificationExecutionResponse(
				"execution-id", "preview-id", 1L, "original-id", "replacement-id",
				"MOCK", OrderExecutionStatus.ACCEPTED, null, now, now, now, now);
	}

	/** 실제 조회나 정정 없이 컨트롤러가 전달한 호출 인수만 기록합니다. */
	private static final class RecordingService extends ConditionalOrderModificationService {
		private ConditionalOrderModificationPreviewResponse previewResponse;
		private ConditionalOrderModificationExecutionResponse executionResponse;
		private ConditionalOrderModificationPreviewRequest request;
		private String approvedId;
		private String executedId;
		private String queriedId;

		/** 부모 서비스의 실제 의존 객체 없이 기록용 서비스를 초기화합니다. */
		private RecordingService() {
			super(null, null, null, null, null, null, null, null, null, null);
		}

		/** 전달된 정정 미리보기 요청을 기록합니다. */
		@Override
		public ConditionalOrderModificationPreviewResponse createPreview(
				ConditionalOrderModificationPreviewRequest request) {
			this.request = request;
			return previewResponse;
		}

		/** 전달된 승인 미리보기 식별값을 기록합니다. */
		@Override
		public ConditionalOrderModificationPreviewResponse approvePreview(String previewId) {
			approvedId = previewId;
			return previewResponse;
		}

		/** 전달된 실행 미리보기 식별값을 기록합니다. */
		@Override
		public ConditionalOrderModificationExecutionResponse executeApprovedPreview(String previewId) {
			executedId = previewId;
			return executionResponse;
		}

		/** 전달된 정정 실행 식별값을 기록합니다. */
		@Override
		public ConditionalOrderModificationExecutionResponse getExecution(String executionId) {
			queriedId = executionId;
			return executionResponse;
		}
	}
}

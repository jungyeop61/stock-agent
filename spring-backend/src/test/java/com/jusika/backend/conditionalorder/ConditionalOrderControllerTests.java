package com.jusika.backend.conditionalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/**
 * 조건 주문 HTTP 주소가 요청값을 올바른 자료형으로 변환해 읽기 전용 클라이언트에 전달하는지 검사합니다.
 */
class ConditionalOrderControllerTests {

	private RecordingConditionalOrderClient recordingClient;
	private MockMvc mockMvc;

	/**
	 * 각 테스트에서 실제 토스증권 호출 없이 컨트롤러만 검사할 환경을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_조건_주문_컨트롤러를_준비한다() {
		recordingClient = new RecordingConditionalOrderClient();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new ConditionalOrderController(recordingClient))
				.build();
	}

	/**
	 * 상태·종목·페이지 필터를 변환해 목록 클라이언트에 전달하고 JSON 응답을 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("조건 주문 목록 HTTP 요청의 필터를 변환해 조회한다")
	void 조건_주문_목록_HTTP_요청의_필터를_변환해_조회한다() throws Exception {
		recordingClient.listResponse = new ConditionalOrderListResponse(
				1L, ConditionalOrderListStatus.OPEN, "AAPL", List.of(), null, false);

		mockMvc.perform(get("/api/accounts/{accountSeq}/conditional-orders", 1L)
						.param("status", "OPEN")
						.param("symbol", "aapl")
						.param("cursor", "next_cursor")
						.param("limit", "30"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountSeq").value(1))
				.andExpect(jsonPath("$.listStatus").value("OPEN"))
				.andExpect(jsonPath("$.conditionalOrders").isEmpty())
				.andExpect(jsonPath("$.hasNext").value(false));

		assertThat(recordingClient.listCallCount).isEqualTo(1);
		assertThat(recordingClient.accountSeq).isEqualTo(1L);
		assertThat(recordingClient.status).isEqualTo(ConditionalOrderListStatus.OPEN);
		assertThat(recordingClient.symbol).isEqualTo("aapl");
		assertThat(recordingClient.cursor).isEqualTo("next_cursor");
		assertThat(recordingClient.limit).isEqualTo(30);
	}

	/**
	 * 상세 식별값을 클라이언트에 전달하고 잘못된 상태는 호출 전 HTTP 400으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("조건 주문 상세 경로와 잘못된 목록 상태를 처리한다")
	void 조건_주문_상세_경로와_잘못된_목록_상태를_처리한다() throws Exception {
		recordingClient.detailResponse = null;

		mockMvc.perform(get(
				"/api/accounts/{accountSeq}/conditional-orders/{conditionalOrderId}",
				1L,
				"conditional_test_001"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/accounts/{accountSeq}/conditional-orders", 1L)
						.param("status", "UNKNOWN"))
				.andExpect(status().isBadRequest());

		assertThat(recordingClient.detailCallCount).isEqualTo(1);
		assertThat(recordingClient.conditionalOrderId).isEqualTo("conditional_test_001");
		assertThat(recordingClient.listCallCount).isZero();
	}

	/**
	 * 실제 외부 통신 없이 컨트롤러가 전달한 조회 인수를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class RecordingConditionalOrderClient extends TossConditionalOrderClient {

		private ConditionalOrderListResponse listResponse;
		private ConditionalOrderDetailResponse detailResponse;
		private int listCallCount;
		private int detailCallCount;
		private long accountSeq;
		private ConditionalOrderListStatus status;
		private String symbol;
		private String cursor;
		private Integer limit;
		private String conditionalOrderId;

		/**
		 * 실제 REST 클라이언트와 토큰 공급자 없이 기록용 부모 객체를 초기화합니다.
		 */
		private RecordingConditionalOrderClient() {
			super(null, null);
		}

		/**
		 * 컨트롤러가 전달한 목록 조회 인수를 기록하고 준비된 응답을 반환합니다.
		 *
		 * @param accountSeq 계좌 식별값
		 * @param status 조건 주문 목록 그룹
		 * @param symbol 선택 종목 코드
		 * @param cursor 선택 페이지 커서
		 * @param limit 선택 페이지 크기
		 * @return 테스트가 미리 준비한 조건 주문 목록 응답
		 */
		@Override
		public ConditionalOrderListResponse getConditionalOrders(
				long accountSeq,
				ConditionalOrderListStatus status,
				String symbol,
				String cursor,
				Integer limit) {
			listCallCount++;
			this.accountSeq = accountSeq;
			this.status = status;
			this.symbol = symbol;
			this.cursor = cursor;
			this.limit = limit;
			return listResponse;
		}

		/**
		 * 컨트롤러가 전달한 상세 조회 인수를 기록하고 준비된 응답을 반환합니다.
		 *
		 * @param accountSeq 계좌 식별값
		 * @param conditionalOrderId 조건 주문 식별값
		 * @return 테스트가 미리 준비한 조건 주문 상세 응답
		 */
		@Override
		public ConditionalOrderDetailResponse getConditionalOrder(
				long accountSeq,
				String conditionalOrderId) {
			detailCallCount++;
			this.accountSeq = accountSeq;
			this.conditionalOrderId = conditionalOrderId;
			return detailResponse;
		}
	}
}

package com.jusika.backend.orderhistory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/**
 * 주문 목록 HTTP 주소가 요청값을 올바른 자료형으로 변환해 읽기 전용 클라이언트에 전달하는지 검사합니다.
 */
class OrderHistoryControllerTests {

	private TossOrderHistoryClient historyClient;
	private RecordingOrderHistoryClient recordingHistoryClient;
	private MockMvc mockMvc;

	/**
	 * 각 테스트에서 실제 토스증권 호출 없이 컨트롤러만 검사할 환경을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_주문_목록_컨트롤러를_준비한다() {
		recordingHistoryClient = new RecordingOrderHistoryClient();
		historyClient = recordingHistoryClient;
		mockMvc = MockMvcBuilders
				.standaloneSetup(new OrderHistoryController(historyClient))
				.build();
	}

	/**
	 * 상태·종목·날짜 필터를 변환해 목록 클라이언트에 전달하고 JSON 응답을 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 목록 HTTP 요청의 필터를 변환해 조회한다")
	void 주문_목록_HTTP_요청의_필터를_변환해_조회한다() throws Exception {
		LocalDate from = LocalDate.parse("2026-03-01");
		LocalDate to = LocalDate.parse("2026-03-31");
		OrderListResponse response = new OrderListResponse(
				1L, OrderListStatus.OPEN, "AAPL", from, to, List.of(), null, false);
		recordingHistoryClient.response = response;

		mockMvc.perform(get("/api/accounts/{accountSeq}/orders", 1L)
						.param("status", "OPEN")
						.param("symbol", "aapl")
						.param("from", "2026-03-01")
						.param("to", "2026-03-31"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountSeq").value(1))
				.andExpect(jsonPath("$.listStatus").value("OPEN"))
				.andExpect(jsonPath("$.symbol").value("AAPL"))
				.andExpect(jsonPath("$.orders").isEmpty())
				.andExpect(jsonPath("$.nextCursor").isEmpty())
				.andExpect(jsonPath("$.hasNext").value(false));

		assertThat(recordingHistoryClient.callCount).isEqualTo(1);
		assertThat(recordingHistoryClient.accountSeq).isEqualTo(1L);
		assertThat(recordingHistoryClient.status).isEqualTo(OrderListStatus.OPEN);
		assertThat(recordingHistoryClient.symbol).isEqualTo("aapl");
		assertThat(recordingHistoryClient.from).isEqualTo(from);
		assertThat(recordingHistoryClient.to).isEqualTo(to);
		assertThat(recordingHistoryClient.cursor).isNull();
		assertThat(recordingHistoryClient.limit).isNull();
	}

	/**
	 * 지원하지 않는 상태와 잘못된 날짜는 목록 클라이언트를 호출하기 전에 HTTP 400으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 주문 목록 상태와 날짜를 HTTP 400으로 거절한다")
	void 잘못된_주문_목록_상태와_날짜를_HTTP_400으로_거절한다() throws Exception {
		mockMvc.perform(get("/api/accounts/{accountSeq}/orders", 1L)
						.param("status", "UNKNOWN"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/accounts/{accountSeq}/orders", 1L)
						.param("status", "OPEN")
						.param("from", "2026-13-01"))
				.andExpect(status().isBadRequest());

		assertThat(recordingHistoryClient.callCount).isZero();
	}

	/**
	 * 실제 외부 통신 없이 컨트롤러가 전달한 주문 목록 인수를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class RecordingOrderHistoryClient extends TossOrderHistoryClient {

		private OrderListResponse response;
		private int callCount;
		private long accountSeq;
		private OrderListStatus status;
		private String symbol;
		private LocalDate from;
		private LocalDate to;
		private String cursor;
		private Integer limit;

		/**
		 * 실제 REST 클라이언트와 토큰 공급자 없이 기록용 부모 객체를 초기화합니다.
		 */
		private RecordingOrderHistoryClient() {
			super(null, null);
		}

		/**
		 * 컨트롤러가 전달한 목록 조회 인수를 기록하고 준비된 응답을 반환합니다.
		 *
		 * @param accountSeq 계좌 식별값
		 * @param status 주문 목록 그룹
		 * @param symbol 선택 종목 코드
		 * @param from 선택 조회 시작일
		 * @param to 선택 조회 종료일
		 * @param cursor 선택 페이지 커서
		 * @param limit 선택 페이지 크기
		 * @return 테스트가 미리 준비한 주문 목록 응답
		 */
		@Override
		public OrderListResponse getOrders(
				long accountSeq,
				OrderListStatus status,
				String symbol,
				LocalDate from,
				LocalDate to,
				String cursor,
				Integer limit) {
			callCount++;
			this.accountSeq = accountSeq;
			this.status = status;
			this.symbol = symbol;
			this.from = from;
			this.to = to;
			this.cursor = cursor;
			this.limit = limit;
			return response;
		}
	}
}

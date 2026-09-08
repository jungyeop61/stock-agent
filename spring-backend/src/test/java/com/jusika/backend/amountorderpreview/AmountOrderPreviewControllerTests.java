package com.jusika.backend.amountorderpreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 금액 주문 미리보기 HTTP 주소가 요청과 식별값을 서비스에 정확히 전달하는지 검사합니다.
 */
class AmountOrderPreviewControllerTests {

	private RecordingAmountOrderPreviewService recordingService;
	private MockMvc mockMvc;

	/**
	 * 각 테스트에서 실제 토스증권 조회 없이 컨트롤러만 검사할 환경을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_금액_주문_미리보기_컨트롤러를_준비한다() {
		recordingService = new RecordingAmountOrderPreviewService();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new AmountOrderPreviewController(recordingService))
				.build();
	}

	/**
	 * JSON 요청을 금액 주문 미리보기 객체로 변환하고 계산 결과를 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("달러 금액 주문 미리보기 HTTP 요청을 생성한다")
	void 달러_금액_주문_미리보기_HTTP_요청을_생성한다() throws Exception {
		recordingService.response = 금액_주문_미리보기를_만든다();

		mockMvc.perform(post("/api/orders/amount/preview")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "accountSeq": 1,
								  "symbol": "AAPL",
								  "orderAmount": 100
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.symbol").value("AAPL"))
				.andExpect(jsonPath("$.side").value("BUY"))
				.andExpect(jsonPath("$.orderType").value("MARKET"))
				.andExpect(jsonPath("$.orderAmount").value(100))
				.andExpect(jsonPath("$.estimatedQuantity").value(0.5))
				.andExpect(jsonPath("$.estimatedTotalCost").value(100.1))
				.andExpect(jsonPath("$.estimatedOrderAmountKrw").value(140000))
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

		assertThat(recordingService.createCallCount).isEqualTo(1);
		assertThat(recordingService.request.accountSeq()).isEqualTo(1L);
		assertThat(recordingService.request.symbol()).isEqualTo("AAPL");
		assertThat(recordingService.request.orderAmount()).isEqualByComparingTo("100");
	}

	/**
	 * URL의 식별값을 서비스에 전달해 저장된 미리보기를 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("저장된 달러 금액 주문 미리보기를 HTTP로 조회한다")
	void 저장된_달러_금액_주문_미리보기를_HTTP로_조회한다() throws Exception {
		recordingService.response = 금액_주문_미리보기를_만든다();

		mockMvc.perform(get(
						"/api/orders/amount/previews/{previewId}",
						recordingService.response.previewId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.previewId").value(recordingService.response.previewId()))
				.andExpect(jsonPath("$.currency").value("USD"))
				.andExpect(jsonPath("$.marketCountry").value("US"));

		assertThat(recordingService.getCallCount).isEqualTo(1);
		assertThat(recordingService.previewId).isEqualTo(recordingService.response.previewId());
	}

	/**
	 * 컨트롤러 응답에 사용할 정상 달러 금액 주문 미리보기를 만듭니다.
	 *
	 * @return HTTP JSON 변환에 사용할 미리보기
	 */
	private AmountOrderPreviewResponse 금액_주문_미리보기를_만든다() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-08T09:30:30+09:00");
		return new AmountOrderPreviewResponse(
				"bc7422c8-7b63-4f14-9ea3-99497ff2c454",
				createdAt,
				createdAt.plusMinutes(2),
				1L,
				"AAPL",
				OrderSide.BUY,
				OrderType.MARKET,
				new BigDecimal("100"),
				"USD",
				"US",
				new BigDecimal("200"),
				new BigDecimal("0.5"),
				new BigDecimal("0.001"),
				new BigDecimal("0.1"),
				new BigDecimal("100.1"),
				new BigDecimal("1400"),
				createdAt.minusSeconds(30),
				createdAt.plusSeconds(30),
				new BigDecimal("140000"),
				false,
				true,
				OrderPreviewStatus.PENDING_APPROVAL);
	}

	/**
	 * 실제 조회와 저장 없이 컨트롤러가 전달한 인수를 기록하는 테스트 전용 서비스입니다.
	 */
	private static final class RecordingAmountOrderPreviewService extends AmountOrderPreviewService {

		private AmountOrderPreviewResponse response;
		private AmountOrderPreviewRequest request;
		private String previewId;
		private int createCallCount;
		private int getCallCount;

		/** 실제 의존성 없이 기록용 부모 객체를 초기화합니다. */
		private RecordingAmountOrderPreviewService() {
			super(null, null, null, null, null, null, null);
		}

		/** 생성 요청을 기록하고 준비된 미리보기를 반환합니다. */
		@Override
		public AmountOrderPreviewResponse createPreview(AmountOrderPreviewRequest request) {
			createCallCount++;
			this.request = request;
			return response;
		}

		/** 조회 식별값을 기록하고 준비된 미리보기를 반환합니다. */
		@Override
		public AmountOrderPreviewResponse getPreview(String previewId) {
			getCallCount++;
			this.previewId = previewId;
			return response;
		}
	}
}

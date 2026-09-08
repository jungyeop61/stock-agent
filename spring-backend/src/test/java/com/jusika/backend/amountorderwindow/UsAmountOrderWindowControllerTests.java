package com.jusika.backend.amountorderwindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 미국 금액 주문 접수 시간 HTTP 주소가 현재 판정 결과를 그대로 반환하는지 검사합니다.
 */
class UsAmountOrderWindowControllerTests {

	private RecordingUsAmountOrderWindowService recordingService;
	private MockMvc mockMvc;

	/**
	 * 각 테스트에서 실제 토스증권 조회 없이 컨트롤러만 검사할 환경을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_미국_금액_주문_시간_컨트롤러를_준비한다() {
		recordingService = new RecordingUsAmountOrderWindowService();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new UsAmountOrderWindowController(recordingService))
				.build();
	}

	/**
	 * 현재 접수 가능 여부와 정규장·마감 시각을 JSON으로 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("현재 미국 금액 주문 접수 가능 여부를 HTTP로 조회한다")
	void 현재_미국_금액_주문_접수_가능_여부를_HTTP로_조회한다() throws Exception {
		recordingService.response = new UsAmountOrderWindowResponse(
				LocalDate.parse("2026-03-25"),
				OffsetDateTime.parse("2026-03-26T03:00:00+09:00"),
				true,
				OffsetDateTime.parse("2026-03-25T22:30:00+09:00"),
				OffsetDateTime.parse("2026-03-26T05:00:00+09:00"),
				OffsetDateTime.parse("2026-03-26T04:00:00+09:00"),
				true,
				UsAmountOrderWindowStatus.OPEN);

		mockMvc.perform(get("/api/market/us/amount-order-window"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.marketDate").value("2026-03-25"))
				.andExpect(jsonPath("$.orderable").value(true))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.orderAcceptanceEndAt")
						.value("2026-03-26T04:00:00+09:00"));

		assertThat(recordingService.callCount).isEqualTo(1);
	}

	/**
	 * 실제 시간 판정 없이 컨트롤러 호출 횟수를 기록하는 테스트 전용 서비스입니다.
	 */
	private static final class RecordingUsAmountOrderWindowService
			extends UsAmountOrderWindowService {

		private UsAmountOrderWindowResponse response;
		private int callCount;

		/** 실제 캘린더와 시계 없이 기록용 부모 객체를 초기화합니다. */
		private RecordingUsAmountOrderWindowService() {
			super(null, null);
		}

		/** 현재 시간 판정 호출을 기록하고 준비된 응답을 반환합니다. */
		@Override
		public UsAmountOrderWindowResponse checkCurrentWindow() {
			callCount++;
			return response;
		}
	}
}

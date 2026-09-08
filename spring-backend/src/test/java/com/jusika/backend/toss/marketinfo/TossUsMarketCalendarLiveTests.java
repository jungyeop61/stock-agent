package com.jusika.backend.toss.marketinfo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.marketcalendar.UsMarketCalendarResponse;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 미국 장 운영 일정을 읽습니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossUsMarketCalendarLiveTests {

	@Autowired
	private TossUsMarketCalendarClient marketCalendarClient;

	/**
	 * 실제 액세스 토큰으로 현재 기준 미국 장 운영 일정의 구조만 검사합니다.
	 * 인증정보나 구체적인 시장 시각은 테스트 결과에 출력하지 않습니다.
	 */
	@Test
	@DisplayName("실제 토스증권에서 미국 장 운영 일정을 안전하게 조회한다")
	void 실제_토스증권에서_미국_장_운영_일정을_안전하게_조회한다() {
		UsMarketCalendarResponse response = marketCalendarClient.getMarketCalendar(null);

		assertThat(response.today()).isNotNull();
		assertThat(response.previousBusinessDay().date()).isBefore(response.today().date());
		assertThat(response.nextBusinessDay().date()).isAfter(response.today().date());
	}
}

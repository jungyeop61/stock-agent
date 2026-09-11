package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 외부 주문 없이 데이터베이스의 LIVE 일일 누적 위험 예약을 검사합니다. */
@SpringBootTest(properties = {
		"jusika.broker.live-daily-order-limits.max-quantity=10",
		"jusika.broker.live-daily-order-limits.max-krw-order-amount=1000",
		"jusika.broker.live-daily-order-limits.max-usd-order-amount=100"
})
class BrokerLiveDailyOrderRiskServiceTests {

	@Autowired
	private BrokerLiveDailyOrderRiskService service;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 각 테스트가 독립적인 일일 누적 예약 상태에서 시작하도록 기존 합성 자료를 지웁니다. */
	@BeforeEach
	void 일일_누적_예약을_초기화한다() {
		jdbcTemplate.update("DELETE FROM broker_live_daily_order_risk_reservations");
	}

	/** 다른 테스트 컨텍스트에 합성 예약 자료가 남지 않도록 검사가 끝난 뒤 정리합니다. */
	@AfterEach
	void 일일_누적_예약을_정리한다() {
		jdbcTemplate.update("DELETE FROM broker_live_daily_order_risk_reservations");
	}

	/** 같은 주문의 제출과 복구가 일일 누적값을 두 번 더하지 않는지 검사합니다. */
	@Test
	@DisplayName("같은 LIVE 주문 위험 예약은 하루에 한 번만 누적한다")
	void 같은_LIVE_주문_위험_예약은_하루에_한_번만_누적한다() {
		BrokerOrderRiskSnapshot risk = new BrokerOrderRiskSnapshot(
				new BigDecimal("3"), new BigDecimal("400"), "KRW");

		service.reserve(1L, "테스트-동일-주문", risk);
		assertThatCode(() -> service.reserve(1L, "테스트-동일-주문", risk))
				.doesNotThrowAnyException();

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM broker_live_daily_order_risk_reservations",
				Integer.class);
		assertThat(count).isOne();
	}

	/** 일일 상한을 대부분 사용한 UNKNOWN 주문도 같은 예약 식별값으로 안전 복구할 수 있는지 검사합니다. */
	@Test
	@DisplayName("기존 LIVE 일일 예약을 인식한 안전 복구 사전 검사는 추가 누적하지 않는다")
	void 기존_LIVE_일일_예약을_인식한_안전_복구_사전_검사는_추가_누적하지_않는다() {
		String reservationKey = "테스트-결과불명-복구";
		BrokerOrderRiskSnapshot risk = new BrokerOrderRiskSnapshot(
				new BigDecimal("8"), new BigDecimal("900"), "KRW");
		service.reserve(1L, reservationKey, risk);

		assertThatCode(() -> service.requireCanReserve(1L, reservationKey, risk))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> service.requireCanReserve(1L, risk))
				.isInstanceOf(BrokerMutationBlockedException.class);
		assertThat(예약_개수를_조회한다()).isOne();
	}

	/** 계좌의 원화 누적 주문금액이 상한을 넘으면 새 예약을 저장하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 일일 누적 원화 주문금액 초과를 차단한다")
	void LIVE_일일_누적_원화_주문금액_초과를_차단한다() {
		service.reserve(1L, "테스트-원화-첫번째", new BrokerOrderRiskSnapshot(
				new BigDecimal("2"), new BigDecimal("800"), "KRW"));

		assertThatThrownBy(() -> service.reserve(
				1L,
				"테스트-원화-두번째",
				new BrokerOrderRiskSnapshot(BigDecimal.ONE, new BigDecimal("201"), "KRW")))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문의 일일 누적 금액 안전 한도를 초과합니다.")
				.hasMessageNotContaining("201");
		assertThat(예약_개수를_조회한다()).isOne();
	}

	/** 원화와 달러 주문 수량도 계좌별 하루 전체에서 합산되는지 검사합니다. */
	@Test
	@DisplayName("LIVE 일일 누적 수량은 통화를 합쳐서 차단한다")
	void LIVE_일일_누적_수량은_통화를_합쳐서_차단한다() {
		service.reserve(1L, "테스트-수량-원화", new BrokerOrderRiskSnapshot(
				new BigDecimal("6"), new BigDecimal("500"), "KRW"));

		assertThatThrownBy(() -> service.reserve(
				1L,
				"테스트-수량-달러",
				new BrokerOrderRiskSnapshot(new BigDecimal("5"), new BigDecimal("50"), "USD")))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문의 일일 누적 수량 안전 한도를 초과합니다.");
		assertThat(예약_개수를_조회한다()).isOne();
	}

	/** 같은 해시 예약을 다른 위험값으로 재사용하면 누적값을 바꾸지 않는지 검사합니다. */
	@Test
	@DisplayName("같은 LIVE 예약 식별값의 다른 위험값을 거절한다")
	void 같은_LIVE_예약_식별값의_다른_위험값을_거절한다() {
		service.reserve(1L, "테스트-충돌-주문", new BrokerOrderRiskSnapshot(
				BigDecimal.ONE, new BigDecimal("100"), "KRW"));

		assertThatThrownBy(() -> service.reserve(
				1L,
				"테스트-충돌-주문",
				new BrokerOrderRiskSnapshot(BigDecimal.ONE, new BigDecimal("101"), "KRW")))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("같은 LIVE 일일 한도 예약 식별값의 주문 위험값이 일치하지 않습니다.");
		assertThat(예약_개수를_조회한다()).isOne();
	}

	/** 예약 식별값 원문 대신 고정 길이 SHA-256 해시만 저장하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 일일 예약 식별값은 해시로만 저장한다")
	void LIVE_일일_예약_식별값은_해시로만_저장한다() {
		String reservationKey = "테스트-원문-미저장";
		service.reserve(1L, reservationKey, new BrokerOrderRiskSnapshot(
				BigDecimal.ONE, new BigDecimal("100"), "KRW"));

		String storedHash = jdbcTemplate.queryForObject(
				"SELECT reservation_key_hash FROM broker_live_daily_order_risk_reservations",
				String.class);

		assertThat(storedHash).matches("[0-9a-f]{64}");
		assertThat(storedHash).isNotEqualTo(reservationKey);
	}

	/** 동시에 들어온 두 예약도 잠금 행 아래에서 직렬화되어 한도 안의 한 건만 저장하는지 검사합니다. */
	@Test
	@DisplayName("동시 LIVE 주문 예약은 일일 누적 한도를 원자적으로 적용한다")
	void 동시_LIVE_주문_예약은_일일_누적_한도를_원자적으로_적용한다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		BrokerOrderRiskSnapshot risk = new BrokerOrderRiskSnapshot(
				new BigDecimal("6"), new BigDecimal("600"), "KRW");

		try {
			Future<Boolean> first = executor.submit(
					() -> 예약_성공_여부를_반환한다(start, "테스트-동시-첫번째", risk));
			Future<Boolean> second = executor.submit(
					() -> 예약_성공_여부를_반환한다(start, "테스트-동시-두번째", risk));
			start.countDown();

			assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
			assertThat(예약_개수를_조회한다()).isOne();
		} finally {
			executor.shutdownNow();
		}
	}

	/** 시작 신호 뒤 예약을 시도하고 일일 누적 한도 차단 여부를 성공값으로 변환합니다. */
	private boolean 예약_성공_여부를_반환한다(
			CountDownLatch start,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) throws InterruptedException {
		start.await();
		try {
			service.reserve(1L, reservationKey, riskSnapshot);
			return true;
		} catch (BrokerMutationBlockedException exception) {
			return false;
		}
	}

	/** 현재 테스트 트랜잭션에 저장된 일일 위험 예약 행 수를 반환합니다. */
	private int 예약_개수를_조회한다() {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM broker_live_daily_order_risk_reservations",
				Integer.class);
		return count == null ? 0 : count;
	}
}

package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** 실제 외부 주문 없이 V17의 LIVE 주문 빈도 예약과 동시성 제어를 검사합니다. */
@SpringBootTest(properties = {
		"jusika.broker.live-order-rate-limits.max-mutations-per-account-per-minute=3",
		"jusika.broker.live-order-rate-limits.max-mutations-per-instrument-per-minute=2"
})
class BrokerLiveOrderRateLimitServiceTests {

	@Autowired
	private BrokerLiveOrderRateLimitService service;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 각 테스트가 빈 1분 예약 상태에서 시작하도록 합성 자료를 지웁니다. */
	@BeforeEach
	void 주문_빈도_예약을_초기화한다() {
		jdbcTemplate.update("DELETE FROM broker_live_order_rate_limit_reservations");
	}

	/** 다른 테스트에 합성 예약이 남지 않도록 검사가 끝난 뒤 정리합니다. */
	@AfterEach
	void 주문_빈도_예약을_정리한다() {
		jdbcTemplate.update("DELETE FROM broker_live_order_rate_limit_reservations");
	}

	/** 같은 논리 주문의 제출과 복구가 1분 빈도에 한 번만 더해지는지 검사합니다. */
	@Test
	@DisplayName("같은 LIVE 주문 빈도 예약은 한 번만 집계한다")
	void 같은_LIVE_주문_빈도_예약은_한_번만_집계한다() {
		service.reserve(1L, "AAPL", "테스트-동일-주문");

		assertThatCode(() -> service.reserve(1L, "aapl", "테스트-동일-주문"))
				.doesNotThrowAnyException();
		assertThat(예약_개수를_조회한다()).isOne();
	}

	/** 같은 예약이 있으면 종목 상한에 도달한 뒤의 안전 복구 사전 검사도 통과하는지 검사합니다. */
	@Test
	@DisplayName("기존 LIVE 빈도 예약을 인식한 안전 복구는 추가 집계하지 않는다")
	void 기존_LIVE_빈도_예약을_인식한_안전_복구는_추가_집계하지_않는다() {
		service.reserve(1L, "AAPL", "테스트-복구-첫번째");
		service.reserve(1L, "AAPL", "테스트-복구-두번째");

		assertThatCode(() -> service.requireCanReserve(
				1L, "AAPL", "테스트-복구-첫번째"))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> service.requireCanReserve(1L, "AAPL"))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문의 종목별 1분 빈도 한도를 초과합니다.");
	}

	/** 서로 다른 종목을 합친 계좌 전체 1분 상한 초과를 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 계좌별 1분 주문 빈도 초과를 차단한다")
	void LIVE_계좌별_1분_주문_빈도_초과를_차단한다() {
		service.reserve(1L, "AAPL", "테스트-계좌-첫번째");
		service.reserve(1L, "MSFT", "테스트-계좌-두번째");
		service.reserve(1L, "GOOG", "테스트-계좌-세번째");

		assertThatThrownBy(() -> service.reserve(1L, "NVDA", "테스트-계좌-네번째"))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문의 계좌별 1분 빈도 한도를 초과합니다.");
		assertThat(예약_개수를_조회한다()).isEqualTo(3);
	}

	/** 같은 계좌의 동일 종목 1분 상한 초과를 계좌 상한보다 먼저 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 종목별 1분 주문 빈도 초과를 차단한다")
	void LIVE_종목별_1분_주문_빈도_초과를_차단한다() {
		service.reserve(1L, "AAPL", "테스트-종목-첫번째");
		service.reserve(1L, "AAPL", "테스트-종목-두번째");

		assertThatThrownBy(() -> service.reserve(1L, "AAPL", "테스트-종목-세번째"))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문의 종목별 1분 빈도 한도를 초과합니다.");
		assertThat(예약_개수를_조회한다()).isEqualTo(2);
	}

	/** 예약 식별값과 종목 원문 대신 SHA-256 해시만 저장하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 주문 빈도 예약은 식별값과 종목을 해시로만 저장한다")
	void LIVE_주문_빈도_예약은_식별값과_종목을_해시로만_저장한다() {
		service.reserve(1L, "AAPL", "테스트-원문-미저장");

		String reservationHash = jdbcTemplate.queryForObject(
				"SELECT reservation_key_hash FROM broker_live_order_rate_limit_reservations",
				String.class);
		String symbolHash = jdbcTemplate.queryForObject(
				"SELECT symbol_hash FROM broker_live_order_rate_limit_reservations",
				String.class);

		assertThat(reservationHash).matches("[0-9a-f]{64}").isNotEqualTo("테스트-원문-미저장");
		assertThat(symbolHash).matches("[0-9a-f]{64}").isNotEqualTo("AAPL");
	}

	/** 같은 예약 식별값을 다른 계좌나 종목에 재사용하면 기존 집계를 바꾸지 않는지 검사합니다. */
	@Test
	@DisplayName("같은 LIVE 빈도 예약 식별값의 다른 계좌와 종목을 거절한다")
	void 같은_LIVE_빈도_예약_식별값의_다른_계좌와_종목을_거절한다() {
		service.reserve(1L, "AAPL", "테스트-예약-충돌");

		assertThatThrownBy(() -> service.reserve(2L, "AAPL", "테스트-예약-충돌"))
				.isInstanceOf(BrokerMutationBlockedException.class);
		assertThatThrownBy(() -> service.reserve(1L, "MSFT", "테스트-예약-충돌"))
				.isInstanceOf(BrokerMutationBlockedException.class);
		assertThat(예약_개수를_조회한다()).isOne();
	}

	/** 동시에 몰린 세 요청도 직렬화되어 종목 상한 안의 두 건만 저장하는지 검사합니다. */
	@Test
	@DisplayName("동시 LIVE 주문은 1분 종목 빈도 한도를 원자적으로 적용한다")
	void 동시_LIVE_주문은_1분_종목_빈도_한도를_원자적으로_적용한다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Boolean> first = executor.submit(
					() -> 예약_성공_여부를_반환한다(start, "테스트-동시-첫번째"));
			Future<Boolean> second = executor.submit(
					() -> 예약_성공_여부를_반환한다(start, "테스트-동시-두번째"));
			Future<Boolean> third = executor.submit(
					() -> 예약_성공_여부를_반환한다(start, "테스트-동시-세번째"));
			start.countDown();

			assertThat(List.of(
					first.get(5, TimeUnit.SECONDS),
					second.get(5, TimeUnit.SECONDS),
					third.get(5, TimeUnit.SECONDS)))
					.filteredOn(Boolean::booleanValue)
					.hasSize(2);
			assertThat(예약_개수를_조회한다()).isEqualTo(2);
		} finally {
			executor.shutdownNow();
		}
	}

	/** 시작 신호 뒤 합성 주문 빈도를 예약하고 차단 여부를 성공값으로 변환합니다. */
	private boolean 예약_성공_여부를_반환한다(
			CountDownLatch start,
			String reservationKey) throws InterruptedException {
		start.await();
		try {
			service.reserve(1L, "AAPL", reservationKey);
			return true;
		} catch (BrokerMutationBlockedException exception) {
			return false;
		}
	}

	/** 현재 데이터베이스에 저장된 합성 빈도 예약 행 수를 반환합니다. */
	private int 예약_개수를_조회한다() {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM broker_live_order_rate_limit_reservations",
				Integer.class);
		return count == null ? 0 : count;
	}
}

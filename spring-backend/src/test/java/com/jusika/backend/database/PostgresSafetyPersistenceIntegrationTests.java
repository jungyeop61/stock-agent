package com.jusika.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jusika.backend.brokeraudit.BrokerMutationAuditOutcome;
import com.jusika.backend.brokeraudit.BrokerMutationAuditService;
import com.jusika.backend.brokeraudit.BrokerMutationAuditStage;
import com.jusika.backend.brokersafety.BrokerLiveDailyOrderRiskService;
import com.jusika.backend.brokersafety.BrokerLiveOrderRateLimitService;
import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.brokersafety.BrokerUnknownIncidentAcknowledgementRequest;
import com.jusika.backend.brokersafety.BrokerUnknownIncidentAcknowledgementService;

/** 실제 외부 주문 없이 격리된 PostgreSQL에서 마이그레이션과 안전 저장소 잠금을 검사합니다. */
@SpringBootTest(properties = {
		"jusika.broker.live-daily-order-limits.max-quantity=10",
		"jusika.broker.live-daily-order-limits.max-krw-order-amount=1000",
		"jusika.broker.live-daily-order-limits.max-usd-order-amount=100",
		"jusika.broker.live-order-rate-limits.max-mutations-per-account-per-minute=3",
		"jusika.broker.live-order-rate-limits.max-mutations-per-instrument-per-minute=2"
})
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "RUN_JUSIKA_POSTGRES_TEST", matches = "(?i)true")
class PostgresSafetyPersistenceIntegrationTests {

	private static final String TEST_URL_ENV = "JUSIKA_TEST_POSTGRES_URL";
	private static final String TEST_USERNAME_ENV = "JUSIKA_TEST_POSTGRES_USERNAME";
	private static final String TEST_PASSWORD_ENV = "JUSIKA_TEST_POSTGRES_PASSWORD";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private BrokerLiveDailyOrderRiskService dailyRiskService;

	@Autowired
	private BrokerLiveOrderRateLimitService rateLimitService;

	@Autowired
	private BrokerMutationAuditService auditService;

	@Autowired
	private BrokerUnknownIncidentAcknowledgementService acknowledgementService;

	/** 명시적으로 지정된 테스트 전용 PostgreSQL 연결 정보만 Spring 테스트에 주입합니다. */
	@DynamicPropertySource
	static void 테스트_PostgreSQL_연결_정보를_설정한다(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", PostgresSafetyPersistenceIntegrationTests::테스트_DB_URL을_확인한다);
		registry.add("spring.datasource.username", () -> 필수_환경변수를_읽는다(TEST_USERNAME_ENV));
		registry.add("spring.datasource.password", () -> 필수_환경변수를_읽는다(TEST_PASSWORD_ENV));
	}

	/** 각 검사 전 현재 연결이 테스트 DB인지 다시 확인하고 합성 자료를 비웁니다. */
	@BeforeEach
	void 테스트_DB를_확인하고_자료를_초기화한다() {
		합성_자료를_지운다();
	}

	/** 다음 검사나 로컬 실행에 합성 자료가 남지 않도록 정리합니다. */
	@AfterEach
	void 합성_자료를_정리한다() {
		합성_자료를_지운다();
	}

	/** 실제 PostgreSQL에 전체 Flyway 이력이 순서대로 적용되어 V19에 도달했는지 검사합니다. */
	@Test
	@DisplayName("PostgreSQL에 Flyway V1부터 V19까지 적용한다")
	void PostgreSQL에_Flyway_V1부터_V19까지_적용한다() {
		String latestVersion = jdbcTemplate.queryForObject("""
				select version
				from flyway_schema_history
				where success = true
				order by installed_rank desc
				limit 1
				""", String.class);
		List<String> safetyTables = jdbcTemplate.queryForList("""
				select table_name
				from information_schema.tables
				where table_schema = current_schema()
				  and table_name in (
				    'broker_live_daily_order_risk_reservations',
				    'broker_live_order_rate_limit_reservations',
				    'broker_mutation_audit_events',
				    'broker_unknown_incident_acknowledgements'
				  )
				""", String.class);

		assertThat(latestVersion).isEqualTo("19");
		assertThat(safetyTables).hasSize(4);
	}

	/** PostgreSQL 행 잠금이 동시 일일 예약을 직렬화해 한도 안의 한 건만 저장하는지 검사합니다. */
	@Test
	@DisplayName("PostgreSQL에서 동시 일일 위험 예약을 원자적으로 제한한다")
	void PostgreSQL에서_동시_일일_위험_예약을_원자적으로_제한한다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		BrokerOrderRiskSnapshot risk = new BrokerOrderRiskSnapshot(
				new BigDecimal("6"), new BigDecimal("600"), "KRW");

		try {
			Future<Boolean> first = executor.submit(
					() -> 일일_위험_예약_성공_여부를_반환한다(start, "통합-동시-첫번째", risk));
			Future<Boolean> second = executor.submit(
					() -> 일일_위험_예약_성공_여부를_반환한다(start, "통합-동시-두번째", risk));
			start.countDown();

			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
			assertThat(행_수를_조회한다("broker_live_daily_order_risk_reservations")).isOne();
		} finally {
			executor.shutdownNow();
		}
	}

	/** PostgreSQL에서도 같은 논리 요청의 1분 빈도 예약이 한 번만 집계되는지 검사합니다. */
	@Test
	@DisplayName("PostgreSQL에서 주문 빈도 예약을 멱등하게 저장한다")
	void PostgreSQL에서_주문_빈도_예약을_멱등하게_저장한다() {
		rateLimitService.reserve(1L, "AAPL", "통합-동일-빈도");

		assertThatCode(() -> rateLimitService.reserve(1L, "aapl", "통합-동일-빈도"))
				.doesNotThrowAnyException();
		assertThat(행_수를_조회한다("broker_live_order_rate_limit_reservations")).isOne();
	}

	/** PostgreSQL에서 UNKNOWN 감사 사건과 수동 확인 이력이 안전정지 상태를 바꾸는지 검사합니다. */
	@Test
	@DisplayName("PostgreSQL에서 결과 불명 사고 확인 이력을 저장한다")
	void PostgreSQL에서_결과_불명_사고_확인_이력을_저장한다() {
		auditService.record(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.UNKNOWN);
		Long unknownEventId = jdbcTemplate.queryForObject(
				"select max(audit_event_id) from broker_mutation_audit_events", Long.class);

		assertThat(unknownEventId).isNotNull();
		assertThat(acknowledgementService.isHaltActive()).isTrue();
		assertThat(acknowledgementService.acknowledge(
				new BrokerUnknownIncidentAcknowledgementRequest(unknownEventId, true)).haltActive())
				.isFalse();
		assertThat(행_수를_조회한다("broker_unknown_incident_acknowledgements")).isOne();
	}

	/** 시작 신호 뒤 합성 일일 위험을 예약하고 한도 차단 여부를 성공값으로 변환합니다. */
	private boolean 일일_위험_예약_성공_여부를_반환한다(
			CountDownLatch start,
			String reservationKey,
			BrokerOrderRiskSnapshot risk) throws InterruptedException {
		start.await();
		try {
			dailyRiskService.reserve(1L, reservationKey, risk);
			return true;
		} catch (BrokerMutationBlockedException exception) {
			return false;
		}
	}

	/** 허용 목록에 있는 테스트 테이블의 현재 행 수를 반환합니다. */
	private int 행_수를_조회한다(String tableName) {
		if (!List.of(
				"broker_live_daily_order_risk_reservations",
				"broker_live_order_rate_limit_reservations",
				"broker_unknown_incident_acknowledgements").contains(tableName)) {
			throw new IllegalArgumentException("허용되지 않은 통합 테스트 테이블입니다.");
		}
		Integer count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class);
		return count == null ? 0 : count;
	}

	/** 외래키 순서와 확인 기준을 지켜 통합 테스트가 만든 합성 자료만 모두 삭제합니다. */
	private void 합성_자료를_지운다() {
		현재_연결이_테스트_DB인지_확인한다();
		jdbcTemplate.update("delete from broker_unknown_incident_acknowledgements");
		jdbcTemplate.update("delete from broker_mutation_audit_events");
		jdbcTemplate.update("delete from broker_live_order_rate_limit_reservations");
		jdbcTemplate.update("delete from broker_live_daily_order_risk_reservations");
	}

	/** 자료를 지우기 직전에 현재 연결된 데이터베이스가 테스트 전용인지 다시 검사합니다. */
	private void 현재_연결이_테스트_DB인지_확인한다() {
		String databaseName = jdbcTemplate.queryForObject("select current_database()", String.class);
		if (!테스트_DB_이름인가(databaseName)) {
			throw new IllegalStateException("테스트 전용 데이터베이스가 아니므로 자료 정리를 중단합니다.");
		}
	}

	/** JDBC 주소가 PostgreSQL이며 데이터베이스 이름이 명백한 테스트 용도인지 확인합니다. */
	private static String 테스트_DB_URL을_확인한다() {
		String url = 필수_환경변수를_읽는다(TEST_URL_ENV);
		if (!url.startsWith("jdbc:postgresql://")) {
			throw new IllegalStateException("PostgreSQL 통합 테스트 JDBC 주소만 사용할 수 있습니다.");
		}
		int queryStart = url.indexOf('?');
		String withoutQuery = queryStart < 0 ? url : url.substring(0, queryStart);
		int databaseSeparator = withoutQuery.lastIndexOf('/');
		String databaseName = databaseSeparator < 0 ? "" : withoutQuery.substring(databaseSeparator + 1);
		if (!테스트_DB_이름인가(databaseName)) {
			throw new IllegalStateException("DB 이름에 test 또는 integration이 포함되어야 합니다.");
		}
		return url;
	}

	/** 데이터베이스 이름에 테스트 전용 표식이 들어 있는지 대소문자 구분 없이 확인합니다. */
	private static boolean 테스트_DB_이름인가(String databaseName) {
		if (databaseName == null || databaseName.isBlank()) {
			return false;
		}
		String normalized = databaseName.toLowerCase(Locale.ROOT);
		return normalized.matches(".*(^|[_-])(test|integration)([_-]|$).*");
	}

	/** 비어 있지 않은 필수 통합 테스트 환경변수를 읽되 값은 오류 메시지에 노출하지 않습니다. */
	private static String 필수_환경변수를_읽는다(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("PostgreSQL 통합 테스트 필수 환경변수가 없습니다: " + name);
		}
		return value;
	}
}

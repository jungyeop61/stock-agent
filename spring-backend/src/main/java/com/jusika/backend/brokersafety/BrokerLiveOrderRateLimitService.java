package com.jusika.backend.brokersafety;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** LIVE 신규 주문·정정·복구의 계좌·종목별 1분 호출 수를 원자적으로 제한합니다. */
@Service
public class BrokerLiveOrderRateLimitService {

	private static final int MAX_KEY_LENGTH = 1024;
	private static final String LOCK_SQL = """
			SELECT lock_id
			FROM broker_live_order_rate_limit_lock
			WHERE lock_id = 1
			FOR UPDATE
			""";

	private final JdbcTemplate jdbcTemplate;
	private final BrokerSafetyProperties properties;
	private final Clock clock;

	/** 빈도 예약 저장소, 승인된 상한과 현재 시각을 연결합니다. */
	public BrokerLiveOrderRateLimitService(
			JdbcTemplate jdbcTemplate,
			BrokerSafetyProperties properties,
			Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.clock = clock;
	}

	/** 내부 실행 상태를 만들기 전에 현재 1분 빈도에 새 요청을 더할 수 있는지 확인합니다. */
	@Transactional(readOnly = true)
	public void requireCanReserve(long accountSeq, String symbol) {
		ValidatedInput input = validateInput(accountSeq, symbol);
		validateCounts(input, currentWindowStart());
	}

	/** 같은 논리 요청의 기존 예약을 인식하면서 현재 1분 빈도를 사전 검사합니다. */
	@Transactional(readOnly = true)
	public void requireCanReserve(long accountSeq, String symbol, String reservationKey) {
		ValidatedInput input = validateInput(accountSeq, symbol);
		String reservationHash = hash(reservationKey, "LIVE 주문 빈도 예약 식별값이 올바르지 않습니다.");
		RateReservation existing = findReservation(reservationHash);
		if (existing != null) {
			validateSameReservation(existing, input);
			return;
		}
		validateCounts(input, currentWindowStart());
	}

	/** 실제 토스 호출 직전에 전체 예약을 직렬화해 같은 논리 요청을 한 번만 집계합니다. */
	@Transactional
	public void reserve(long accountSeq, String symbol, String reservationKey) {
		ValidatedInput input = validateInput(accountSeq, symbol);
		String reservationHash = hash(reservationKey, "LIVE 주문 빈도 예약 식별값이 올바르지 않습니다.");
		jdbcTemplate.queryForObject(LOCK_SQL, Integer.class);
		RateReservation existing = findReservation(reservationHash);
		if (existing != null) {
			validateSameReservation(existing, input);
			return;
		}
		OffsetDateTime windowStart = currentWindowStart();
		validateCounts(input, windowStart);
		jdbcTemplate.update("""
				INSERT INTO broker_live_order_rate_limit_reservations (
				    reservation_key_hash, account_seq, symbol_hash, window_start, created_at
				) VALUES (?, ?, ?, ?, ?)
				""",
				reservationHash,
				input.accountSeq(),
				input.symbolHash(),
				windowStart,
				OffsetDateTime.now(clock));
	}

	/** 계좌·종목과 활성화된 빈도 설정을 검사하고 종목 원문을 해시로 바꿉니다. */
	private ValidatedInput validateInput(long accountSeq, String symbol) {
		if (accountSeq <= 0) {
			throw new IllegalArgumentException("주문 빈도를 검사할 계좌 식별값이 올바르지 않습니다.");
		}
		if (!properties.liveOrderRateLimits().isConfigured()) {
			throw new BrokerMutationBlockedException("실제 주문 빈도 한도가 설정되어 있지 않습니다.");
		}
		String symbolHash = hash(
				symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT),
				"주문 빈도를 검사할 종목 코드가 올바르지 않습니다.");
		return new ValidatedInput(accountSeq, symbolHash);
	}

	/** 현재 1분 구간의 계좌 전체와 동일 종목 예약 수에 새 요청 하나를 더해 검사합니다. */
	private void validateCounts(ValidatedInput input, OffsetDateTime windowStart) {
		BrokerLiveOrderRateLimitProperties limits = properties.liveOrderRateLimits();
		Integer accountCount = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM broker_live_order_rate_limit_reservations
				WHERE account_seq = ? AND window_start = ?
				""", Integer.class, input.accountSeq(), windowStart);
		if (requireCount(accountCount) + 1 > limits.maxMutationsPerAccountPerMinute()) {
			throw new BrokerMutationBlockedException("실제 주문의 계좌별 1분 빈도 한도를 초과합니다.");
		}
		Integer instrumentCount = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM broker_live_order_rate_limit_reservations
				WHERE account_seq = ? AND symbol_hash = ? AND window_start = ?
				""", Integer.class, input.accountSeq(), input.symbolHash(), windowStart);
		if (requireCount(instrumentCount) + 1 > limits.maxMutationsPerInstrumentPerMinute()) {
			throw new BrokerMutationBlockedException("실제 주문의 종목별 1분 빈도 한도를 초과합니다.");
		}
	}

	/** 데이터베이스 집계값이 누락되거나 음수이면 안전하게 실행을 중단합니다. */
	private int requireCount(Integer count) {
		if (count == null || count < 0) {
			throw new IllegalStateException("LIVE 주문 빈도 집계값이 올바르지 않습니다.");
		}
		return count;
	}

	/** 해시로 이미 예약한 논리 요청의 비교 필드를 조회합니다. */
	private RateReservation findReservation(String reservationHash) {
		List<RateReservation> found = jdbcTemplate.query("""
				SELECT account_seq, symbol_hash
				FROM broker_live_order_rate_limit_reservations
				WHERE reservation_key_hash = ?
				""",
				(resultSet, rowNumber) -> new RateReservation(
						resultSet.getLong("account_seq"),
						resultSet.getString("symbol_hash")),
				reservationHash);
		return found.isEmpty() ? null : found.getFirst();
	}

	/** 같은 예약 식별값이 최초 계좌와 종목에만 재사용되는지 검사합니다. */
	private void validateSameReservation(RateReservation existing, ValidatedInput input) {
		if (existing.accountSeq() != input.accountSeq()
				|| !existing.symbolHash().equals(input.symbolHash())) {
			throw new BrokerMutationBlockedException(
					"같은 LIVE 주문 빈도 예약 식별값의 계좌 또는 종목이 일치하지 않습니다.");
		}
	}

	/** 원문 식별값과 종목을 저장하지 않도록 제한을 검사한 뒤 SHA-256으로 변환합니다. */
	private String hash(String value, String errorMessage) {
		if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) {
			throw new IllegalArgumentException(errorMessage);
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("LIVE 주문 빈도 예약 해시를 만들 수 없습니다.", exception);
		}
	}

	/** 현재 시각을 UTC 기준 1분 구간의 시작 시각으로 변환합니다. */
	private OffsetDateTime currentWindowStart() {
		Instant start = clock.instant().truncatedTo(ChronoUnit.MINUTES);
		return OffsetDateTime.ofInstant(start, ZoneOffset.UTC);
	}

	/** 검증을 마친 계좌 식별값과 종목 해시입니다. */
	private record ValidatedInput(long accountSeq, String symbolHash) {
	}

	/** 기존 빈도 예약의 원문 없는 비교 필드입니다. */
	private record RateReservation(long accountSeq, String symbolHash) {
	}
}

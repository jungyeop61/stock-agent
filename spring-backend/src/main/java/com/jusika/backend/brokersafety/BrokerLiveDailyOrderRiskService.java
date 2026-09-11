package com.jusika.backend.brokersafety;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 주문 직전에 계좌별 한국시간 일일 누적 위험을 데이터베이스에 원자적으로 예약합니다.
 * 주문 식별값 원문은 저장하지 않고 SHA-256 해시만 보관합니다.
 */
@Service
public class BrokerLiveDailyOrderRiskService {

	private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
	private static final int MAX_RESERVATION_KEY_LENGTH = 1024;
	private static final String LOCK_SQL = """
			SELECT lock_id
			FROM broker_live_daily_order_risk_lock
			WHERE lock_id = 1
			FOR UPDATE
			""";

	private final JdbcTemplate jdbcTemplate;
	private final BrokerSafetyProperties properties;
	private final Clock clock;

	/** 일일 누적값 저장소, 승인된 상한과 현재 시각을 전달받습니다. */
	public BrokerLiveDailyOrderRiskService(
			JdbcTemplate jdbcTemplate,
			BrokerSafetyProperties properties,
			Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 내부 실행 상태를 만들기 전에 현재 누적값에 새 주문을 더할 수 있는지 읽기 전용으로 확인합니다.
	 * 최종 동시성 판정은 실제 토스 호출 직전의 원자적 예약에서 다시 수행합니다.
	 */
	@Transactional(readOnly = true)
	public void requireCanReserve(long accountSeq, BrokerOrderRiskSnapshot riskSnapshot) {
		validateInput(accountSeq, riskSnapshot);
		LocalDate businessDate = currentBusinessDate();
		DailyTotals totals = loadTotals(accountSeq, businessDate, riskSnapshot.currency());
		validateTotals(totals, riskSnapshot);
	}

	/**
	 * 안전 복구 전에 같은 예약 식별값이 이미 같은 위험으로 저장됐으면 추가 누적 없이 통과시킵니다.
	 * 기존 예약이 없으면 일반 사전 검사와 같이 현재 누적값에 더해 한도를 확인합니다.
	 */
	@Transactional(readOnly = true)
	public void requireCanReserve(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		validateInput(accountSeq, riskSnapshot);
		validateReservationKey(reservationKey);
		LocalDate businessDate = currentBusinessDate();
		DailyReservation existing = findReservation(hashReservationKey(reservationKey));
		if (existing != null) {
			validateSameReservation(existing, accountSeq, businessDate, riskSnapshot);
			return;
		}
		DailyTotals totals = loadTotals(accountSeq, businessDate, riskSnapshot.currency());
		validateTotals(totals, riskSnapshot);
	}

	/**
	 * 실제 토스 호출 직전에 전체 LIVE 주문을 직렬화하고 일일 누적 위험을 한 번만 예약합니다.
	 * 같은 예약 식별값과 같은 위험값의 재호출은 누적값을 다시 더하지 않습니다.
	 */
	@Transactional
	public void reserve(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		validateInput(accountSeq, riskSnapshot);
		validateReservationKey(reservationKey);
		jdbcTemplate.queryForObject(LOCK_SQL, Integer.class);

		LocalDate businessDate = currentBusinessDate();
		String reservationKeyHash = hashReservationKey(reservationKey);
		DailyReservation existing = findReservation(reservationKeyHash);
		if (existing != null) {
			validateSameReservation(existing, accountSeq, businessDate, riskSnapshot);
			return;
		}

		DailyTotals totals = loadTotals(accountSeq, businessDate, riskSnapshot.currency());
		validateTotals(totals, riskSnapshot);
		jdbcTemplate.update("""
				INSERT INTO broker_live_daily_order_risk_reservations (
				    reservation_key_hash, account_seq, business_date, currency,
				    quantity, order_amount, created_at
				) VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
				reservationKeyHash,
				accountSeq,
				businessDate,
				riskSnapshot.currency(),
				riskSnapshot.quantity(),
				riskSnapshot.orderAmount(),
				OffsetDateTime.now(clock));
	}

	/** 계좌, 위험값과 활성화된 일일 한도 설정이 유효한지 검사합니다. */
	private void validateInput(long accountSeq, BrokerOrderRiskSnapshot riskSnapshot) {
		if (accountSeq <= 0) {
			throw new IllegalArgumentException("일일 주문 한도를 검사할 계좌 식별값이 올바르지 않습니다.");
		}
		if (riskSnapshot == null
				|| riskSnapshot.orderAmount() == null
				|| riskSnapshot.orderAmount().signum() <= 0
				|| (riskSnapshot.quantity() != null && riskSnapshot.quantity().signum() <= 0)
				|| !("KRW".equals(riskSnapshot.currency()) || "USD".equals(riskSnapshot.currency()))) {
			throw new IllegalArgumentException("LIVE 일일 주문 한도 검사값이 올바르지 않습니다.");
		}
		if (!properties.liveDailyOrderLimits().isConfigured()) {
			throw new BrokerMutationBlockedException(
					"실제 일일 누적 주문 수량·금액 한도가 설정되어 있지 않습니다.");
		}
	}

	/** 예약 식별값이 해시 입력으로 사용할 수 있는 제한 안에 있는지 검사합니다. */
	private void validateReservationKey(String reservationKey) {
		if (reservationKey == null || reservationKey.isBlank()
				|| reservationKey.length() > MAX_RESERVATION_KEY_LENGTH) {
			throw new IllegalArgumentException("LIVE 일일 주문 한도 예약 식별값이 올바르지 않습니다.");
		}
	}

	/** 현재 계좌와 날짜의 전체 수량 및 해당 통화 주문금액 누적값을 조회합니다. */
	private DailyTotals loadTotals(long accountSeq, LocalDate businessDate, String currency) {
		BigDecimal quantity = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(quantity), 0)
				FROM broker_live_daily_order_risk_reservations
				WHERE account_seq = ? AND business_date = ?
				""", BigDecimal.class, accountSeq, businessDate);
		BigDecimal orderAmount = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(order_amount), 0)
				FROM broker_live_daily_order_risk_reservations
				WHERE account_seq = ? AND business_date = ? AND currency = ?
				""", BigDecimal.class, accountSeq, businessDate, currency);
		return new DailyTotals(requireTotal(quantity), requireTotal(orderAmount));
	}

	/** 데이터베이스 합계가 누락되거나 음수이면 안전하게 내부 오류로 거절합니다. */
	private BigDecimal requireTotal(BigDecimal total) {
		if (total == null || total.signum() < 0) {
			throw new IllegalStateException("LIVE 일일 주문 누적값이 올바르지 않습니다.");
		}
		return total;
	}

	/** 새 주문을 더한 누적 수량과 누적 금액이 승인된 일일 상한 이내인지 검사합니다. */
	private void validateTotals(DailyTotals totals, BrokerOrderRiskSnapshot riskSnapshot) {
		BrokerLiveDailyOrderLimitProperties limits = properties.liveDailyOrderLimits();
		BigDecimal addedQuantity = riskSnapshot.quantity() == null
				? BigDecimal.ZERO : riskSnapshot.quantity();
		if (totals.quantity().add(addedQuantity).compareTo(limits.maxQuantity()) > 0) {
			throw new BrokerMutationBlockedException("실제 주문의 일일 누적 수량 안전 한도를 초과합니다.");
		}
		if (totals.orderAmount().add(riskSnapshot.orderAmount()).compareTo(
				limits.maxOrderAmount(riskSnapshot.currency())) > 0) {
			throw new BrokerMutationBlockedException("실제 주문의 일일 누적 금액 안전 한도를 초과합니다.");
		}
	}

	/** 해시로 이미 예약한 주문이 있는지 조회합니다. */
	private DailyReservation findReservation(String reservationKeyHash) {
		List<DailyReservation> reservations = jdbcTemplate.query("""
				SELECT account_seq, business_date, currency, quantity, order_amount
				FROM broker_live_daily_order_risk_reservations
				WHERE reservation_key_hash = ?
				""",
				(resultSet, rowNumber) -> new DailyReservation(
						resultSet.getLong("account_seq"),
						resultSet.getObject("business_date", LocalDate.class),
						resultSet.getString("currency"),
						resultSet.getBigDecimal("quantity"),
						resultSet.getBigDecimal("order_amount")),
				reservationKeyHash);
		return reservations.isEmpty() ? null : reservations.getFirst();
	}

	/** 동일 예약 식별값의 재호출이 최초 계좌·날짜·위험값과 완전히 같은지 검사합니다. */
	private void validateSameReservation(
			DailyReservation existing,
			long accountSeq,
			LocalDate businessDate,
			BrokerOrderRiskSnapshot riskSnapshot) {
		if (existing.accountSeq() != accountSeq
				|| !existing.businessDate().equals(businessDate)
				|| !existing.currency().equals(riskSnapshot.currency())
				|| !sameDecimal(existing.quantity(), riskSnapshot.quantity())
				|| !sameDecimal(existing.orderAmount(), riskSnapshot.orderAmount())) {
			throw new BrokerMutationBlockedException(
					"같은 LIVE 일일 한도 예약 식별값의 주문 위험값이 일치하지 않습니다.");
		}
	}

	/** 자리수가 달라도 두 금융값의 실제 숫자가 같은지 비교합니다. */
	private boolean sameDecimal(BigDecimal first, BigDecimal second) {
		if (first == null || second == null) {
			return first == null && second == null;
		}
		return first.compareTo(second) == 0;
	}

	/** 예약 식별값 원문을 저장하지 않도록 SHA-256 해시 문자열로 변환합니다. */
	private String hashReservationKey(String reservationKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(reservationKey.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("LIVE 일일 한도 예약 해시를 만들 수 없습니다.", exception);
		}
	}

	/** 현재 시각을 한국시간의 일일 한도 기준 날짜로 변환합니다. */
	private LocalDate currentBusinessDate() {
		return LocalDate.now(clock.withZone(BUSINESS_ZONE));
	}

	/** 데이터베이스에서 계산한 계좌별 하루 누적 수량과 통화별 금액입니다. */
	private record DailyTotals(BigDecimal quantity, BigDecimal orderAmount) {
	}

	/** 기존 일일 위험 예약의 원문 없는 비교 필드입니다. */
	private record DailyReservation(
			long accountSeq,
			LocalDate businessDate,
			String currency,
			BigDecimal quantity,
			BigDecimal orderAmount) {
	}
}

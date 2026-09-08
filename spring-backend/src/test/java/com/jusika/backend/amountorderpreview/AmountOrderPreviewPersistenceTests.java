package com.jusika.backend.amountorderpreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * Flyway V11과 V12로 만든 H2 테이블에서 달러 금액 주문 미리보기의 저장과 승인을 검사합니다.
 */
@SpringBootTest
class AmountOrderPreviewPersistenceTests {

	@Autowired
	private AmountOrderPreviewStore previewStore;

	/**
	 * 금액 주문 미리보기의 달러 계산값과 환율 사본을 손실 없이 저장하는지 검사합니다.
	 */
	@Test
	@DisplayName("금액 주문 미리보기 전체 계산 결과를 데이터베이스에 저장한다")
	void 금액_주문_미리보기_전체_계산_결과를_데이터베이스에_저장한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 8, 9, 30, 30, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse preview = new AmountOrderPreviewResponse(
				UUID.randomUUID().toString(),
				createdAt,
				createdAt.plusMinutes(2),
				1L,
				"AAPL",
				OrderSide.BUY,
				OrderType.MARKET,
				new BigDecimal("100.1234567890123456789012345678"),
				"USD",
				"US",
				new BigDecimal("200.25"),
				new BigDecimal("0.5"),
				new BigDecimal("0.001"),
				new BigDecimal("0.100123456789012346"),
				new BigDecimal("100.2235802458013580249012345678"),
				new BigDecimal("1400.5"),
				createdAt.minusSeconds(30),
				createdAt.plusSeconds(30),
				new BigDecimal("140223.401234567890123456"),
				false,
				true,
				OrderPreviewStatus.PENDING_APPROVAL,
				null);

		AmountOrderPreviewResponse saved = previewStore.save(preview);
		AmountOrderPreviewResponse found = previewStore.findById(preview.previewId()).orElseThrow();

		assertThat(saved.previewId()).isEqualTo(preview.previewId());
		assertThat(found.createdAt()).isEqualTo(createdAt);
		assertThat(found.expiresAt()).isEqualTo(createdAt.plusMinutes(2));
		assertThat(found.accountSeq()).isEqualTo(1L);
		assertThat(found.symbol()).isEqualTo("AAPL");
		assertThat(found.side()).isEqualTo(OrderSide.BUY);
		assertThat(found.orderType()).isEqualTo(OrderType.MARKET);
		assertThat(found.orderAmount()).isEqualByComparingTo(preview.orderAmount());
		assertThat(found.referencePrice()).isEqualByComparingTo("200.25");
		assertThat(found.estimatedQuantity()).isEqualByComparingTo("0.5");
		assertThat(found.estimatedCommission())
				.isEqualByComparingTo(preview.estimatedCommission());
		assertThat(found.estimatedTotalCost()).isEqualByComparingTo(preview.estimatedTotalCost());
		assertThat(found.exchangeRate()).isEqualByComparingTo("1400.5");
		assertThat(found.exchangeRateValidFrom()).isEqualTo(createdAt.minusSeconds(30));
		assertThat(found.exchangeRateValidUntil()).isEqualTo(createdAt.plusSeconds(30));
		assertThat(found.estimatedOrderAmountKrw())
				.isEqualByComparingTo(preview.estimatedOrderAmountKrw());
		assertThat(found.status()).isEqualTo(OrderPreviewStatus.PENDING_APPROVAL);
		assertThat(found.approvedAt()).isNull();
	}

	/**
	 * 유효한 승인 대기 행은 금융값을 유지하면서 데이터베이스에서 한 번만 승인되는지 검사합니다.
	 */
	@Test
	@DisplayName("금액 주문 미리보기를 데이터베이스에서 한 번만 승인한다")
	void 금액_주문_미리보기를_데이터베이스에서_한_번만_승인한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 8, 9, 30, 30, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse preview = 저장할_금액_주문_미리보기를_만든다(createdAt);
		previewStore.save(preview);
		OffsetDateTime approvedAt = createdAt.plusMinutes(1);

		boolean firstApproval = previewStore.approvePending(preview.previewId(), approvedAt);
		boolean duplicateApproval = previewStore.approvePending(preview.previewId(), approvedAt.plusSeconds(1));
		AmountOrderPreviewResponse approved = previewStore.findById(preview.previewId()).orElseThrow();

		assertThat(firstApproval).isTrue();
		assertThat(duplicateApproval).isFalse();
		assertThat(approved.status()).isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(approved.approvedAt()).isEqualTo(approvedAt);
		assertThat(approved.orderAmount()).isEqualByComparingTo(preview.orderAmount());
		assertThat(approved.estimatedTotalCost())
				.isEqualByComparingTo(preview.estimatedTotalCost());
		assertThat(approved.exchangeRate()).isEqualByComparingTo(preview.exchangeRate());
	}

	/**
	 * 유효 종료 시각과 정확히 같은 승인 대기 행은 승인하지 않고 만료시키는지 검사합니다.
	 */
	@Test
	@DisplayName("금액 주문 미리보기를 유효 종료 시각에 만료한다")
	void 금액_주문_미리보기를_유효_종료_시각에_만료한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 8, 9, 30, 30, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse preview = 저장할_금액_주문_미리보기를_만든다(createdAt);
		previewStore.save(preview);

		boolean expired = previewStore.expirePending(preview.previewId(), preview.expiresAt());
		boolean approved = previewStore.approvePending(preview.previewId(), preview.expiresAt());
		AmountOrderPreviewResponse found = previewStore.findById(preview.previewId()).orElseThrow();

		assertThat(expired).isTrue();
		assertThat(approved).isFalse();
		assertThat(found.status()).isEqualTo(OrderPreviewStatus.EXPIRED);
		assertThat(found.approvedAt()).isNull();
	}

	/**
	 * 데이터베이스 승인 테스트에서 공통으로 사용할 정상 금액 주문 미리보기를 만듭니다.
	 *
	 * @param createdAt 미리보기 생성 시각
	 * @return 승인 대기 상태의 금액 주문 미리보기
	 */
	private AmountOrderPreviewResponse 저장할_금액_주문_미리보기를_만든다(OffsetDateTime createdAt) {
		return new AmountOrderPreviewResponse(
				UUID.randomUUID().toString(),
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
				OrderPreviewStatus.PENDING_APPROVAL,
				null);
	}
}

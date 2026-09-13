package com.jusika.backend.brokeraudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.internalauth.InternalApiAuthorizationInterceptor;

/** V16 테이블의 LIVE 주문 변경 감사 저장과 커서 조회를 검사합니다. */
@SpringBootTest
class BrokerMutationAuditPersistenceTests {

	@Autowired
	private BrokerMutationAuditEventJpaRepository repository;

	@Autowired
	private BrokerMutationAuditService auditService;

	@Autowired
	private BrokerMutationSafetyPolicy safetyPolicy;

	/** 각 테스트가 독립된 감사 사건 목록을 사용하도록 기존 테스트 자료를 비웁니다. */
	@BeforeEach
	void 감사_사건을_비운다() {
		repository.deleteAll();
	}

	/** 요청 문맥이 다음 테스트로 전달되지 않도록 현재 요청 저장소를 정리합니다. */
	@AfterEach
	void 요청_문맥을_정리한다() {
		RequestContextHolder.resetRequestAttributes();
		repository.deleteAll();
	}

	/** 기본 MOCK 차단 결과가 계좌나 주문 정보 없이 정책 감사 사건으로 남는지 검사합니다. */
	@Test
	@DisplayName("기본 MOCK 차단을 LIVE 안전 관문 감사 사건으로 저장한다")
	void 기본_MOCK_차단을_LIVE_안전_관문_감사_사건으로_저장한다() {
		assertThatThrownBy(() -> safetyPolicy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
				.isInstanceOf(BrokerMutationBlockedException.class);

		BrokerMutationAuditListResponse response = auditService.getEvents(null, 10);

		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextBeforeEventId()).isNull();
		assertThat(response.events()).singleElement().satisfies(event -> {
			assertThat(event.requestId()).isNull();
			assertThat(event.capability())
					.isEqualTo(BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION);
			assertThat(event.stage()).isEqualTo(BrokerMutationAuditStage.SAFETY_GATE);
			assertThat(event.outcome()).isEqualTo(BrokerMutationAuditOutcome.BLOCKED);
		});
	}

	/** 현재 HTTP 요청의 검증된 UUID만 감사 사건에 연결되는지 검사합니다. */
	@Test
	@DisplayName("검증된 요청 UUID를 LIVE 변경 감사 사건에 연결한다")
	void 검증된_요청_UUID를_LIVE_변경_감사_사건에_연결한다() {
		String requestId = UUID.randomUUID().toString();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(
				InternalApiAuthorizationInterceptor.AUDIT_REQUEST_ID_ATTRIBUTE,
				requestId);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		auditService.record(
				BrokerMutationCapability.NORMAL_ORDER_CANCELLATION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.STARTED);

		assertThat(auditService.getEvents(null, 10).events())
				.singleElement()
				.extracting(BrokerMutationAuditEventResponse::requestId)
				.isEqualTo(requestId);
	}

	/** 다음 커서가 중복 없이 더 오래된 감사 사건만 반환하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 변경 감사 목록을 식별값 역순으로 나누어 조회한다")
	void LIVE_변경_감사_목록을_식별값_역순으로_나누어_조회한다() {
		auditService.record(
				BrokerMutationCapability.SINGLE_CONDITIONAL_ORDER_CREATION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.STARTED);
		auditService.record(
				BrokerMutationCapability.SINGLE_CONDITIONAL_ORDER_CREATION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.REJECTED);
		auditService.record(
				BrokerMutationCapability.SINGLE_CONDITIONAL_ORDER_CREATION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.UNKNOWN);

		BrokerMutationAuditListResponse first = auditService.getEvents(null, 2);
		BrokerMutationAuditListResponse second = auditService.getEvents(
				first.nextBeforeEventId(), 2);

		assertThat(first.hasNext()).isTrue();
		assertThat(first.events())
				.extracting(BrokerMutationAuditEventResponse::outcome)
				.containsExactly(
						BrokerMutationAuditOutcome.UNKNOWN,
						BrokerMutationAuditOutcome.REJECTED);
		assertThat(second.hasNext()).isFalse();
		assertThat(second.events())
				.extracting(BrokerMutationAuditEventResponse::outcome)
				.containsExactly(BrokerMutationAuditOutcome.STARTED);
	}

	/** 토스 요청의 UNKNOWN 사건만 자동 안전정지 근거로 판단하는지 검사합니다. */
	@Test
	@DisplayName("토스 요청 결과 불명 사건을 자동 안전정지 근거로 조회한다")
	void 토스_요청_결과_불명_사건을_자동_안전정지_근거로_조회한다() {
		auditService.record(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.REJECTED);
		assertThat(auditService.hasUnknownBrokerRequest()).isFalse();

		auditService.record(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.UNKNOWN);

		assertThat(auditService.hasUnknownBrokerRequest()).isTrue();
	}

	/** 잘못된 커서와 과도한 조회 개수를 데이터베이스 접근 전에 거절하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 변경 감사 목록의 잘못된 조회 범위를 거절한다")
	void LIVE_변경_감사_목록의_잘못된_조회_범위를_거절한다() {
		assertThatThrownBy(() -> auditService.getEvents(0L, 10))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("감사 사건 커서는 1 이상이어야 합니다.");
		assertThatThrownBy(() -> auditService.getEvents(null, 101))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("감사 사건 조회 개수는 1 이상 100 이하여야 합니다.");
	}
}

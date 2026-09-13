package com.jusika.backend.brokeraudit;

import java.time.OffsetDateTime;

import com.jusika.backend.brokersafety.BrokerMutationCapability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 계좌·종목·금액·주문 식별값을 제외한 LIVE 주문 변경 감사 사건입니다. */
@Entity
@Table(name = "broker_mutation_audit_events")
class BrokerMutationAuditEventEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long auditEventId;

	@Column(name = "request_id", length = 36)
	private String requestId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 64)
	private BrokerMutationCapability capability;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private BrokerMutationAuditStage stage;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private BrokerMutationAuditOutcome outcome;

	@Column(name = "occurred_at", nullable = false)
	private OffsetDateTime occurredAt;

	/** JPA가 감사 사건을 복원할 때 사용하는 기본 생성자입니다. */
	protected BrokerMutationAuditEventEntity() {
	}

	/** 민감정보가 제거된 새 LIVE 주문 변경 감사 사건을 만듭니다. */
	BrokerMutationAuditEventEntity(
			String requestId,
			BrokerMutationCapability capability,
			BrokerMutationAuditStage stage,
			BrokerMutationAuditOutcome outcome,
			OffsetDateTime occurredAt) {
		this.requestId = requestId;
		this.capability = capability;
		this.stage = stage;
		this.outcome = outcome;
		this.occurredAt = occurredAt;
	}

	/** 감사 사건의 순차 식별값을 반환합니다. */
	Long auditEventId() {
		return auditEventId;
	}

	/** 같은 HTTP 요청을 연결하는 요청 UUID를 반환합니다. */
	String requestId() {
		return requestId;
	}

	/** 감사 대상 LIVE 주문 변경 기능을 반환합니다. */
	BrokerMutationCapability capability() {
		return capability;
	}

	/** 감사 사건이 발생한 처리 단계를 반환합니다. */
	BrokerMutationAuditStage stage() {
		return stage;
	}

	/** 민감정보가 제거된 처리 결과를 반환합니다. */
	BrokerMutationAuditOutcome outcome() {
		return outcome;
	}

	/** 감사 사건 발생 시각을 반환합니다. */
	OffsetDateTime occurredAt() {
		return occurredAt;
	}
}

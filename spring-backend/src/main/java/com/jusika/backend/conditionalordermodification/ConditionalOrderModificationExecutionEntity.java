package com.jusika.backend.conditionalordermodification;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/** 조건 주문 정정 한 건의 제출 상태와 대체 식별값을 데이터베이스에 보관합니다. */
@Entity
@Table(
		name = "conditional_order_modification_executions",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_conditional_modify_target",
				columnNames = {"account_seq", "original_conditional_order_id"}))
class ConditionalOrderModificationExecutionEntity {

	@Id
	@Column(name = "execution_id", nullable = false, length = 36)
	private String executionId;
	@Column(name = "preview_id", nullable = false, unique = true, length = 36)
	private String previewId;
	@Column(name = "account_seq", nullable = false)
	private long accountSeq;
	@Column(name = "original_conditional_order_id", nullable = false, length = 512)
	private String originalConditionalOrderId;
	@Column(name = "replacement_conditional_order_id", length = 512)
	private String replacementConditionalOrderId;
	@Column(name = "broker_mode", nullable = false, length = 16)
	private String brokerMode;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OrderExecutionStatus status;
	@Enumerated(EnumType.STRING)
	@Column(name = "failure_type", length = 32)
	private OrderExecutionFailureType failureType;
	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;
	@Column(name = "submitted_at")
	private OffsetDateTime submittedAt;
	@Column(name = "completed_at")
	private OffsetDateTime completedAt;
	@Version
	@Column(nullable = false)
	private long version;

	/** JPA가 저장된 정정 실행 행을 복원할 때 사용하는 빈 생성자입니다. */
	protected ConditionalOrderModificationExecutionEntity() {
	}

	/** 정정 실행 응답의 모든 값을 새 저장 객체에 복사합니다. */
	private ConditionalOrderModificationExecutionEntity(
			ConditionalOrderModificationExecutionResponse value) {
		executionId = value.executionId(); previewId = value.previewId();
		accountSeq = value.accountSeq(); originalConditionalOrderId = value.originalConditionalOrderId();
		replacementConditionalOrderId = value.replacementConditionalOrderId();
		brokerMode = value.brokerMode(); status = value.status(); failureType = value.failureType();
		createdAt = value.createdAt(); updatedAt = value.updatedAt(); submittedAt = value.submittedAt();
		completedAt = value.completedAt();
	}

	/** 정정 실행 응답을 데이터베이스 저장 객체로 변환합니다. */
	static ConditionalOrderModificationExecutionEntity from(
			ConditionalOrderModificationExecutionResponse value) {
		return new ConditionalOrderModificationExecutionEntity(value);
	}

	/** 데이터베이스에 저장된 정정 실행 상태를 API 응답으로 변환합니다. */
	ConditionalOrderModificationExecutionResponse toResponse() {
		return new ConditionalOrderModificationExecutionResponse(
				executionId, previewId, accountSeq, originalConditionalOrderId,
				replacementConditionalOrderId, brokerMode, status, failureType,
				createdAt, updatedAt, submittedAt, completedAt);
	}
}

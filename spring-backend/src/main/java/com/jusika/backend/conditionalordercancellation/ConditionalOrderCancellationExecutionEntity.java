package com.jusika.backend.conditionalordercancellation;

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

/** 조건 주문 취소 한 건의 제출 상태를 데이터베이스에 보관합니다. */
@Entity
@Table(
		name = "conditional_order_cancellation_executions",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_conditional_cancel_target",
				columnNames = {"account_seq", "conditional_order_id"}))
class ConditionalOrderCancellationExecutionEntity {

	@Id
	@Column(name = "execution_id", nullable = false, length = 36)
	private String executionId;

	@Column(name = "preview_id", nullable = false, unique = true, length = 36)
	private String previewId;

	@Column(name = "account_seq", nullable = false)
	private long accountSeq;

	@Column(name = "conditional_order_id", nullable = false, length = 512)
	private String conditionalOrderId;

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

	/** JPA가 저장된 실행 행을 복원할 때 사용하는 빈 생성자입니다. */
	protected ConditionalOrderCancellationExecutionEntity() {
	}

	/** 조건 주문 취소 실행 응답의 모든 값을 새 저장 객체에 복사합니다. */
	private ConditionalOrderCancellationExecutionEntity(
			ConditionalOrderCancellationExecutionResponse response) {
		this.executionId = response.executionId();
		this.previewId = response.previewId();
		this.accountSeq = response.accountSeq();
		this.conditionalOrderId = response.conditionalOrderId();
		this.brokerMode = response.brokerMode();
		this.status = response.status();
		this.failureType = response.failureType();
		this.createdAt = response.createdAt();
		this.updatedAt = response.updatedAt();
		this.submittedAt = response.submittedAt();
		this.completedAt = response.completedAt();
	}

	/** 실행 응답을 데이터베이스 저장 객체로 변환합니다. */
	static ConditionalOrderCancellationExecutionEntity from(
			ConditionalOrderCancellationExecutionResponse response) {
		return new ConditionalOrderCancellationExecutionEntity(response);
	}

	/** 데이터베이스에 저장된 실행 상태를 API 응답으로 변환합니다. */
	ConditionalOrderCancellationExecutionResponse toResponse() {
		return new ConditionalOrderCancellationExecutionResponse(
				executionId, previewId, accountSeq, conditionalOrderId, brokerMode,
				status, failureType, createdAt, updatedAt, submittedAt, completedAt);
	}
}

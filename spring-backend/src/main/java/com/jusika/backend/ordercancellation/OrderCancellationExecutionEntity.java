package com.jusika.backend.ordercancellation;

import java.time.OffsetDateTime;

import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 취소 요청 한 건의 제출 상태를 데이터베이스에 보관합니다. */
@Entity
@Table(name = "order_cancellation_executions")
class OrderCancellationExecutionEntity {

	@Id
	@Column(name = "execution_id", nullable = false, length = 36)
	private String executionId;

	@Column(name = "preview_id", nullable = false, unique = true, length = 36)
	private String previewId;

	@Column(name = "order_id", nullable = false, unique = true, length = 512)
	private String orderId;

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

	/** JPA가 저장된 행을 복원할 때 사용하는 빈 생성자입니다. */
	protected OrderCancellationExecutionEntity() {
	}

	/** 취소 실행 응답의 값을 새 저장 객체에 복사합니다. */
	private OrderCancellationExecutionEntity(OrderCancellationExecutionResponse response) {
		this.executionId = response.executionId();
		this.previewId = response.previewId();
		this.orderId = response.orderId();
		this.brokerMode = response.brokerMode();
		this.status = response.status();
		this.failureType = response.failureType();
		this.createdAt = response.createdAt();
		this.updatedAt = response.updatedAt();
		this.submittedAt = response.submittedAt();
		this.completedAt = response.completedAt();
	}

	/** 취소 실행 응답을 데이터베이스 저장 객체로 변환합니다. */
	static OrderCancellationExecutionEntity from(OrderCancellationExecutionResponse response) {
		return new OrderCancellationExecutionEntity(response);
	}

	/** 저장된 취소 실행 상태를 API 응답으로 변환합니다. */
	OrderCancellationExecutionResponse toResponse() {
		return new OrderCancellationExecutionResponse(
				executionId, previewId, orderId, brokerMode, status, failureType,
				createdAt, updatedAt, submittedAt, completedAt);
	}
}

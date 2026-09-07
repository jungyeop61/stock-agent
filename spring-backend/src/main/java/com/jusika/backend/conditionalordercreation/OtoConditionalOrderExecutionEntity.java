package com.jusika.backend.conditionalordercreation;

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

/** OTO 조건 주문 미리보기 한 건의 제출 상태를 데이터베이스에 보관합니다. */
@Entity
@Table(name = "oto_conditional_order_executions")
class OtoConditionalOrderExecutionEntity {

	@Id
	@Column(name = "execution_id", nullable = false, length = 36)
	private String executionId;
	@Column(name = "preview_id", nullable = false, unique = true, length = 36)
	private String previewId;
	@Column(name = "client_order_id", nullable = false, unique = true, length = 36)
	private String clientOrderId;
	@Column(name = "conditional_order_id", length = 512)
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

	/** JPA가 저장 행을 객체로 복원할 때 사용할 빈 생성자입니다. */
	protected OtoConditionalOrderExecutionEntity() {
	}

	/** OTO 실행 준비 응답의 모든 값을 새 저장 객체에 기록합니다. */
	private OtoConditionalOrderExecutionEntity(OtoConditionalOrderExecutionResponse response) {
		this.executionId = response.executionId();
		this.previewId = response.previewId();
		this.clientOrderId = response.clientOrderId();
		this.conditionalOrderId = response.conditionalOrderId();
		this.brokerMode = response.brokerMode();
		this.status = response.status();
		this.failureType = response.failureType();
		this.createdAt = response.createdAt();
		this.updatedAt = response.updatedAt();
		this.submittedAt = response.submittedAt();
		this.completedAt = response.completedAt();
	}

	/** OTO 실행 준비 응답을 데이터베이스 저장 객체로 변환합니다. */
	static OtoConditionalOrderExecutionEntity from(OtoConditionalOrderExecutionResponse response) {
		return new OtoConditionalOrderExecutionEntity(response);
	}

	/** 데이터베이스 값을 OTO 조건 주문 실행 응답으로 변환합니다. */
	OtoConditionalOrderExecutionResponse toResponse() {
		return new OtoConditionalOrderExecutionResponse(
				executionId, previewId, clientOrderId, conditionalOrderId, brokerMode,
				status, failureType, createdAt, updatedAt, submittedAt, completedAt);
	}
}

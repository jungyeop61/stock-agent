package com.jusika.backend.amountorderexecution;

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

/**
 * 금액 주문 미리보기 한 건의 제출 상태와 비공개 요청 지문을 데이터베이스에 보관합니다.
 */
@Entity
@Table(name = "amount_order_executions")
class AmountOrderExecutionEntity {

	@Id
	@Column(name = "execution_id", nullable = false, length = 36)
	private String executionId;

	@Column(name = "preview_id", nullable = false, unique = true, length = 36)
	private String previewId;

	@Column(name = "client_order_id", nullable = false, unique = true, length = 36)
	private String clientOrderId;

	@Column(name = "broker_mode", nullable = false, length = 16)
	private String brokerMode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OrderExecutionStatus status;

	@Column(name = "broker_order_id", length = 128)
	private String brokerOrderId;

	@Enumerated(EnumType.STRING)
	@Column(name = "failure_type", length = 32)
	private OrderExecutionFailureType failureType;

	@Column(name = "request_fingerprint", nullable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "submitted_at")
	private OffsetDateTime submittedAt;

	@Column(name = "recovery_attempted_at")
	private OffsetDateTime recoveryAttemptedAt;

	@Column(name = "completed_at")
	private OffsetDateTime completedAt;

	@Version
	@Column(nullable = false)
	private long version;

	/**
	 * JPA가 데이터베이스 행을 객체로 복원할 때 사용할 빈 생성자입니다.
	 */
	protected AmountOrderExecutionEntity() {
	}

	/**
	 * 실행 준비 응답과 요청 지문을 새 저장 객체에 복사합니다.
	 *
	 * @param response 저장할 금액 주문 실행 준비 응답
	 * @param requestFingerprint 외부 API에 노출하지 않을 최초 주문 지문
	 */
	private AmountOrderExecutionEntity(
			AmountOrderExecutionResponse response,
			String requestFingerprint) {
		this.executionId = response.executionId();
		this.previewId = response.previewId();
		this.clientOrderId = response.clientOrderId();
		this.brokerMode = response.brokerMode();
		this.status = response.status();
		this.brokerOrderId = response.brokerOrderId();
		this.failureType = response.failureType();
		this.requestFingerprint = requestFingerprint;
		this.createdAt = response.createdAt();
		this.updatedAt = response.updatedAt();
		this.submittedAt = response.submittedAt();
		this.recoveryAttemptedAt = response.recoveryAttemptedAt();
		this.completedAt = response.completedAt();
	}

	/**
	 * 실행 준비 응답을 데이터베이스 저장 객체로 변환합니다.
	 *
	 * @param response 저장할 금액 주문 실행 준비 응답
	 * @param requestFingerprint 외부 API에 노출하지 않을 최초 주문 지문
	 * @return 금액 주문 실행 저장 객체
	 */
	static AmountOrderExecutionEntity from(
			AmountOrderExecutionResponse response,
			String requestFingerprint) {
		return new AmountOrderExecutionEntity(response, requestFingerprint);
	}

	/**
	 * 데이터베이스에 저장된 상태를 외부 응답으로 변환합니다.
	 *
	 * @return 요청 지문을 제외한 금액 주문 실행 응답
	 */
	AmountOrderExecutionResponse toResponse() {
		return new AmountOrderExecutionResponse(
				executionId,
				previewId,
				clientOrderId,
				brokerMode,
				status,
				brokerOrderId,
				failureType,
				createdAt,
				updatedAt,
				submittedAt,
				recoveryAttemptedAt,
				completedAt);
	}

	/**
	 * 안전 복구 검증에만 사용할 최초 금액 주문 요청 지문을 반환합니다.
	 *
	 * @return 외부 API에는 공개하지 않는 요청 지문
	 */
	String requestFingerprint() {
		return requestFingerprint;
	}
}

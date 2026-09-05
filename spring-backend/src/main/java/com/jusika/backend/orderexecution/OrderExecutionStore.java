package com.jusika.backend.orderexecution;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 주문 실행권을 한 번만 확보하고 실행 상태를 조건부로 변경하는 저장소 경계입니다.
 */
public interface OrderExecutionStore {

	/**
	 * 미리보기 행을 잠근 뒤 기존 실행이 없을 때만 실행 준비 기록을 만듭니다.
	 *
	 * @param execution 저장할 실행 준비 기록
	 * @param requestFingerprint 계좌와 최초 주문 본문을 함께 계산한 변경 감지용 지문
	 * @return 이번 호출이 실행권을 확보했으면 true
	 */
	boolean claim(OrderExecutionResponse execution, String requestFingerprint);

	/**
	 * 실행 준비 상태를 증권사 제출 중 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param submittedAt 제출 시작 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean markSubmitting(String executionId, OffsetDateTime submittedAt);

	/**
	 * 제출 전에 내부 상태가 어긋난 실행 준비 기록을 안전하게 종료합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 내부 상태 오류를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean markPreparationFailed(String executionId, OffsetDateTime failedAt);

	/**
	 * 제출 중 실행을 증권사 접수 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param brokerOrderId 증권사가 반환한 주문 식별값
	 * @param completedAt 접수를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean markAccepted(String executionId, String brokerOrderId, OffsetDateTime completedAt);

	/**
	 * 제출 중 실행을 확정 거절 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 거절을 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean markRejected(String executionId, OffsetDateTime failedAt);

	/**
	 * 제출 중 실행을 주문 접수 여부 불명 상태로 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 불명 상태를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean markUnknown(String executionId, OffsetDateTime failedAt);

	/**
	 * 안전 복구에 필요한 실행 상태와 외부에 노출하지 않는 요청 지문을 함께 조회합니다.
	 *
	 * @param executionId 조회할 실행 식별값
	 * @return 복구 후보이며 없으면 빈 값
	 */
	Optional<OrderExecutionRecoveryCandidate> findRecoveryCandidateById(String executionId);

	/**
	 * 10분 안의 최초 결과 불명 실행만 안전 복구 중 상태로 한 번 변경합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param submittedAfter 이 시각보다 나중에 제출한 실행만 허용하는 하한선
	 * @param recoveryStartedAt 복구를 시작한 현재 시각이자 제출 시각의 상한선
	 * @return 이번 호출이 복구권을 확보했으면 true
	 */
	boolean claimRecovery(
			String executionId,
			OffsetDateTime submittedAfter,
			OffsetDateTime recoveryStartedAt);

	/**
	 * 복구 중 실행을 증권사 접수 상태로 확정합니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param brokerOrderId 복구 응답으로 회수한 증권사 주문 식별값
	 * @param completedAt 접수를 확인한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean markRecovered(String executionId, String brokerOrderId, OffsetDateTime completedAt);

	/**
	 * 복구 응답도 불확실한 실행을 재복구할 수 없는 결과 불명 상태로 되돌립니다.
	 *
	 * @param executionId 변경할 실행 식별값
	 * @param failedAt 복구 결과를 확정하지 못한 시각
	 * @return 이번 호출이 상태를 변경했으면 true
	 */
	boolean markRecoveryUnknown(String executionId, OffsetDateTime failedAt);

	/**
	 * 실행 식별값으로 저장된 주문 실행 기록을 조회합니다.
	 *
	 * @param executionId 조회할 실행 식별값
	 * @return 저장된 주문 실행 기록이며 없으면 빈 값
	 */
	Optional<OrderExecutionResponse> findById(String executionId);

	/**
	 * 미리보기 식별값으로 기존 주문 실행 기록을 조회합니다.
	 *
	 * @param previewId 조회할 미리보기 식별값
	 * @return 저장된 주문 실행 기록이며 없으면 빈 값
	 */
	Optional<OrderExecutionResponse> findByPreviewId(String previewId);
}

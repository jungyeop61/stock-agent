package com.jusika.backend.amountorderexecution;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;

/**
 * 승인된 금액 주문의 MOCK 실행과 저장 결과 조회 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/orders/amount")
@RequiresInternalApiAuthority(InternalApiAuthority.ORDER)
public class AmountOrderExecutionController {

	private final AmountOrderExecutionService executionService;
	private final AmountOrderRecoveryService recoveryService;

	/**
	 * 금액 주문의 최종 재검증과 MOCK 실행을 담당하는 서비스를 전달받습니다.
	 *
	 * @param executionService 금액 주문 실행 서비스
	 * @param recoveryService 결과 불명 금액 주문의 안전 복구 서비스
	 */
	public AmountOrderExecutionController(
			AmountOrderExecutionService executionService,
			AmountOrderRecoveryService recoveryService) {
		this.executionService = executionService;
		this.recoveryService = recoveryService;
	}

	/**
	 * 승인된 금액 주문 미리보기를 실행 직전 다시 검사하고 MOCK으로 한 번만 실행합니다.
	 *
	 * @param previewId 실행할 금액 주문 미리보기 식별값
	 * @return 데이터베이스에 저장된 MOCK 실행 결과
	 */
	@PostMapping("/previews/{previewId}/execute")
	public AmountOrderExecutionResponse executePreview(@PathVariable String previewId) {
		return executionService.executeApprovedPreview(previewId);
	}

	/**
	 * 외부 주문 호출이나 상태 변경 없이 저장된 금액 주문 실행 결과를 조회합니다.
	 *
	 * @param executionId 조회할 금액 주문 실행 식별값
	 * @return 데이터베이스에 저장된 실행 결과
	 */
	@GetMapping("/executions/{executionId}")
	@RequiresInternalApiAuthority(InternalApiAuthority.READ)
	public AmountOrderExecutionResponse getExecution(@PathVariable String executionId) {
		return executionService.getExecution(executionId);
	}

	/**
	 * 최초 제출 결과가 불명확한 금액 주문의 기존 모의 주문번호를 같은 요청으로 한 번 회수합니다.
	 *
	 * @param executionId 안전 복구할 금액 주문 실행 식별값
	 * @return 복구 뒤 데이터베이스에 저장된 금액 주문 실행 기록
	 */
	@PostMapping("/executions/{executionId}/recover")
	public AmountOrderExecutionResponse recoverExecution(@PathVariable String executionId) {
		return recoveryService.recoverUnknownExecution(executionId);
	}
}

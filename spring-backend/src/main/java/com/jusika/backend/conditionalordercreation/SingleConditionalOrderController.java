package com.jusika.backend.conditionalordercreation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 단일 조건 주문의 미리보기·승인·모의 실행·실행 결과 조회 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/conditional-orders/single")
public class SingleConditionalOrderController {

	private final SingleConditionalOrderService service;

	/**
	 * 단일 조건 주문 검증과 안전한 실행을 담당하는 서비스를 전달받습니다.
	 *
	 * @param service 단일 조건 주문 서비스
	 */
	public SingleConditionalOrderController(SingleConditionalOrderService service) {
		this.service = service;
	}

	/**
	 * 입력 조건과 계좌 여력을 확인하고 실제 주문 없이 미리보기를 만듭니다.
	 *
	 * @param request 사용자가 확인하려는 단일 조건 주문
	 * @return 데이터베이스에 저장된 변경 불가 미리보기
	 */
	@PostMapping("/preview")
	public SingleConditionalOrderPreviewResponse createPreview(
			@RequestBody SingleConditionalOrderPreviewRequest request) {
		return service.createPreview(request);
	}

	/**
	 * 저장된 미리보기 내용을 변경하지 않고 사용자 승인을 기록합니다.
	 *
	 * @param previewId 승인할 미리보기 식별값
	 * @return 승인 상태와 시각이 반영된 미리보기
	 */
	@PostMapping("/previews/{previewId}/approve")
	public SingleConditionalOrderPreviewResponse approvePreview(
			@PathVariable String previewId) {
		return service.approvePreview(previewId);
	}

	/**
	 * 승인된 미리보기를 최종 재검증하고 현재 단계의 MOCK 모드에서 한 번 실행합니다.
	 *
	 * @param previewId 실행할 미리보기 식별값
	 * @return 저장된 조건 주문 실행 상태
	 */
	@PostMapping("/previews/{previewId}/execute")
	public SingleConditionalOrderExecutionResponse executePreview(
			@PathVariable String previewId) {
		return service.executeApprovedPreview(previewId);
	}

	/**
	 * 실행 식별값으로 저장된 조건 주문 실행 결과를 읽기 전용으로 조회합니다.
	 *
	 * @param executionId 조회할 실행 식별값
	 * @return 데이터베이스에 저장된 실행 결과
	 */
	@GetMapping("/executions/{executionId}")
	public SingleConditionalOrderExecutionResponse getExecution(
			@PathVariable String executionId) {
		return service.getExecution(executionId);
	}
}

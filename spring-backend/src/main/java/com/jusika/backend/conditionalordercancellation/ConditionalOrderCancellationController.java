package com.jusika.backend.conditionalordercancellation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 조건 주문 취소의 미리보기·승인·모의 실행·결과 조회 주소를 제공합니다. */
@RestController
@RequestMapping("/api/conditional-orders/cancellations")
public class ConditionalOrderCancellationController {

	private final ConditionalOrderCancellationService service;

	/** 조건 주문 취소 안전 흐름을 담당하는 서비스를 전달받습니다. */
	public ConditionalOrderCancellationController(ConditionalOrderCancellationService service) {
		this.service = service;
	}

	/** 실제 취소 없이 최신 조건 주문을 조회해 승인용 미리보기를 만듭니다. */
	@PostMapping("/preview")
	public ConditionalOrderCancellationPreviewResponse createPreview(
			@RequestBody ConditionalOrderCancellationPreviewRequest request) {
		return service.createPreview(request);
	}

	/** 저장된 조건 주문 내용을 바꾸지 않고 유효한 미리보기만 승인합니다. */
	@PostMapping("/previews/{previewId}/approve")
	public ConditionalOrderCancellationPreviewResponse approvePreview(
			@PathVariable String previewId) {
		return service.approvePreview(previewId);
	}

	/** 승인된 미리보기를 최종 재검증한 뒤 현재 모드에서 한 번 취소합니다. */
	@PostMapping("/previews/{previewId}/execute")
	public ConditionalOrderCancellationExecutionResponse executePreview(
			@PathVariable String previewId) {
		return service.executeApprovedPreview(previewId);
	}

	/** 저장된 조건 주문 취소 실행 결과를 읽기 전용으로 조회합니다. */
	@GetMapping("/executions/{executionId}")
	public ConditionalOrderCancellationExecutionResponse getExecution(
			@PathVariable String executionId) {
		return service.getExecution(executionId);
	}
}

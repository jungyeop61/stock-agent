package com.jusika.backend.ordercancellation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문 취소 미리보기·승인·실행·조회 HTTP 주소를 제공합니다. */
@RestController
@RequestMapping("/api/orders/cancellations")
public class OrderCancellationController {

	private final OrderCancellationService service;

	/** 취소 안전 흐름을 담당하는 서비스를 전달받습니다. */
	public OrderCancellationController(OrderCancellationService service) {
		this.service = service;
	}

	/** 실제 취소 없이 현재 주문 상태를 확인해 승인용 미리보기를 만듭니다. */
	@PostMapping("/preview")
	public OrderCancellationPreviewResponse createPreview(
			@RequestBody OrderCancellationPreviewRequest request) {
		return service.createPreview(request);
	}

	/** 저장된 취소 대상을 변경하지 않고 유효한 미리보기만 승인합니다. */
	@PostMapping("/previews/{previewId}/approve")
	public OrderCancellationPreviewResponse approvePreview(@PathVariable String previewId) {
		return service.approvePreview(previewId);
	}

	/** 승인된 취소 미리보기를 재검증한 뒤 현재 모드로 한 번 실행합니다. */
	@PostMapping("/previews/{previewId}/execute")
	public OrderCancellationExecutionResponse executePreview(@PathVariable String previewId) {
		return service.executeApprovedPreview(previewId);
	}

	/** 저장된 취소 실행 결과를 읽기 전용으로 조회합니다. */
	@GetMapping("/executions/{executionId}")
	public OrderCancellationExecutionResponse getExecution(@PathVariable String executionId) {
		return service.getExecution(executionId);
	}
}

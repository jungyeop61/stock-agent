package com.jusika.backend.ordermodification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문 정정 미리보기·승인·실행·조회 HTTP 주소를 제공합니다. */
@RestController
@RequestMapping("/api/orders/modifications")
public class OrderModificationController {
	private final OrderModificationService service;

	/** 주문 정정 안전 흐름을 담당하는 서비스를 전달받습니다. */
	public OrderModificationController(OrderModificationService service) { this.service = service; }
	/** 실제 정정 없이 원주문과 요청한 변경값을 승인용 사본으로 만듭니다. */
	@PostMapping("/preview")
	public OrderModificationPreviewResponse createPreview(
			@RequestBody OrderModificationPreviewRequest request) { return service.createPreview(request); }
	/** 저장된 정정 내용을 바꾸지 않고 유효한 미리보기만 승인합니다. */
	@PostMapping("/previews/{previewId}/approve")
	public OrderModificationPreviewResponse approvePreview(@PathVariable String previewId) {
		return service.approvePreview(previewId);
	}
	/** 승인한 정정을 실행 직전 재검증한 뒤 현재 모드로 한 번 실행합니다. */
	@PostMapping("/previews/{previewId}/execute")
	public OrderModificationExecutionResponse executePreview(@PathVariable String previewId) {
		return service.executeApprovedPreview(previewId);
	}
	/** 저장된 정정 실행 결과를 읽기 전용으로 조회합니다. */
	@GetMapping("/executions/{executionId}")
	public OrderModificationExecutionResponse getExecution(@PathVariable String executionId) {
		return service.getExecution(executionId);
	}
}

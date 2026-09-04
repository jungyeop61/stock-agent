package com.jusika.backend.orderpreview;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실제 주문을 보내지 않고 주문 가능 여부와 예상 금액을 확인할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderPreviewController {

	private final OrderPreviewService orderPreviewService;

	/**
	 * 주문 검증과 예상 계산을 담당하는 서비스를 전달받습니다.
	 *
	 * @param orderPreviewService 주문 미리보기 서비스
	 */
	public OrderPreviewController(OrderPreviewService orderPreviewService) {
		this.orderPreviewService = orderPreviewService;
	}

	/**
	 * 요청 본문을 검증하고 토스증권에 실제 주문을 전송하지 않은 미리보기를 반환합니다.
	 *
	 * @param request 사용자가 확인하려는 수량 기반 주문 내용
	 * @return 계좌 조건 검증과 예상 비용 계산을 마친 주문 미리보기
	 */
	@PostMapping("/preview")
	public OrderPreviewResponse previewOrder(@RequestBody OrderPreviewRequest request) {
		return orderPreviewService.createPreview(request);
	}

	/**
	 * 저장된 미리보기 식별값만 받아 해당 내용을 변경하지 않고 사용자 승인을 기록합니다.
	 * 이 함수는 토스증권 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param previewId 승인할 주문 미리보기 식별값
	 * @return 승인 시각과 승인 상태가 반영된 저장된 주문 미리보기
	 */
	@PostMapping("/previews/{previewId}/approve")
	public OrderPreviewResponse approvePreview(@PathVariable String previewId) {
		return orderPreviewService.approvePreview(previewId);
	}
}

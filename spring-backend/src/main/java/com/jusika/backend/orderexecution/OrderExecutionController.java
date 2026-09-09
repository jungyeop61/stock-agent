package com.jusika.backend.orderexecution;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;

/**
 * 모바일 앱과 에이전트가 주문 실행 기록을 조회하고 결과 불명 주문을 복구할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/orders/executions")
@RequiresInternalApiAuthority(InternalApiAuthority.ORDER)
public class OrderExecutionController {

	private final OrderExecutionService orderExecutionService;
	private final OrderRecoveryService orderRecoveryService;

	/**
	 * 저장된 주문 실행 상태를 조회하는 서비스를 전달받습니다.
	 *
	 * @param orderExecutionService 주문 실행과 조회를 담당하는 서비스
	 * @param orderRecoveryService 결과 불명 주문의 안전 복구를 담당하는 서비스
	 */
	public OrderExecutionController(
			OrderExecutionService orderExecutionService,
			OrderRecoveryService orderRecoveryService) {
		this.orderExecutionService = orderExecutionService;
		this.orderRecoveryService = orderRecoveryService;
	}

	/**
	 * 실행 식별값으로 우리 데이터베이스의 주문 실행 상태를 조회합니다.
	 * 이 함수는 토스증권을 호출하거나 주문 상태를 변경하지 않습니다.
	 *
	 * @param executionId 우리 서버가 만든 주문 실행 식별값
	 * @return 저장된 모의 또는 향후 실제 주문 실행 기록
	 */
	@GetMapping("/{executionId}")
	@RequiresInternalApiAuthority(InternalApiAuthority.READ)
	public OrderExecutionResponse getExecution(@PathVariable String executionId) {
		return orderExecutionService.getExecution(executionId);
	}

	/**
	 * 최초 제출 결과가 불명확한 주문의 기존 주문번호를 같은 요청으로 한 번만 회수합니다.
	 *
	 * @param executionId 우리 서버가 만든 주문 실행 식별값
	 * @return 복구 뒤 데이터베이스에 저장된 주문 실행 기록
	 */
	@PostMapping("/{executionId}/recover")
	public OrderExecutionResponse recoverExecution(@PathVariable String executionId) {
		return orderRecoveryService.recoverUnknownExecution(executionId);
	}
}

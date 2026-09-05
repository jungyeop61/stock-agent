package com.jusika.backend.orderexecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import com.jusika.backend.order.QuantityOrderSubmissionRequest;

/**
 * 계좌와 수량 주문 본문 전체로 변경 감지용 SHA-256 지문을 계산합니다.
 */
@Component
public class OrderRequestFingerprint {

	private static final String FORMAT_VERSION = "quantity-order-v1";

	/**
	 * 복구 요청이 최초 제출 요청과 완전히 같은지 비교할 결정적인 지문을 만듭니다.
	 *
	 * @param accountSeq 주문에 사용한 계좌 식별값
	 * @param request 최초 제출 또는 복구할 수량 주문 본문
	 * @return 64자리 소문자 16진수 SHA-256 지문
	 */
	public String calculate(long accountSeq, QuantityOrderSubmissionRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("주문 요청이 필요합니다.");
		}
		String canonical = String.join("\u0000",
				FORMAT_VERSION,
				Long.toString(accountSeq),
				required(request.clientOrderId()),
				required(request.symbol()),
				requiredName(request.side()),
				requiredName(request.orderType()),
				requiredName(request.timeInForce()),
				requiredDecimal(request.quantity()),
				nullableDecimal(request.price()),
				Boolean.toString(request.confirmHighValueOrder()));
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("주문 요청 지문을 계산할 수 없습니다.", exception);
		}
	}

	/**
	 * 필수 문자열이 비어 있지 않은지 확인하고 지문 원문에 사용할 값을 반환합니다.
	 *
	 * @param value 확인할 필수 문자열
	 * @return 비어 있지 않은 원본 문자열
	 */
	private String required(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("주문 요청의 필수 문자열이 비어 있습니다.");
		}
		return value;
	}

	/**
	 * 필수 열거형 값을 이름 문자열로 변환합니다.
	 *
	 * @param value 확인할 필수 열거형 값
	 * @return 열거형 이름
	 */
	private String requiredName(Enum<?> value) {
		if (value == null) {
			throw new IllegalArgumentException("주문 요청의 필수 분류값이 비어 있습니다.");
		}
		return value.name();
	}

	/**
	 * 필수 숫자를 지수 표기 없는 정확한 문자열로 변환합니다.
	 *
	 * @param value 확인할 필수 숫자
	 * @return 소수 자릿수를 보존한 숫자 문자열
	 */
	private String requiredDecimal(java.math.BigDecimal value) {
		if (value == null) {
			throw new IllegalArgumentException("주문 요청의 필수 숫자가 비어 있습니다.");
		}
		return value.toPlainString();
	}

	/**
	 * 선택 가격을 null 표식 또는 지수 표기 없는 정확한 문자열로 변환합니다.
	 *
	 * @param value 변환할 선택 가격
	 * @return null 표식 또는 소수 자릿수를 보존한 숫자 문자열
	 */
	private String nullableDecimal(java.math.BigDecimal value) {
		return value == null ? "<null>" : value.toPlainString();
	}
}

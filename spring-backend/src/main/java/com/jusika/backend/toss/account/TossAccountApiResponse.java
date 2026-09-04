package com.jusika.backend.toss.account;

import java.util.List;

/**
 * 토스증권 계좌 목록 API가 공통 응답 형식 안에 담아 보내는 계좌 목록을 표현합니다.
 *
 * @param result 사용 가능한 계좌 목록
 */
public record TossAccountApiResponse(List<TossAccountItem> result) {
}

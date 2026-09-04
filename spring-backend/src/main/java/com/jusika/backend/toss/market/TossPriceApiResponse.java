package com.jusika.backend.toss.market;

import java.util.List;

/**
 * 토스증권 현재가 API가 공통 응답 형식 안에 담아 보내는 종목 목록을 표현합니다.
 *
 * @param result 조회된 종목별 현재가 목록
 */
public record TossPriceApiResponse(List<TossPriceItem> result) {
}

package com.jusika.backend.amountorderpreview;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 금액 주문 미리보기 행의 기본 저장과 식별값 조회를 제공합니다.
 */
interface AmountOrderPreviewJpaRepository extends JpaRepository<AmountOrderPreviewEntity, String> {
}

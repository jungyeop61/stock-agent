# 스프링 백엔드

금융 거래의 최종 책임을 갖는 Spring Boot 프로젝트입니다.

현재는 환경 설정만 구성되어 있으며 애플리케이션 코드는 아직 없습니다.

## 담당 범위

- 종목과 계좌 정보 검증
- 변경할 수 없는 주문 미리보기 생성
- 사용자 승인 검증
- 중복 주문 방지
- 주문 위험 정책 적용
- 전체 금융 처리 과정 감사 로그 기록
- 토스증권 Open API 연동

실제 증권사 주문 인증정보와 주문 호출 코드는 반드시 이 프로젝트 내부에만 둡니다.

## 환경 확인

Maven Wrapper와 프로젝트 설정이 정상인지 확인합니다.

```bash
./mvnw validate
```

기능 구현을 시작한 뒤 실행 명령과 테스트 명령을 추가합니다.

## PostgreSQL 프로필

PostgreSQL을 사용할 때는 필요한 환경변수를 설정하고 `postgres` 프로필을 활성화합니다.

```bash
export SPRING_PROFILES_ACTIVE=postgres
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/jusika
export SPRING_DATASOURCE_USERNAME=jusika
export SPRING_DATASOURCE_PASSWORD=로컬비밀번호
```

위 설정은 이후 Spring 애플리케이션 코드를 추가할 때 사용합니다.

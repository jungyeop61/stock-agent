# 스프링 백엔드

금융 거래의 최종 책임을 갖는 Spring Boot 프로젝트입니다.

현재는 토스증권 OAuth 인증, 종목 현재가 조회와 계좌 목록 조회까지 구현되어 있습니다.
보유 종목·잔고·주문 기능은 아직 없습니다.

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

애플리케이션을 실행하고 기본 상태를 확인할 수 있습니다.

```bash
./mvnw spring-boot:run
```

실행 후 `http://localhost:8080/actuator/health`에서 상태를 확인합니다.

## 토스증권 인증 테스트

평소 테스트는 실제 토스증권 서버를 호출하지 않고 가짜 HTTP 서버를 사용합니다.

```bash
./mvnw test
```

실제 인증 테스트는 새 액세스 토큰을 발급하므로 사용자가 명시적으로 실행할 때만 동작합니다.
토스증권은 새 토큰을 발급하면 이전 토큰을 무효화하므로 반복 실행하지 않습니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossAuthLiveTests test
```

실제 클라이언트 비밀키와 발급된 액세스 토큰은 로그에 출력하지 않습니다.

## 현재가 조회

서버를 실행한 뒤 종목 코드를 URL에 넣어 현재가를 조회합니다.
삼성전자 종목 코드는 `005930`입니다.

```bash
curl http://localhost:8080/api/stocks/005930/price
```

응답에는 종목 코드, 숫자 형태의 현재가, 통화와 시세 기록 시각이 포함됩니다.

```json
{
  "symbol": "005930",
  "price": 72000,
  "currency": "KRW",
  "timestamp": "2026-09-04T09:30:00.123+09:00"
}
```

평소 자동 테스트는 가짜 토스증권 서버를 사용하므로 실제 토큰을 발급하지 않습니다.
실제 현재가 조회 테스트는 아래 환경변수를 지정했을 때만 실행됩니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossPriceLiveTests test
```

## 계좌 목록 조회

계좌 식별값은 환경변수에 직접 저장하지 않고 토스증권 계좌 목록 API에서 받아옵니다.
서버를 실행한 뒤 다음 주소로 사용 가능한 계좌를 조회합니다.

```bash
curl http://localhost:8080/api/accounts
```

실제 계좌번호는 노출하지 않고 끝 4자리만 남겨 반환합니다.
`accountSeq`는 다음 단계에서 보유주식과 잔고를 조회할 때 사용합니다.

```json
[
  {
    "accountSeq": 1,
    "maskedAccountNumber": "*******8901",
    "accountType": "BROKERAGE"
  }
]
```

실제 계좌 목록 테스트는 아래 환경변수를 지정했을 때만 실행됩니다.
테스트 결과에는 실제 계좌번호를 출력하지 않습니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossAccountLiveTests test
```

## PostgreSQL 프로필

PostgreSQL을 사용할 때는 필요한 환경변수를 설정하고 `postgres` 프로필을 활성화합니다.

```bash
export SPRING_PROFILES_ACTIVE=postgres
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/jusika
export SPRING_DATASOURCE_USERNAME=jusika
export SPRING_DATASOURCE_PASSWORD=로컬비밀번호
```

위 설정은 이후 Spring 애플리케이션 코드를 추가할 때 사용합니다.

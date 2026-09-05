# 스프링 백엔드

금융 거래의 최종 책임을 갖는 Spring Boot 프로젝트입니다.

현재는 토스증권 OAuth 인증, 종목 현재가, 계좌 목록, 보유주식 평가, 매수 가능 금액, 매도 가능 수량과 매매 수수료 조회가 구현되어 있습니다.
수량 기반 주문 미리보기는 데이터베이스 저장, 만료, 사용자 승인, 최종 재검증과 중복 실행 차단까지 구현되어 있습니다.
제출 결과가 불명확한 주문은 최초 요청 지문을 확인한 뒤 10분 안에 같은 내용으로 한 번만 안전 복구할 수 있습니다.
토스증권의 진행 중·종료 주문 목록과 주문 상세·누적 체결 결과를 읽기 전용으로 조회하고, 우리 데이터베이스의 주문 실행 기록도 조회할 수 있습니다.
토스증권 주문 생성 클라이언트는 수량 주문과 미국 주식 금액 주문 형식 및 멱등성 처리를 구현했습니다.
승인된 미리보기의 실행 및 복구 API는 `MOCK` 모의 주문 경계에만 연결되어 있어 실제 주문을 실행할 수는 없습니다.

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

## 보유주식과 평가손익 조회

먼저 계좌 목록에서 받은 `accountSeq`를 URL에 넣어 보유주식을 조회합니다.
다음 명령의 `1`은 설명용 계좌 식별값이며, 코드나 환경변수에 고정하지 않습니다.

```bash
curl http://localhost:8080/api/accounts/1/holdings
```

응답에는 통화별 투자원금·평가금액·손익과 개별 보유 종목이 포함됩니다.
원화와 달러는 환율로 임의 합산하지 않고 각각 반환합니다.
수익률은 `0.1077`이 `10.77%`를 의미하는 소수비율입니다.

```json
{
  "accountSeq": 1,
  "totalPurchaseAmount": {
    "krw": 6500000,
    "usd": null
  },
  "marketValue": {
    "amount": {"krw": 7200000, "usd": null},
    "amountAfterCost": {"krw": 7050000, "usd": null}
  },
  "profitLoss": {
    "amount": {"krw": 700000, "usd": null},
    "amountAfterCost": {"krw": 550000, "usd": null},
    "rate": 0.1077,
    "rateAfterCost": 0.0846
  },
  "dailyProfitLoss": {
    "amount": {"krw": 100000, "usd": null},
    "rate": 0.0141
  },
  "items": [
    {
      "symbol": "005930",
      "name": "삼성전자",
      "marketCountry": "KR",
      "currency": "KRW",
      "quantity": 100,
      "lastPrice": 72000,
      "averagePurchasePrice": 65000
    }
  ]
}
```

위 JSON은 주요 필드만 보여주는 설명용 예시입니다.
실제 응답의 개별 종목에는 평가금액, 손익, 일간손익, 예상 수수료와 세금도 포함됩니다.
이 조회 결과는 보유주식 평가 정보이며 주문에 사용할 수 있는 현금과는 다릅니다.

실제 보유주식 테스트는 계좌 목록에서 `accountSeq`를 선택한 뒤 실행되며 금융값을 출력하지 않습니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossHoldingsLiveTests test
```

## 원화와 달러 매수 가능 금액 조회

계좌 목록에서 받은 `accountSeq`와 조회할 통화를 지정합니다.
다음 명령의 `1`은 설명용 계좌 식별값입니다.

원화 매수 가능 금액을 조회합니다.

```bash
curl "http://localhost:8080/api/accounts/1/buying-power?currency=KRW"
```

달러 매수 가능 금액은 같은 주소에 `USD`를 지정합니다.

```bash
curl "http://localhost:8080/api/accounts/1/buying-power?currency=USD"
```

```json
{
  "accountSeq": 1,
  "currency": "KRW",
  "cashBuyingPower": 5000000
}
```

`cashBuyingPower`는 계좌 총자산이 아니라 미수 없이 순수 현금으로 매수할 수 있는 금액입니다.
원화는 원 단위 정수이고 달러는 소수점이 포함될 수 있으므로 모두 `BigDecimal`로 처리합니다.
우리 서버는 `KRW`와 `USD`만 허용하며 소문자로 입력해도 대문자로 바꿉니다.

실제 연동 테스트는 계좌 목록에서 `accountSeq`를 선택한 뒤 원화와 달러를 모두 조회합니다.
테스트 결과에는 실제 매수 가능 금액을 출력하지 않습니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossBuyingPowerLiveTests test
```

## 종목별 매도 가능 수량 조회

계좌 목록에서 받은 `accountSeq`와 보유주식에서 확인한 종목 코드를 URL에 넣습니다.
다음 명령의 `1`과 `005930`은 각각 설명용 계좌 식별값과 삼성전자 종목 코드입니다.

```bash
curl http://localhost:8080/api/accounts/1/stocks/005930/sellable-quantity
```

```json
{
  "accountSeq": 1,
  "symbol": "005930",
  "sellableQuantity": 80
}
```

`sellableQuantity`는 단순 보유 수량이 아니라 기존 미체결 매도 주문 등을 반영해 지금 새 매도 주문에 사용할 수 있는 수량입니다.
국내 주식은 정수 단위이고 미국 주식은 소수 단위가 가능하므로 `BigDecimal`로 처리합니다.
영문 종목 코드는 대문자로 정규화하며 허용되지 않은 문자가 있으면 토스증권을 호출하기 전에 차단합니다.

실제 연동 테스트는 계좌 목록과 보유주식을 차례로 조회해 첫 보유 종목을 자동으로 선택합니다.
테스트 결과에는 실제 종목이나 보유 수량을 출력하지 않습니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossSellableQuantityLiveTests test
```

## 국내와 미국 시장의 매매 수수료 조회

계좌 목록에서 받은 `accountSeq`를 URL에 넣어 계좌에 실제 적용되는 시장별 수수료율을 조회합니다.
다음 명령의 `1`은 설명용 계좌 식별값입니다.

```bash
curl http://localhost:8080/api/accounts/1/commissions
```

```json
{
  "accountSeq": 1,
  "commissions": [
    {
      "marketCountry": "KR",
      "commissionRate": 0.00015,
      "startDate": "2026-01-01",
      "endDate": "2026-12-31"
    },
    {
      "marketCountry": "US",
      "commissionRate": 0.001,
      "startDate": null,
      "endDate": null
    }
  ]
}
```

`commissionRate`는 퍼센트 숫자가 아니라 매매 금액에 곱하는 소수 비율입니다.
예를 들어 `0.00015`는 `0.015%`를 뜻하며, 오차 없는 계산을 위해 `BigDecimal`로 처리합니다.
`startDate`는 해외주식 등 적용 시작일이 없으면 `null`이고, `endDate`는 무기한 적용이면 `null`입니다.

실제 연동 테스트는 계좌 목록에서 `accountSeq`를 자동으로 선택한 뒤 국내와 미국 시장의 수수료를 조회합니다.
테스트 결과에는 실제 수수료율과 적용 기간을 출력하지 않습니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossCommissionsLiveTests test
```

## 수량 기반 주문 미리보기

주문 미리보기는 현재가, 매수 가능 금액 또는 매도 가능 수량, 계좌 수수료를 조회해 주문 가능 여부와 예상 금액을 계산합니다.
이 기능은 토스증권 주문 생성 API를 호출하지 않으므로 실제 매수나 매도가 발생하지 않습니다.

```bash
curl -X POST http://localhost:8080/api/orders/preview \
  -H "Content-Type: application/json" \
  -d '{
    "accountSeq": 1,
    "symbol": "005930",
    "side": "BUY",
    "orderType": "LIMIT",
    "quantity": 1,
    "price": 70000
  }'
```

```json
{
  "previewId": "임시-미리보기-식별값",
  "createdAt": "2026-09-04T20:00:00Z",
  "expiresAt": "2026-09-04T20:02:00Z",
  "accountSeq": 1,
  "symbol": "005930",
  "side": "BUY",
  "orderType": "LIMIT",
  "quantity": 1,
  "requestedPrice": 70000,
  "referencePrice": 72000,
  "calculationPrice": 70000,
  "currency": "KRW",
  "marketCountry": "KR",
  "commissionRate": 0.00015,
  "estimatedOrderAmount": 70000,
  "estimatedCommission": 10.5,
  "estimatedAmountAfterCommission": 70010.5,
  "sellTaxExcluded": false,
  "requiresHighValueConfirmation": false,
  "orderReady": true,
  "status": "PENDING_APPROVAL",
  "approvedAt": null
}
```

지정가는 입력 가격으로 계산하고 시장가는 조회 시점의 현재가로 계산합니다.
시장가는 실제 체결 가격이 달라질 수 있으므로 미리보기 금액은 보장된 금액이 아닙니다.
매도 미리보기의 `estimatedAmountAfterCommission`에는 예상 수수료만 반영하며 매도 세금은 포함하지 않습니다.
이 경우 `sellTaxExcluded`가 `true`로 반환됩니다.

수량 기반 주문은 토스증권 실제 주문 규칙과 동일하게 다음 형식을 검사합니다.

- 국내 주식과 일반 미국 주식 주문은 양의 정수 수량만 허용합니다.
- 미국 주식 시장가 매도만 소수점 6자리까지 허용합니다.
- 미국 주식 소수점 매수는 이번 단계에 포함하지 않았으며 이후 금액 주문으로 구현합니다.
- 지정가는 가격이 필수이고 시장가는 가격을 입력할 수 없습니다.
- 국내 지정가는 원 단위 정수여야 합니다.
- 미국 지정가는 1달러 미만이면 소수점 4자리, 1달러 이상이면 소수점 2자리까지 허용합니다.
- 국내 주문금액이 1억원 이상이면 `requiresHighValueConfirmation`이 `true`가 됩니다.

`previewId`는 데이터베이스에 저장된 미리보기를 구분하는 UUID 식별값입니다.
기본 승인 유효시간은 생성 시각부터 2분이며 `.env`의 `ORDER_PREVIEW_EXPIRATION`으로 바꿀 수 있습니다.
로컬 기본 H2 데이터베이스의 내용은 서버를 종료하면 사라지고, `postgres` 프로필에서는 PostgreSQL에 유지됩니다.
`orderReady`는 조회 시점의 입력 형식과 계좌 금액 또는 수량 검사를 통과했다는 뜻이며 증권사의 최종 주문 접수를 보장하지 않습니다.
호가 단위, 주문 가능 시간, 종목 거래 제한과 미리보기 이후의 가격·잔고 변동은 실제 주문 직전에 다시 검사해야 합니다.

## 주문 미리보기 승인

미리보기 응답에서 받은 `previewId`만 URL에 넣어 승인합니다.
승인 요청은 주문 수량이나 가격을 본문으로 받지 않으므로 저장된 주문 내용을 바꿀 수 없습니다.

```bash
curl -X POST http://localhost:8080/api/orders/previews/미리보기-식별값/approve
```

성공하면 저장된 미리보기 전체가 다시 반환되고 `status`는 `APPROVED`, `approvedAt`은 승인 시각이 됩니다.
이번 단계의 승인은 서버 데이터베이스 상태만 변경하며 토스증권 주문 생성 API를 호출하지 않습니다.

다음 상태 규칙을 적용합니다.

- 처음 만든 미리보기는 `PENDING_APPROVAL` 상태입니다.
- 유효시간 안에 한 번만 `APPROVED`로 변경할 수 있습니다.
- 유효시간이 지나면 `EXPIRED`로 변경되며 HTTP 410을 반환합니다.
- 같은 미리보기를 다시 승인하면 HTTP 409를 반환합니다.
- 존재하지 않는 미리보기는 HTTP 404를 반환합니다.
- 모의 실행권을 확보한 미리보기는 `CONSUMED`가 되어 다시 실행할 수 없습니다.

실제 연동 테스트는 매도 가능한 보유 종목을 자동으로 선택해 읽기 전용 미리보기까지만 생성합니다.
토스증권 주문 생성 API는 호출하지 않으며 실제 종목과 금융값도 출력하지 않습니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=OrderPreviewLiveTests test
```

## 승인된 주문 미리보기 모의 실행

승인된 미리보기 식별값을 사용해 주문 직전 조건을 다시 확인하고 모의 주문을 실행합니다.
현재 설정은 `MOCK` 전용이며 토스증권 주문 생성 API를 호출하지 않습니다.

```bash
curl -X POST http://localhost:8080/api/orders/previews/미리보기-식별값/execute
```

실행 직전에는 다음 값을 새로 조회합니다.

- 종목 현재가와 승인된 통화·시장 코드의 일치 여부
- 계좌에 적용되는 시장별 매매 수수료
- 매수 주문의 현재 매수 가능 금액
- 매도 주문의 현재 매도 가능 수량
- 국내 1억원 이상 주문의 추가 확인 여부

모든 조건을 통과하면 실행 기록과 고유한 `clientOrderId`를 만들고 미리보기를 `CONSUMED`로 변경합니다.
같은 미리보기에는 실행 기록을 하나만 만들 수 있으므로 중복 요청은 실제 주문 경계에 도달하지 않습니다.

```json
{
  "executionId": "서버-실행-식별값",
  "previewId": "미리보기-식별값",
  "clientOrderId": "중복-방지-식별값",
  "brokerMode": "MOCK",
  "status": "ACCEPTED",
  "brokerOrderId": "모의-주문-식별값",
  "failureType": null,
  "createdAt": "2026-09-04T21:00:00Z",
  "updatedAt": "2026-09-04T21:00:00Z",
  "submittedAt": "2026-09-04T21:00:00Z",
  "recoveryAttemptedAt": null,
  "completedAt": "2026-09-04T21:00:00Z"
}
```

실행 상태는 다음 의미를 가집니다.

- `PREPARED`: 데이터베이스에서 이 미리보기의 실행권을 확보했습니다.
- `SUBMITTING`: 주문 제출 경계 호출을 시작했습니다.
- `RECOVERING`: 최초 주문과 같은 내용으로 기존 주문번호 회수를 한 번 시도하고 있습니다.
- `ACCEPTED`: 모의 증권사가 주문을 접수했습니다. 체결 완료를 뜻하지 않습니다.
- `REJECTED`: 제출 전 내부 상태 오류 또는 증권사의 확정 거절입니다.
- `UNKNOWN`: 요청 전송 후 접수 여부를 확정할 수 없어 자동 재시도하면 안 됩니다.

현재는 성공 경로도 `brokerMode`가 항상 `MOCK`입니다.
`OrderExecutionService`와 실제 `TossOrderClient` 사이에는 연결 코드가 없으며 실제 주문 라이브 테스트도 없습니다.

모의 실행 서비스와 데이터베이스 상태 변경은 다음 명령으로 각각 검사할 수 있습니다.

```bash
./mvnw -Dtest=OrderExecutionServiceTests test
./mvnw -Dtest=OrderExecutionPersistenceTests test
```

## 저장된 주문 실행 기록 조회

모의 실행 API가 반환한 `executionId`로 우리 데이터베이스의 실행 기록을 조회합니다.
이 주소는 토스증권을 호출하지 않고 저장된 상태도 변경하지 않습니다.

```bash
curl http://localhost:8080/api/orders/executions/실행-식별값
```

존재하지 않는 실행 식별값은 HTTP 404, UUID 형식이 아닌 값은 HTTP 400을 반환합니다.

## 결과 불명 주문 안전 복구

최초 주문 전송 뒤 응답을 받지 못해 실행 상태가 `UNKNOWN`, 실패 분류가 `SUBMISSION_UNKNOWN`인 경우에만 안전 복구를 요청할 수 있습니다.

```bash
curl -X POST http://localhost:8080/api/orders/executions/실행-식별값/recover
```

복구는 새 주문을 만드는 일반 재시도가 아닙니다.
데이터베이스에 저장한 SHA-256 요청 지문으로 계좌, `clientOrderId`, 종목, 매수·매도 방향, 주문 유형, 유효 조건, 수량, 가격과 고액 주문 확인값이 최초 제출과 모두 같은지 확인합니다.
그다음 다음 조건을 모두 만족할 때만 복구 전용 제출 경계를 한 번 호출합니다.

- 최초 주문 제출 시각부터 10분이 지나지 않았습니다. 정확히 10분이 된 순간부터는 HTTP 410으로 차단합니다.
- 최초 주문과 현재 주문 제출 모드가 같습니다.
- 증권사 주문번호가 아직 없고 복구를 시도한 기록도 없습니다.
- 데이터베이스의 원자적 상태 변경으로 다른 요청이 먼저 복구권을 확보하지 않았습니다.

복구할 때 현재가나 잔고를 다시 조회하지 않습니다.
이미 접수됐을 수 있는 최초 주문의 본문을 바꾸지 않고 동일한 요청을 재전송해야 하기 때문입니다.
복구 응답에서도 기존 `clientOrderId`와 주문번호를 모두 확인하면 상태가 `ACCEPTED`가 되고 `recoveryAttemptedAt`에 시각이 기록됩니다.

복구 응답까지 불명확하면 상태는 다시 `UNKNOWN`, 실패 분류는 `RECOVERY_UNKNOWN`이 됩니다.
이 경우 자동으로 두 번째 복구를 하지 않으며 주문 목록과 토스증권 앱에서 사람이 직접 확인해야 합니다.
마이그레이션 전에 만든 실행 기록은 요청 지문이 없으므로 복구하지 않습니다.

현재 복구 경계도 `MOCK` 모드이며 실제 토스증권 주문 생성 API와 연결되어 있지 않습니다.
따라서 이 API와 자동 테스트는 실제 매수나 매도를 발생시키지 않습니다.

복구 서비스와 데이터베이스·HTTP 상태 변경은 다음 명령으로 검사할 수 있습니다.

```bash
./mvnw -Dtest=OrderRecoveryServiceTests test
./mvnw -Dtest=OrderExecutionPersistenceTests,OrderExecutionControllerTests test
```

## 토스증권 주문 목록 조회

계좌 목록에서 받은 `accountSeq`와 필수 상태값으로 주문 목록을 조회합니다.
이 기능은 토스증권의 읽기 전용 `GET` 요청만 사용하므로 주문 생성·정정·취소가 발생하지 않습니다.

진행 중 주문을 전체 종목과 전체 기간에서 조회합니다.

```bash
curl "http://localhost:8080/api/accounts/1/orders?status=OPEN"
```

진행 중 주문을 종목과 한국 날짜 기준 주문 생성일로 필터링할 수 있습니다.

```bash
curl "http://localhost:8080/api/accounts/1/orders?status=OPEN&symbol=AAPL&from=2026-03-01&to=2026-03-31"
```

종료된 주문은 기본 20건, 최대 100건 단위로 조회합니다.

```bash
curl "http://localhost:8080/api/accounts/1/orders?status=CLOSED&limit=20"
```

`hasNext`가 `true`이면 응답의 `nextCursor`를 변경하지 않고 다음 요청에 전달합니다.
커서에 URL 특수문자가 들어갈 수 있으므로 `curl`에서는 다음처럼 자동 인코딩하는 방식이 안전합니다.

```bash
curl --get "http://localhost:8080/api/accounts/1/orders" \
  --data-urlencode "status=CLOSED" \
  --data-urlencode "limit=20" \
  --data-urlencode "cursor=응답에서-받은-nextCursor"
```

목록 상태와 페이지 규칙은 다음과 같습니다.

- `OPEN`: `PENDING`, `PARTIAL_FILLED`, `PENDING_CANCEL`, `PENDING_REPLACE` 등 진행 중 주문을 전량 반환합니다.
- `OPEN`에서는 잘린 목록으로 오해하지 않도록 `cursor`와 `limit` 입력을 허용하지 않습니다.
- `CLOSED`: 체결·취소·거부·정정 완료 등 종료된 주문을 커서 기반 페이지로 반환합니다.
- `symbol`은 선택값이며 영문 종목은 대문자로 정규화합니다.
- `from`과 `to`는 선택값이며 양 끝 날짜를 모두 포함합니다.
- 시작일이 종료일보다 늦거나 페이지 크기가 1~100 범위를 벗어나면 HTTP 400을 반환합니다.

응답의 `orders` 각 항목에는 주문 상세 조회와 같은 주문·누적 체결 필드가 들어 있습니다.
`nextCursor`와 `hasNext`는 다음 종료 주문 페이지의 존재 여부를 나타냅니다.

주문 목록에는 `clientOrderId`가 포함되지 않으므로 결과 불명 주문을 목록의 비슷한 주문과 자동 연결하지 않습니다.
결과 불명 주문은 위 안전 복구 API에서만 저장된 동일 주문 본문과 동일한 `clientOrderId`를 10분 이내에 한 번 재전송합니다.

가짜 토스증권 서버로 목록 필터·페이지·응답 검증을 검사합니다.

```bash
./mvnw -Dtest=TossOrderListClientTests test
```

실제 연동 테스트는 계좌 목록에서 첫 계좌 식별값을 선택해 진행 중 주문과 종료 주문을 읽기 전용으로 조회합니다.
테스트 결과에는 주문 식별값·종목·수량·가격·금액을 출력하지 않습니다.
새 액세스 토큰 발급으로 기존 토큰이 무효화될 수 있으므로 사용자가 명시적으로 실행할 때만 동작합니다.

```bash
RUN_TOSS_LIVE_TEST=true ./mvnw -Dtest=TossOrderListLiveTests test
```

## 토스증권 주문 상세와 체결 상태 조회

계좌 목록에서 받은 `accountSeq`와 토스증권 주문 생성 응답의 `orderId`로 한 주문을 조회합니다.
읽기 전용 `GET` 요청만 사용하므로 주문 생성·정정·취소가 발생하지 않습니다.

```bash
curl http://localhost:8080/api/accounts/1/orders/토스증권-주문-식별값
```

응답에는 다음 정보가 포함됩니다.

- 주문 종목, 매수·매도 방향, 호가 유형과 유효 조건
- 토스증권 원본 주문 상태와 우리 서버가 해석한 상태
- 주문 수량·가격·통화와 주문 시각
- 누적 체결 수량, 평균 체결가, 체결금액, 수수료와 세금
- 마지막 체결 시각과 결제 예정일

지원하는 주문 상태는 `PENDING`, `PENDING_CANCEL`, `PENDING_REPLACE`, `PARTIAL_FILLED`, `FILLED`, `CANCELED`, `REJECTED`, `CANCEL_REJECTED`, `REPLACE_REJECTED`, `REPLACED`입니다.
토스증권이 새로운 상태 코드를 추가하더라도 서버가 중단되지 않도록 `status`는 `UNKNOWN`으로 반환하고 `brokerStatusCode`에는 원본 코드를 보존합니다.

주문 상세 조회는 `orderId`만 지원합니다.
토스증권 공식 명세에는 `clientOrderId`로 주문을 직접 검색하는 기능이 없습니다.
따라서 응답을 받지 못한 `UNKNOWN` 주문은 목록이나 상세 조회 결과로 추정하지 않습니다.
별도 안전 복구 API가 주문 생성 후 10분 안에 저장된 주문 내용과 동일한 `clientOrderId`로 정확히 같은 요청을 한 번만 보내 기존 `orderId`를 회수합니다.
실제 토스증권 연결은 아직 하지 않았으므로 현재는 모의 주문번호만 회수합니다.

가짜 토스증권 서버로 주문 상세 변환과 오류 처리를 검사합니다.

```bash
./mvnw -Dtest=TossOrderHistoryClientTests test
```

## 토스증권 주문 생성 클라이언트

토스증권 Open API v1.2.14의 `POST /api/v1/orders` 명세에 맞춘 내부 클라이언트를 구현했습니다.
이 클라이언트는 HTTP 컨트롤러, 승인된 미리보기, 모의 실행 서비스와 연결되어 있지 않으므로 애플리케이션 외부에서 실제 주문을 실행할 수 없습니다.

지원하는 주문 형식은 다음과 같습니다.

- 국내·미국 주식의 수량 기반 지정가·시장가 매수와 매도
- 미국 주식 시장가 매도의 소수점 6자리 이하 수량
- 미국 주식의 달러 금액 기반 시장가 주문
- 당일 주문 `DAY`, 장 마감 주문 `CLS`, 장 개시 주문 `OPG`
- 1억원 이상 주문에 사용할 `confirmHighValueOrder`

공식 명세에서 `clientOrderId`는 선택 필드지만, 우리 클라이언트에서는 중복 주문 방지를 위해 반드시 입력하게 했습니다.
최대 36자의 영문·숫자·하이픈·밑줄만 허용하며, 토스증권 성공 응답이 같은 값을 반환하는지도 확인합니다.

주문 생성 성공 응답에는 주문 상태가 포함되지 않고 `orderId`와 `clientOrderId`만 포함됩니다.
따라서 주문 상태를 임의로 추정하지 않으며, 실제 체결·미체결 상태는 이후 주문 상세 조회 기능으로 확인해야 합니다.

HTTP 4xx처럼 주문 거절이 확인된 응답과 다음과 같은 주문 결과 불명 상태를 구분합니다.

- 주문 전송 후 네트워크 연결이 끊긴 경우
- 토스증권이 HTTP 5xx를 반환한 경우
- 성공 응답에 주문 식별값이 없거나 요청과 다른 멱등성 식별값이 포함된 경우

결과 불명 상태에서는 새 멱등성 식별값으로 주문을 다시 보내면 안 됩니다.
구현된 안전 복구 절차는 저장된 주문 내용과 동일한 `clientOrderId`가 모두 일치할 때만 복구 전용 경계를 한 번 호출합니다.
이 경계와 실제 `TossOrderClient`의 연결은 후속 단계에서 별도로 진행합니다.

주문 클라이언트 테스트는 실제 토스증권 서버가 아닌 가짜 HTTP 서버만 사용합니다.

```bash
./mvnw -Dtest=TossOrderClientTests test
```

실제 주문을 생성하는 라이브 테스트는 안전을 위해 만들거나 실행하지 않았습니다.

## PostgreSQL 프로필

PostgreSQL을 사용할 때는 필요한 환경변수를 설정하고 `postgres` 프로필을 활성화합니다.

```bash
export SPRING_PROFILES_ACTIVE=postgres
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/jusika
export SPRING_DATASOURCE_USERNAME=jusika
export SPRING_DATASOURCE_PASSWORD=로컬비밀번호
```

위 설정은 이후 Spring 애플리케이션 코드를 추가할 때 사용합니다.

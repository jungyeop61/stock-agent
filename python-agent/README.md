# 파이썬 에이전트

음성 인식 결과를 구조화된 금융 명령으로 해석하고 Spring 백엔드의 안전한 금융 API를 호출하는 FastAPI와 LangGraph 프로젝트입니다.

현재 MVP는 다음 흐름을 지원합니다.

- 종목 현재가 조회
- 달러·원화 참고 환율 조회와 금액 환산
- 단일 계좌의 보유자산 조회
- 수량 기반 매수·매도 미리보기 생성
- 미국 주식 달러 금액 시장가 매수 미리보기 생성
- 미체결 일반 주문과 감시 중 조건 주문 조회
- 주문번호를 명시한 일반 주문 취소·정정
- 감시가격·수량·방향·만료일을 명시한 단일 조건 주문 생성
- 첫·둘째 감시가격과 주문가격을 명시한 OCO·OTO 조건 주문 생성
- 조건주문번호를 명시한 조건 주문 취소
- 정정 후 전체 구성을 명시한 조건 주문 정정과 유형 전환
- LangGraph `interrupt`를 이용한 사용자 승인 대기
- `승인` 또는 `취소` 응답으로 기존 미리보기만 재개
- 승인된 미리보기의 Spring 승인·실행 호출
- 메모리 또는 PostgreSQL LangGraph 체크포인트
- OpenAI Structured Outputs 또는 오프라인 규칙 기반 명령 해석
- 누락된 주문 정보를 세션 체크포인트에 보존하며 차례로 묻는 다중 턴 대화

Python Agent는 토스증권 API를 직접 호출하지 않습니다. 실제 금융 검증, 주문 승인 상태 변경, 멱등 실행과 증권사 호출은 Spring 백엔드만 담당합니다.

## 환경 준비

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
```

## 실행

Spring 백엔드를 MOCK 모드로 먼저 실행한 다음 Agent를 실행합니다.

```bash
cd spring-backend
./mvnw spring-boot:run
```

```bash
cd python-agent
source .venv/bin/activate
PYTHONPATH=src python -m jusika_agent
```

기본 주소는 `http://127.0.0.1:8000`이며 상태 확인 주소는 `/health`입니다.

## 텍스트 기반 MOCK 흐름

세션 ID는 Android가 한 음성 대화 동안 유지하는 UUID입니다.

```bash
SESSION_ID="11111111-1111-4111-8111-111111111111"

curl -X POST "http://127.0.0.1:8000/api/agent/sessions/$SESSION_ID/messages" \
  -H "Content-Type: application/json" \
  -d '{"text":"삼성전자 5주 사줘"}'

curl -X POST "http://127.0.0.1:8000/api/agent/sessions/$SESSION_ID/messages" \
  -H "Content-Type: application/json" \
  -d '{"text":"승인"}'
```

첫 응답은 `WAITING_CONFIRMATION`이고 두 번째 응답은 Spring의 MOCK 주문 실행 결과입니다. 일반 주문 취소·정정과 조건 주문 생성·취소도 같은 승인 흐름을 사용합니다. 승인 대기 중 새로운 문장을 말해도 미리보기 파라미터를 바꾸지 않으며 `승인` 또는 `취소`를 다시 요청합니다.

종목이나 수량처럼 필요한 정보가 빠지면 같은 세션에서 한 항목씩 이어서 묻습니다. 필요한 값이 모두 모인 뒤에만 Spring 미리보기를 생성하며, 정보 입력 중 `취소` 또는 `그만`이라고 말하면 미리보기를 만들지 않고 종료합니다.

```text
사용자: 사줘
에이전트: 어느 종목인지 말씀해주세요.
사용자: 삼성전자
에이전트: 몇 주를 주문할까요?
사용자: 5주
에이전트: 주문 미리보기 안내 후 승인 또는 취소 요청
```

규칙 기반 해석기의 추가 명령 예시는 다음과 같습니다. 주문 식별자는 목록 조회 응답에서 받은 값을 그대로 말해야 하며 에이전트가 임의로 고르지 않습니다.

```text
미체결 주문 알려줘
지금 달러 환율 알려줘
100달러는 원화로 얼마야
애플 200달러어치 사줘
주문번호 order-123 취소해줘
주문번호 order-123을 7주 71,000원으로 정정해줘
조건 주문 목록 알려줘
삼성전자 80,000원이 되면 시장가로 2주 매도 조건주문 만료일 2026-09-30
조건주문번호 conditional-123 취소해줘
삼성전자 2주 OCO 조건주문 첫 조건 감시가 80,000원 주문가 79,000원 둘째 조건 감시가 65,000원 주문가 64,900원 만료일 2026-09-30
삼성전자 2주 OTO 조건주문 첫 조건 감시가 68,000원 주문가 69,000원 둘째 조건 감시가 79,000원 주문가 80,000원 만료일 2026-09-30
조건주문번호 conditional-123을 OCO 조건주문으로 정정 2주 첫 조건 감시가 80,000원 주문가 79,000원 둘째 조건 감시가 65,000원 주문가 64,900원 만료일 2026-09-30
```

금액 주문은 Spring 백엔드 계약에 맞춰 미국 주식의 달러 금액 시장가 매수만 지원합니다. `삼성전자 10만 원어치 사줘` 같은 국내·원화 금액 주문, 금액 매도, 수량과 금액을 함께 말한 요청은 미리보기 전에 거절합니다.

환율 조회와 금액 환산은 읽기 전용 참고 정보입니다. 토스증권 OpenAPI는 실제 환전 거래 API를 제공하지 않으므로 `10만 원을 달러로 환전해줘`처럼 계좌 통화를 변경하는 명령은 외부 호출 없이 거절합니다.

OCO는 첫·둘째 조건을 모두 매도로, OTO는 첫 조건을 매수하고 둘째 조건을 매도로 고정합니다. 조건 주문 정정은 일부 값만 바꾸지 않고 정정 후 전체 유형·수량·두 가격 조건·만료일을 다시 검증합니다.

## 외부 계정 없는 로컬 프로세스 E2E

실제 FastAPI와 Spring 프로세스 사이의 HTTP 연결은 루트의 읽기 전용 토스 스텁으로 재현할 수 있습니다. 스텁은 OAuth와 계좌·현재가·매수 가능 금액·수수료·환율·미국 장 운영 일정 조회만 제공하며 주문 변경 API는 구현하지 않습니다.

첫 번째 터미널에서 스텁을 실행합니다.

```bash
python3 devtools/toss_read_stub.py
```

두 번째 터미널에서 Spring을 실제 주문이 불가능한 MOCK 설정으로 실행합니다.

```bash
cd spring-backend
TOSSINVEST_BASE_URL=http://127.0.0.1:18081 \
TOSSINVEST_CLIENT_ID=local-client \
TOSSINVEST_CLIENT_SECRET=local-secret \
JUSIKA_INTERNAL_READ_API_KEY=local-read-key \
JUSIKA_INTERNAL_ORDER_API_KEY=local-order-key \
JUSIKA_BROKER_MODE=mock \
JUSIKA_LIVE_TRADING_ENABLED=false \
JUSIKA_TRADING_KILL_SWITCH_ACTIVE=true \
SPRING_SERVER_PORT=18080 \
./mvnw spring-boot:run
```

세 번째 터미널에서 Agent를 실행합니다.

```bash
cd python-agent
JUSIKA_AGENT_SPRING_BACKEND_URL=http://127.0.0.1:18080 \
JUSIKA_INTERNAL_READ_API_KEY=local-read-key \
JUSIKA_INTERNAL_ORDER_API_KEY=local-order-key \
JUSIKA_AGENT_PORT=18000 \
PYTHONPATH=src \
.venv/bin/python -m jusika_agent
```

이후 위의 텍스트 기반 MOCK 흐름에서 포트만 `18000`으로 바꾸면 `Agent → Spring → 읽기 전용 스텁`과 Spring의 모의 주문 실행까지 확인할 수 있습니다.

## 명령 해석기

기본 `rules` 모드는 외부 API 호출 없이 삼성전자, 애플, 직접 입력한 국내 6자리 종목 코드와 미국 티커의 기본 명령을 해석합니다. 로컬·CI의 MOCK End-to-End 검증에 사용합니다.

실제 OpenAI Structured Outputs를 사용하려면 다음 값을 실행 환경에서 설정합니다.

```dotenv
JUSIKA_AGENT_COMMAND_INTERPRETER=openai
JUSIKA_AGENT_OPENAI_MODEL=gpt-4o-mini
JUSIKA_AGENT_OPENAI_TIMEOUT_SECONDS=15
JUSIKA_AGENT_OPENAI_MAX_OUTPUT_TOKENS=1000
JUSIKA_AGENT_OPENAI_MAX_ATTEMPTS=3
JUSIKA_AGENT_OPENAI_RETRY_BASE_DELAY_SECONDS=0.25
OPENAI_API_KEY=실제_비밀값
```

OpenAI 해석기는 출력 토큰과 호출 시간을 제한하고, 연결 오류, 시간 초과, 요청 제한과
일시적인 서버 오류만 최대 설정 횟수까지 지수 간격으로 재시도합니다. 잘못된 요청이나
구조화되지 않은 응답은 재시도하지 않으며, 해석 실패 시 주문 미리보기를 생성하지 않고
사용자에게 다시 말해달라고 안내합니다.

## 체크포인트

기본 `memory` 체크포인터는 프로세스를 재시작하면 승인 대기 상태가 사라집니다. 운영 또는 재시작 복구 검증에는 Python Agent 전용 PostgreSQL 연결 URL을 설정합니다.

```dotenv
JUSIKA_AGENT_CHECKPOINT_PROVIDER=postgres
JUSIKA_AGENT_CHECKPOINT_DATABASE_URL=postgresql://사용자:비밀번호@호스트:5432/데이터베이스
```

Agent 체크포인트에는 대화 상태와 Spring 미리보기 식별값이 저장될 수 있으므로 접근 권한과 보존 정책을 주문 데이터 수준으로 관리해야 합니다.

## 검증

```bash
python -m ruff check src tests
python -m mypy src
python -m pytest
```

# 파이썬 에이전트

음성 인식 결과를 구조화된 금융 명령으로 해석하고 Spring 백엔드의 안전한 금융 API를 호출하는 FastAPI와 LangGraph 프로젝트입니다.

현재 MVP는 다음 흐름을 지원합니다.

- 종목 현재가 조회
- 달러·원화 참고 환율 조회와 금액 환산
- 단일 계좌의 보유자산 조회
- 통화별 매수 가능 금액·시장별 수수료·종목별 매도 가능 수량 조회
- 수량 기반 매수·매도 미리보기 생성
- 미국 주식 달러 금액 시장가 매수 미리보기 생성
- 미체결·종료 일반 주문과 주문 상세, 감시 중 조건 주문과 상세 조회
- 일반·금액 주문 실행 상태 조회와 결과 불명 실행의 승인 기반 복구
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
- Spring·체크포인트 의존성을 확인하는 readiness와 민감정보 없는 JSON 운영 로그

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

기본 주소는 `http://127.0.0.1:8000`입니다. `/health`는 외부 의존성을 호출하지 않는 프로세스 생존 확인이고, `/ready`는 Spring Actuator와 현재 LangGraph 체크포인트 저장소를 읽기 전용으로 확인합니다. 의존성이 준비되지 않으면 `/ready`는 `503 NOT_READY`를 반환합니다. OpenAI 해석기는 상태 확인마다 유료 원격 호출을 만들지 않고 시작 시 구성·클라이언트 생성 성공 여부로 준비 상태를 판단합니다.

모든 에이전트 운영 로그는 한 줄 JSON입니다. 요청 UUID가 `X-Jusika-Request-Id`로 Spring까지 전달되며 원문 사용자 발화, API 키·토큰, 계좌번호와 주문·미리보기·실행 식별자는 기록하지 않습니다. 세션은 원래 UUID 대신 비가역 해시로만 구분합니다.

Spring 조회 요청은 연결 오류, 시간 초과, 요청 제한 또는 일시적인 서버 오류가 발생하면
기본 최대 3회까지 짧은 지수 간격으로 재시도합니다. 주문 미리보기·승인·실행을 포함한
모든 POST 요청은 결과 불명 상태에서 중복 전송될 위험이 있어 자동으로 재시도하지 않습니다.

```dotenv
JUSIKA_AGENT_SPRING_CONNECT_TIMEOUT_SECONDS=3
JUSIKA_AGENT_SPRING_READ_TIMEOUT_SECONDS=8
JUSIKA_AGENT_SPRING_READ_MAX_ATTEMPTS=3
JUSIKA_AGENT_SPRING_RETRY_BASE_DELAY_SECONDS=0.25
JUSIKA_AGENT_READINESS_TIMEOUT_SECONDS=3
JUSIKA_AGENT_LOG_LEVEL=INFO
```

## 텍스트 기반 MOCK 흐름

Android 0.4.0은 명시적 앱 실행 뒤 녹음한 WAV를 먼저 `POST /api/agent/transcriptions`로
전송합니다. 이 경로는 서버의 `gpt-transcribe`를 사용해 `{"text":"..."}`를 반환하며,
명령은 기존 `POST /api/agent/sessions/{session_id}/voice-messages`로 보냅니다.
명령/누락 정보 답변은 `{"text":"..."}`, 미리보기 승인/중단은
`{"text":"승인", "confirmation_preview_id":"안내한 preview_id"}` 또는 `취소`를 보냅니다.
음성 API는 각 요청 전 Spring 안전 상태가 내부적으로 일관적인지
확인하며, 승인/중단 시 미리보기 ID와 승인 대기 상태가 일치해야 합니다. `네/응` 등의
텍스트 승인 별칭은 음성 API에서 승인으로 사용하지 않습니다. 잘못되거나 이미 처리된 ID는
실행하지 않고 오류로 반환합니다. 기존 텍스트 API 계약은 유지합니다.
전사와 두 메시지 API에 동일한 모바일 인증·사용자별 요청 제한이 적용됩니다. 전사 WAV는
최대 1,000,044바이트, JSON은 최대 8KiB로 제한됩니다. 기본 배포는 MOCK이며, 별도 LIVE
배포에서도 Spring이 보고한 안전 상태가 일관적일 때만 음성 요청을 처리합니다. LIVE가 차단된
상태에서는 조회만 계속 사용할 수 있고 주문 변경은 Spring의 중앙 안전정책에서 거절됩니다.
실제 프로세스 전체 기능 검증은 루트에서 다음과 같이 실행합니다.

```bash
python-agent/.venv/bin/python devtools/run_agent_process_e2e.py --voice
```

세션 ID는 Android가 한 음성 대화 동안 유지하는 UUID입니다.

### 가족용 모바일 인증 설정 (0.3.0)

관리자가 무작위 개인 토큰을 발급하고 앱에 직접 입력하는 방식입니다. OpenAI 키나 증권사
비밀키를 앱에 넣지 않습니다. 토큰은 소지자가 사용자 권한을 갖는 비밀값이므로 채팅·로그·Git에
공유하지 마세요. 아래 명령은 로컬 터미널에 새 토큰 하나를 표시합니다.

```bash
python-agent/.venv/bin/python -c 'import secrets; print(secrets.token_urlsafe(32))'
```

루트 `.env`에 생성한 값을 직접 넣고 서버를 재시작합니다 (예시 문자열은 실제 토큰이 아닙니다).

```dotenv
JUSIKA_AGENT_ENVIRONMENT=production
JUSIKA_AGENT_MOBILE_AUTH_REQUIRED=true
JUSIKA_AGENT_MOBILE_CREDENTIALS='{"father":"생성한_토큰으로_교체"}'
JUSIKA_AGENT_MOBILE_REQUESTS_PER_MINUTE=30
JUSIKA_AGENT_MOBILE_IP_REQUESTS_PER_MINUTE=60
```

앱에서 HTTPS 서버 주소와 같은 토큰을 입력합니다. HTTP 개발은 loopback USB reverse만 가능합니다.
서버는 `Authorization: Bearer <개인 토큰>`을 검사하고, 잘못된/누락 토큰은 401,
외부 평문 연결·브라우저 Origin은 403, 분당 한도 초과는 429 (`Retry-After: 60`),
JSON 8KiB 또는 전사 WAV 1,000,044바이트 초과 요청 본문은 413으로 차단합니다.
주문 POST는 자동 재시도하지 않습니다.
인증 OFF는 환경 `local` + 실제 peer loopback만 허용합니다. **인증 OFF 서버를 reverse proxy로
공개하지 마세요.** 운영에서는 인증을 반드시 켜고 Spring은 비공개 네트워크에 둡니다.

내부 체크포인트/잠금 ID는 사용자 ID + 클라이언트 UUID에서 결정적으로 분리합니다.
동일한 UUID나 다른 사람의 preview ID를 보내도 다른 사용자의 승인 대기를 이어받지 않습니다.
응답에는 원래 UUID만 반환합니다. 인증을 켜기 전 익명 세션은 인증 세션에서 재개하지 않습니다.
재시작 복구에는 기존 PostgreSQL 체크포인터가 필요하며, 토큰 교체 시 사용자 ID를 유지하면
해당 사용자의 체크포인트 이름도 유지됩니다. 사용자 ID를 다른 사람에게 재사용하지 마세요.
분실/폐기는 `.env`에서 해당 토큰을 제거하거나 교체한 뒤 재시작합니다. 이미 처리 중인 요청을
소급 취소하는 기능은 아니므로 즉시 거래 차단에는 기존 kill switch를 사용합니다.

범위와 운영 제한:

- 가족용 수동 토큰 인증이며 가입·자동 토큰 만료·계정 복구·사용자별 증권계좌 권한은 없습니다.
  서로 다른 사용자도 같은 서버의 금융 백엔드를 이용하므로 신뢰하는 가족에게만 발급하세요.
- 요청 제한은 프로세스 메모리 기반입니다. 단일 worker/단일 instance로만 사용하며 재시작 시
  초기화됩니다. 여러 instance에는 Redis 등 공유 제한기가 필요합니다.
- 인증 실패 요청도 IP별 분당 60회로 제한합니다. 신뢰할 proxy IP만 forwarded 설정에 허용해야
  실제 클라이언트 IP를 안전하게 사용할 수 있습니다. `--forwarded-allow-ips='*'`를 사용하지 마세요.
  [단일 VM 배포 구성](../deploy/README.md)은 HTTPS·본문/접속 시간 제한을 함께 제공합니다.
- 서버 배포나 실계좌 연결은 이 변경에서 수행하지 않았습니다. 실제 주문 차단도 유지합니다.

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
전체 주문 내역 알려줘
주문번호 order-123 상태 알려줘
원화 주문 가능 금액 알려줘
내 주식 수수료 알려줘
삼성전자 매도 가능 수량 알려줘
지금 달러 환율 알려줘
100달러는 원화로 얼마야
애플 200달러어치 사줘
주문번호 order-123 취소해줘
주문번호 order-123을 7주 71,000원으로 정정해줘
조건 주문 목록 알려줘
조건주문번호 conditional-123 상세 알려줘
실행번호 execution-123 상태 알려줘
금액 주문 실행번호 amount-execution-123 복구해줘
삼성전자 80,000원이 되면 시장가로 2주 매도 조건주문 만료일 2026-09-30
조건주문번호 conditional-123 취소해줘
삼성전자 2주 OCO 조건주문 첫 조건 감시가 80,000원 주문가 79,000원 둘째 조건 감시가 65,000원 주문가 64,900원 만료일 2026-09-30
삼성전자 2주 OTO 조건주문 첫 조건 감시가 68,000원 주문가 69,000원 둘째 조건 감시가 79,000원 주문가 80,000원 만료일 2026-09-30
조건주문번호 conditional-123을 OCO 조건주문으로 정정 2주 첫 조건 감시가 80,000원 주문가 79,000원 둘째 조건 감시가 65,000원 주문가 64,900원 만료일 2026-09-30
```

금액 주문은 Spring 백엔드 계약에 맞춰 미국 주식의 달러 금액 시장가 매수만 지원합니다. `삼성전자 10만 원어치 사줘` 같은 국내·원화 금액 주문, 금액 매도, 수량과 금액을 함께 말한 요청은 미리보기 전에 거절합니다.

실행 복구는 일반 수량 주문과 미국 주식 달러 금액 주문의 결과가 `UNKNOWN`일 때만 사용할 수 있습니다. 에이전트는 복구 실행 전 다시 승인을 받고, 이미 접수·거절이 확정된 실행은 Spring이 재처리하지 않습니다.

환율 조회와 금액 환산은 읽기 전용 참고 정보입니다. 현재 연동 기준인 토스증권 OpenAPI v1.2.14는 실제 환전 거래 API를 제공하지 않으므로 `10만 원을 달러로 환전해줘`처럼 계좌 통화를 변경하는 명령은 외부 호출 없이 거절합니다. 실제 환전을 추가하려면 환전 주문을 제공하는 별도 금융기관 API와 그 기관의 계좌·인증 계약이 필요합니다.

OCO는 첫·둘째 조건을 모두 매도로, OTO는 첫 조건을 매수하고 둘째 조건을 매도로 고정합니다. 조건 주문 정정은 일부 값만 바꾸지 않고 정정 후 전체 유형·수량·두 가격 조건·만료일을 다시 검증합니다.

## 외부 계정 없는 로컬 프로세스 E2E

실제 FastAPI와 Spring 프로세스 사이의 HTTP 연결은 루트의 읽기 전용 토스 스텁으로 재현할 수 있습니다. 스텁은 OAuth와 계좌·현재가·매수 가능 금액·수수료·매도 가능 수량·환율·보유자산·주문 및 조건 주문·미국 장 운영 일정 조회만 제공하며 주문 변경 API는 구현하지 않습니다.

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

전체 기능을 한 번에 검증하려면 저장소 루트에서 자동 E2E 러너를 실행합니다. 러너는 사용
가능한 로컬 포트를 골라 세 프로세스를 시작하고, 조회 13종, 실제 환전 거절, 다중 턴 수집,
일반·금액·조건 주문 변경 10종과 확정 실행 복구 차단까지 총 26개 시나리오를 검증한 뒤
성공·실패와 관계없이 프로세스를 종료합니다.

```bash
python-agent/.venv/bin/python devtools/run_agent_process_e2e.py
```

Pytest 선택 테스트로도 실행할 수 있습니다.

```bash
cd python-agent
RUN_JUSIKA_AGENT_PROCESS_E2E=true .venv/bin/python -m pytest -m process_e2e
```

Spring은 강제 `mock`, LIVE 비활성화, kill switch 활성화로 실행되며 토스 스텁은 주문 변경
요청을 거절하므로 실제 증권사 주문은 발생하지 않습니다.

## 명령 해석기

기본 `rules` 모드는 외부 API 호출 없이 삼성전자, 애플, 직접 입력한 국내 6자리 종목 코드와 미국 티커의 기본 명령을 해석합니다. 로컬·CI의 MOCK End-to-End 검증에 사용합니다.

실제 OpenAI Structured Outputs를 사용하려면 다음 값을 실행 환경에서 설정합니다.

```dotenv
JUSIKA_AGENT_COMMAND_INTERPRETER=openai
JUSIKA_AGENT_OPENAI_MODEL=gpt-5.6-terra
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

운영 배포는 `gpt-5.6-terra`와 reasoning effort `none`을 사용해 한국어 명령을 엄격한
`ParsedIntent` 구조로만 변환합니다. 모델은 주문을 직접 실행하지 않으며, 모든 변경 요청은
기존 Spring 미리보기·명시적 승인·실행 직전 재검증을 그대로 통과해야 합니다.

## 버튼/Bixby 음성 전사

명령 해석기를 `rules`로 두어도 Android 0.4.0 음성 전사에는 OpenAI 키가 필요합니다.

```dotenv
OPENAI_API_KEY=실제_비밀값
JUSIKA_AGENT_OPENAI_TRANSCRIPTION_MODEL=gpt-transcribe
JUSIKA_AGENT_OPENAI_TRANSCRIPTION_TIMEOUT_SECONDS=30
```

키는 서버에만 저장하고 APK·모바일 토큰·QR에 넣지 않습니다. 서버는 `audio/wav` 형식과
크기를 확인한 뒤 한국어와 금융 용어 힌트를 포함해 전사하며, 원본이나 전사문을 애플리케이션
로그에 기록하지 않습니다.

## 체크포인트

기본 `memory` 체크포인터는 프로세스를 재시작하면 승인 대기 상태가 사라집니다. 운영 또는 재시작 복구 검증에는 Python Agent 전용 PostgreSQL 연결 URL을 설정합니다.

```dotenv
JUSIKA_AGENT_CHECKPOINT_PROVIDER=postgres
JUSIKA_AGENT_CHECKPOINT_DATABASE_URL=postgresql://사용자:비밀번호@호스트:5432/데이터베이스
JUSIKA_AGENT_CHECKPOINT_SESSION_LOCK_TIMEOUT_SECONDS=30
```

Agent 체크포인트에는 대화 상태와 Spring 미리보기 식별값이 저장될 수 있으므로 접근 권한과 보존 정책을 주문 데이터 수준으로 관리해야 합니다.
메모리 모드에서는 프로세스 안의 세션별 잠금으로, PostgreSQL 모드에서는 여러 프로세스가
공유하는 advisory lock으로 같은 세션의 상태 조회와 변경을 직렬화합니다. 잠금 대기 시간이
지나면 주문 흐름을 새로 실행하지 않고 잠시 후 다시 요청하라는 오류를 반환합니다.

### PostgreSQL 재시작 복구 통합 테스트

루트의 테스트 전용 PostgreSQL 컨테이너를 사용하면 Python Agent 앱을 매 요청마다 완전히
종료하고 새로 생성해도 같은 세션의 누락 정보와 승인 대기가 복구되는지 확인할 수 있습니다.
데이터베이스 이름에 `test` 또는 `integration`이 없으면 테스트는 즉시 실패합니다.

```bash
export JUSIKA_TEST_POSTGRES_PASSWORD=통합테스트에서만_사용할_비밀번호
docker compose -f compose.postgres-test.yaml up -d

cd python-agent
RUN_JUSIKA_AGENT_POSTGRES_TEST=true \
JUSIKA_AGENT_TEST_POSTGRES_URL="postgresql://jusika_test:${JUSIKA_TEST_POSTGRES_PASSWORD}@localhost:55432/jusika_integration_test" \
.venv/bin/python -m pytest -m postgres

cd ..
docker compose -f compose.postgres-test.yaml stop
```

이 테스트는 실제 OpenAI API나 토스증권 API를 호출하지 않으며 주문 실행은 Fake Spring
경계에서만 기록합니다. PostgreSQL 컨테이너의 데이터 디렉터리도 `tmpfs`이므로 컨테이너를
제거하면 테스트 체크포인트가 함께 사라집니다.

## 검증

```bash
python -m ruff check src tests
python -m mypy src
python -m pytest
```

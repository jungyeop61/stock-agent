# 주식아 · 서버 한 대 배포

GCP Compute Engine / AWS Linux VM에서 같은 Docker 구성을 사용합니다.
초기 기준은 Debian 13 x86/64, RAM 2GB, 균형 디스크 20GB, swap 2GB입니다.
이는 성능 보장이 아닙니다. 2GB에서는 한 사용자로 시작해 메모리·응답 시간·재시작을 검증하세요.

## 구조와 제한

- Caddy만 외부 80/443을 공개합니다. 80은 인증서 발급과 HTTPS 리다이렉트용입니다.
- Spring 8080, Python 8000, PostgreSQL 5432는 호스트에 공개하지 않습니다.
- Python은 개인 토큰 인증, 사용자별 세션 격리, 사용자 30회/분·IP 60회/분 제한을 적용합니다.
  IP/사용자 제한은 단일 프로세스 메모리 기반이며 재시작하면 초기화됩니다.
- Caddy는 사용자 전달 헤더를 덮어쓰고, Uvicorn은 프록시 고정 IP `172.30.91.2`만 신뢰합니다.
  사설 proxy subnet을 바꾸면 compose와 agent.Dockerfile의 신뢰 IP를 함께 변경하고 다시 빌드하세요.
- Spring DB와 LangGraph 체크포인트/잠금은 같은 PostgreSQL에 저장합니다. 익명 로컬 대화는 이관하지 않습니다.
- 기본 `compose.yaml`은 MOCK, LIVE 비활성화, kill switch 활성화로 고정합니다.
  루트 `.env`나 호스트 환경변수만으로 실제 거래를 활성화할 수 없습니다. 실제 어댑터는
  별도 `compose.live.yaml`을 명시한 경우에만 선택됩니다.
- 운영 명령 해석은 OpenAI `gpt-5.6-terra`의 Structured Outputs를 사용하고, Android 0.4.0의
  명시적 버튼/Bixby 음성 입력 전사는 `gpt-transcribe`를 사용합니다. 로컬·CI 검증만 `rules`로
  강제해 외부 호출과 과금을 막습니다. 실제 환전은 지원하지 않습니다.
- 합산 컨테이너 메모리 상한은 1696MiB입니다. Docker/OS 여유가 작으므로 무거운 작업은 맥에서 합니다.
  Java heap 512MiB, DB 연결 수와 작업 메모리, 컨테이너 로그도 제한합니다.
- DB 백업, 보안 업데이트, 요청 제한보다 앞선 대규모 DDoS 보호는 별도 운영 작업입니다.

## 1. HTTPS 주소 준비

도메인이 없으면 [DuckDNS](https://www.duckdns.org/)의 무료 하위 도메인을 사용할 수 있습니다.
본인 계정으로 이름을 등록한 뒤 해당 주소의 IPv4를 VM의 외부 IPv4로 설정하세요.
DuckDNS 토큰은 DNS 관리 비밀값입니다. 채팅·Git에 공유하거나 이 앱의 접속 토큰으로 쓰지 않습니다.
HTTPS 인증서는 Caddy가 HTTP/TLS challenge로 발급하므로 DuckDNS 토큰을 컨테이너에 전달하지 않습니다.

VM의 IP를 고정 예약/연결하면 재시작마다 DNS를 수정할 필요가 줄어듭니다. 외부 IPv4는 별도 과금입니다.
사용하지 않는 예약 IP도 과금되므로 중복 예약하지 마세요. 임시 IP를 사용하면 VM 중지/재시작 후
변경 여부를 확인하고 DNS를 갱신해야 합니다.
DNS A 레코드가 서버를 가리키고 인터넷 80/443이 허용돼 있어야 인증서가 발급됩니다.
불필요한 AAAA 레코드는 제거하거나 실제 접근 가능한 IPv6만 등록하세요.

GCP 방화벽에서는 웹용 80/443만 앱에 공개합니다. SSH 22는 본인 IP 또는 IAP 접근으로 제한하고,
8000·8080·5432는 허용하지 마세요. AWS에서는 같은 원칙을 보안 그룹에 적용합니다.

## 2. 맥에서 이미지 빌드 및 내보내기

프로젝트 루트에서 Docker Desktop이 실행된 상태로 작업합니다. Apple Silicon에서도 서버용
`linux/amd64`를 명시해야 합니다. 2GB VM에서 직접 빌드하지 마세요.

```bash
docker build --platform linux/amd64 -f deploy/spring.Dockerfile -t jusika-spring:vm-2 .
docker build --platform linux/amd64 -f deploy/agent.Dockerfile -t jusika-agent:vm-2 .
mkdir -p deploy/artifacts
docker image save jusika-spring:vm-2 jusika-agent:vm-2 -o deploy/artifacts/jusika-vm-2.tar
gzip -n deploy/artifacts/jusika-vm-2.tar
shasum -a 256 deploy/artifacts/jusika-vm-2.tar.gz
```

출력 파일이 이미 존재하면 덮어쓰지 말고 새 버전의 파일명/이미지 태그를 사용하세요.
이미지에는 키·환경 설정·APK가 포함되지 않습니다. 외부 기본 이미지/의존성은
빌드 시 다운로드하므로 배포 때는 검증한 이미지 아카이브를 그대로 이관하세요.
구성 변경 파일은 본인이 commit/push한 뒤 서버에서 받습니다. 이 문서의 명령은 자동 push하지 않습니다.

## 3. VM에 프로젝트와 이미지 전달

서버 SSH에서:

```bash
git clone https://github.com/jungyeop61/stock-agent.git
cd stock-agent
```

이미 clone했다면 새로 겹쳐 만들지 말고 기존 경로에서 본인이 push한 변경을 받습니다.
private 저장소라면 최소 읽기 권한의 deploy key 등 별도 인증이 필요합니다. 개인 토큰을 URL에 넣거나
채팅에 보내지 마세요. GitHub 계정 비밀번호로 clone하는 방식도 지원하지 않습니다.

브라우저 SSH의 파일 업로드 기능 또는 `gcloud compute scp`로 맥의 이미지 파일을 서버에 옮깁니다.
업로드한 실제 경로를 확인한 뒤 맥에서 확인한 SHA256과 비교합니다.

```bash
sha256sum jusika-vm-2.tar.gz
sudo docker image load -i jusika-vm-2.tar.gz
```

`docker image load`는 gzip 아카이브를 직접 읽습니다. 경로는 파일을 업로드한 위치에 맞춰 실행하세요.
서버에서는 Caddy/PostgreSQL 공식 이미지만 처음 실행할 때 다운로드합니다.

## 4. 서버에서 비밀값 생성

프로젝트 루트에서 (아래 주소/이메일은 자신의 값으로 교체):

```bash
python3 deploy/init_environment.py --domain 본인주소.duckdns.org --email 본인이메일@example.com
```

`deploy/.env`, `deploy/.mobile-token`을 0600 권한으로 새로 만들며 기존 파일은 덮어쓰지 않습니다.
DB 비밀번호, Spring 읽기·주문 키, 아버지 개인 접속 토큰은 서로 다른 무작위 값입니다.
기존 맥 루트 `.env`를 자동 복사하지 않습니다. 실제 비밀값을 Git/채팅에 보내지 마세요.

생성된 `deploy/.env`의 빈 `OPENAI_API_KEY=` 값에는 발급한 OpenAI API 키를 서버 SSH 안에서
직접 입력하세요. 키를 채팅·명령 인자·APK·QR에 넣지 마세요. 이 키는 앱이 열린 뒤 녹음한
한 문장의 전사와 전사된 한국어 명령의 구조화 해석에만 사용됩니다. 모델 해석 결과가 주문을
직접 실행하지 않으며 기존 미리보기·명시적 승인·Spring 재검증 절차는 그대로 유지됩니다.

### Android 0.3.2 이상: 개인 접속 설정 QR

QR 생성은 VM 안에서만 수행합니다. 외부 QR 서비스, 공개 접속 설정 URL, 새로운 서버 API는 사용하지 않습니다.
서버에서 아래를 실행합니다 (`--domain`은 본인 주소로 변경):

```bash
sudo apt-get update
sudo apt-get install -y python3-qrcode
python3 deploy/mobile_setup.py --domain 본인주소.duckdns.org --terminal
```

인터랙티브 SSH에 표시된 QR을 앱의 **QR로 서버 주소와 토큰 한 번에 등록**으로 스캔하고,
표시된 서버 주소를 확인한 뒤 등록합니다. 스캔 뒤 SSH 창을 닫으세요. QR도 접속 비밀값이므로 공유하지 않습니다.
토큰은 명령 인자나 셸 기록에 넣지 않습니다. 이 도구는 `.env`의 증권사·DB 비밀값을 읽지 않으며
`.mobile-token`만 읽습니다. 기존 토큰이 서버에서 변경된 경우 관리자가 `.mobile-token`도 맞춰야 합니다.

QR이 터미널에서 잘 안 읽히면 `--terminal`을 빼고 실행하면 0600 권한의
`deploy/.mobile-setup.html`을 새로 만듭니다. SSH 파일 다운로드로 Mac에 내려받아 Safari에서 열고 스캔합니다.
HTML은 외부 리소스/네트워크/스크립트 없이 SVG QR만 포함합니다. 서버와 Mac의 QR 파일은 등록 후 삭제합니다.
기존 QR 파일은 덮어쓰지 않으며 Git에서도 제외합니다. QR 파일은 Drive에 업로드하지 마세요.

아직 코드를 Git으로 배포하지 않았다면 Mac의 `deploy/mobile_setup.py` **코드 파일만** SSH 업로드로 올릴 수 있습니다.
기본 업로드 위치가 홈 디렉터리인 경우:

```bash
python3 /home/본인계정/mobile_setup.py --directory /home/본인계정/stock-agent/deploy --domain 본인주소.duckdns.org --terminal
```

이 변경 때문에 서버 컨테이너를 재빌드/재시작하거나 실제 주문 차단을 해제할 필요는 없습니다.

초기 Toss 인증 값은 비어 있습니다. 실제 조회를 사용하려면 관리자가 서버의 `deploy/.env`에
`TOSSINVEST_CLIENT_ID`, `TOSSINVEST_CLIENT_SECRET`을 직접 입력해야 합니다. 앱에는 넣지 않습니다.
설정하지 않은 상태에서는 서비스 기동은 가능하지만 금융 조회가 성공하지 않습니다.
이 설정은 읽기용 연결일 뿐 실제 주문 차단은 해제하지 않습니다.

배포 설정은 `--env-file deploy/.env`로만 읽습니다. 모든 비밀값을 모든 컨테이너에 넘기지 않습니다.
전체 `docker compose config`, `.env`, `.mobile-token`, `docker inspect` 출력은 공유하지 마세요.
DB가 초기화된 뒤 `.env`의 비밀번호만 바꾸면 DB 비밀번호가 바뀌지 않습니다. 실제 DB 사용자
비밀번호와 앱 설정을 함께 관리해야 하므로 임의 재생성하지 마세요.

## 5. 기본 MOCK 검증 후 실행

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --wait
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml ps
sudo docker stats --no-stream
```

`--wait`가 실패하면 멈추고 해당 서비스 상태를 확인합니다. 비밀값 없이 오류를 확인하세요.
서버 밖에서 `https://본인주소.duckdns.org/health`가 UP인지 확인합니다. `/ready`, Spring,
텍스트 messages API, docs, DB는 외부 프록시에서 노출하지 않습니다. 인증 없는 음성 요청은 401이어야 합니다.
서비스 healthy는 실제 Toss 인증/조회 성공, OpenAI 전사 성공이나 S22 음성 입력 성공을 의미하지 않습니다.

Android 0.3.0 이상에서 서버 주소 `https://본인주소.duckdns.org`와 `deploy/.mobile-token`의 값을
보호자가 직접 등록합니다. 토큰은 공개 링크로 만들지 마세요. 첫 조회와 승인 테스트는 MOCK으로 진행하고,
재시작 후 상태 복구·중복 승인 차단·화면 잠금·배터리 동작을 실기기에서 검증하세요.

## 6. 실제 증권사 연결을 주문 없이 검증

먼저 토스증권 WTS의 Open API 설정에서 이 VM의 고정 외부 IPv4가 허용되어 있고,
`deploy/.env`에 `TOSSINVEST_CLIENT_ID`와 `TOSSINVEST_CLIENT_SECRET`이 들어 있는지 확인합니다.
값 자체는 채팅·화면 캡처·Git에 공유하지 마세요. 계좌와 현재가 조회가 이미 성공했다면 이 두 조건은
충족된 것입니다.

LIVE 설정은 기본 배포와 분리합니다. 서버에서 한 번만 다음 파일을 만듭니다.

```bash
cp deploy/live.env.example deploy/.live.env
chmod 600 deploy/.live.env
```

처음에는 파일을 수정하지 않습니다. 기본값은 LIVE 어댑터를 선택하되 실제 거래 기능 OFF,
kill switch ON, 허용 계좌·종목 없음, 모든 한도 0, 기능별 연결 플래그 OFF입니다. 따라서 실제 주문,
정정, 취소, 조건주문은 모두 Spring에서 차단됩니다.

새 Spring/Python 이미지를 VM에 적재한 뒤 다음처럼 **두 Compose 파일과 두 환경 파일을 항상 함께**
지정해 기동합니다.

```bash
sudo docker compose --env-file deploy/.env --env-file deploy/.live.env \
  -f deploy/compose.yaml -f deploy/compose.live.yaml up -d --wait
sudo docker compose --env-file deploy/.env --env-file deploy/.live.env \
  -f deploy/compose.yaml -f deploy/compose.live.yaml exec -T agent \
  python -m jusika_agent.broker_probe --expect-mode LIVE --symbol 005930
```

검증기는 `GET` 요청만 사용합니다. Spring 준비 상태, 안전 상태, 실제 계좌 목록, 실제 현재가만
조회하며 주문 미리보기·주문 생성·정정·취소·조건주문 주소는 호출하지 않습니다. 다음 네 종류의
출력이 모두 보여야 합니다.

- Spring 준비 완료 및 모드 `LIVE`
- 실제 계좌 조회 성공
- 실제 현재가 조회 성공
- 실제 주문 변경 `차단됨`

연결 검증 뒤에도 곧바로 kill switch를 끄지 않습니다. 실제 주문을 열려면 별도 단계에서
허용 계좌, 허용 종목, 1회·일일·활성 주문·분당 한도를 먼저 결정하고 `.live.env`에 입력해야 합니다.
그 다음 필요한 기능별 연결 플래그를 켜고, 마지막으로 사용자가 지정한 소액 주문을 미리보기와 음성
승인까지 거쳐 한 번만 검증합니다. kill switch 해제는 그 테스트 직전에 하는 마지막 단계입니다.

## 7. 중지·백업·AWS 이전

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml stop
```

LIVE 오버레이로 실행 중이면 중지·재시작·상태 확인에도 같은 `--env-file deploy/.live.env`와
`-f deploy/compose.live.yaml`을 붙여야 합니다. 누락하면 다음 기동에서 기본 MOCK 구성으로 돌아갑니다.

컨테이너를 중지해도 VM 자체를 중지하지 않으면 VM 시간 요금이 계속 발생합니다.
`down -v`는 영구 데이터와 인증서 볼륨을 삭제하므로 운영 서버에서 사용하지 마세요.

일관된 초기 수동 백업 (앱 중단 시간이 있습니다):

```bash
umask 077
mkdir -p deploy/backups
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml stop agent spring
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > deploy/backups/jusika-initial.dump
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --wait
```

백업 파일이 존재하면 다른 파일명으로 생성하세요. 덤프에는 금융 데이터와 승인 대기 상태가 있으므로
비밀값과 동일하게 보호하고 별도 저장소에 암호화 보관하세요. 백업 성공/복원을 실제로 검증해야 합니다.
디스크 스냅샷만으로 PostgreSQL 논리 백업 검증을 대체하지 않습니다.

AWS 이전은 앱을 중지하고 마지막 DB 덤프를 생성한 뒤 동일 이미지, 설정, 덤프를 새 서버로 전달합니다.
기존 사용자 ID `father`를 유지하고 승인 유효시간이 지난 요청을 재실행하지 않습니다.
복원은 **빈 신규 DB**에서 앱 기동 전에 수행합니다. 복원 후 DNS를 AWS IP로 바꾸고 Caddy 인증서 발급,
조회·MOCK 승인·재시작 복구를 확인한 뒤 기존 GCP를 중지합니다. 동시 실행은 피합니다.
GCP 데이터 삭제는 AWS 검증과 복구용 백업 확보 후 별도 명시적 단계로 진행합니다.

## 로컬 검사

```bash
python-agent/.venv/bin/pytest python-agent/tests deploy/tests
```

위 테스트는 배포 계약과 설정 보호를 검증하며 GCP 서버를 자동 생성/변경하지 않습니다.
Docker 이미지 빌드·Caddy 설정 검증·VM 실배포·휴대폰 테스트 결과는 별도로 확인해야 합니다.

이미지 빌드 후 Docker Desktop에서 전체 연결을 검사할 수 있습니다:

```bash
python-agent/.venv/bin/python deploy/smoke_vm.py
```

이 검사는 임시 프로젝트/DB와 로컬 테스트 인증서를 만들며 호스트 포트를 공개하지 않습니다.
Toss 읽기 전용 stub과 MOCK 주문만 사용해 HTTPS 인증, 조회, 미리보기, 에이전트 재시작 후 승인,
중복 승인 차단을 검사합니다. 새로 만든 테스트 볼륨만 종료 시 정리합니다. 실제 운영 서버가 아닌
Docker Desktop에서 실행하세요. 테스트용 proxy subnet이 이미 사용 중이면 동시에 실행하지 마세요.

참고: [Compose](https://docs.docker.com/reference/compose-file/services/),
[Caddy HTTPS](https://caddyserver.com/docs/automatic-https),
[Caddy proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy),
[Uvicorn proxy 신뢰 설정](https://www.uvicorn.org/settings/).

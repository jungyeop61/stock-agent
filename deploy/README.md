# 주식아 · 서버 한 대 배포 (MOCK)

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
- 거래는 compose에서 MOCK, LIVE 비활성화, kill switch 활성화로 고정합니다.
  루트 `.env`나 호스트 환경변수로 실제 거래를 활성화할 수 없습니다.
- 초기 명령 해석기는 `rules`이며 OpenAI 키를 컨테이너에 전달하지 않습니다. 실제 환전도 지원하지 않습니다.
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
docker build --platform linux/amd64 -f deploy/spring.Dockerfile -t jusika-spring:vm-1 .
docker build --platform linux/amd64 -f deploy/agent.Dockerfile -t jusika-agent:vm-1 .
mkdir -p deploy/artifacts
docker image save jusika-spring:vm-1 jusika-agent:vm-1 -o deploy/artifacts/jusika-vm-1.tar
gzip -n deploy/artifacts/jusika-vm-1.tar
shasum -a 256 deploy/artifacts/jusika-vm-1.tar.gz
```

출력 파일이 이미 존재하면 덮어쓰지 말고 새 버전의 파일명/이미지 태그를 사용하세요.
이미지에는 키·환경 설정·APK·Vosk 모델이 포함되지 않습니다. 외부 기본 이미지/의존성은
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
sha256sum jusika-vm-1.tar.gz
sudo docker image load -i jusika-vm-1.tar.gz
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

초기 Toss 인증 값은 비어 있습니다. 실제 조회를 사용하려면 관리자가 서버의 `deploy/.env`에
`TOSSINVEST_CLIENT_ID`, `TOSSINVEST_CLIENT_SECRET`을 직접 입력해야 합니다. 앱에는 넣지 않습니다.
설정하지 않은 상태에서는 서비스 기동은 가능하지만 금융 조회가 성공하지 않습니다.
이 설정은 읽기용 연결일 뿐 실제 주문 차단은 해제하지 않습니다.

배포 설정은 `--env-file deploy/.env`로만 읽습니다. 모든 비밀값을 모든 컨테이너에 넘기지 않습니다.
전체 `docker compose config`, `.env`, `.mobile-token`, `docker inspect` 출력은 공유하지 마세요.
DB가 초기화된 뒤 `.env`의 비밀번호만 바꾸면 DB 비밀번호가 바뀌지 않습니다. 실제 DB 사용자
비밀번호와 앱 설정을 함께 관리해야 하므로 임의 재생성하지 마세요.

## 5. 검증 후 실행

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --wait
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml ps
sudo docker stats --no-stream
```

`--wait`가 실패하면 멈추고 해당 서비스 상태를 확인합니다. 비밀값 없이 오류를 확인하세요.
서버 밖에서 `https://본인주소.duckdns.org/health`가 UP인지 확인합니다. `/ready`, Spring,
텍스트 messages API, docs, DB는 외부 프록시에서 노출하지 않습니다. 인증 없는 음성 요청은 401이어야 합니다.
서비스 healthy는 실제 Toss 인증/조회 성공이나 S22 음성 인식 성공을 의미하지 않습니다.

Android 0.3.0 이상에서 서버 주소 `https://본인주소.duckdns.org`와 `deploy/.mobile-token`의 값을
보호자가 직접 등록합니다. 토큰은 공개 링크로 만들지 마세요. 첫 조회와 승인 테스트는 MOCK으로 진행하고,
재시작 후 상태 복구·중복 승인 차단·화면 잠금·배터리 동작을 실기기에서 검증하세요.

## 6. 중지·백업·AWS 이전

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml stop
```

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

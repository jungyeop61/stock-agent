# 주식아 Android · 버튼/Bixby 호출 + OpenAI 음성 인식 (0.4.0)

Kotlin 네이티브 앱입니다. 갤럭시의 **측면 전원 버튼 두 번** 또는
`하이 빅스비, 주식아 열어줘`로 앱을 열면 `네, 말씀하세요`라고 안내한 뒤 한 문장을 듣습니다.
평소에는 마이크 service를 실행하지 않으며 Vosk 모델과 상시 호출어 감지를 사용하지 않습니다.

음성은 기기에서 16kHz mono WAV로 만들고, 말이 시작된 뒤 1초 침묵·최대 25초에서 자동으로
마감합니다. 인증된 HTTPS 서버의 `/api/agent/transcriptions`가 `gpt-transcribe`로 한국어를
받아쓴 뒤 기존 음성 명령 API에 텍스트를 전달합니다. OpenAI 키와 증권사 키는 서버에만 둡니다.
OpenAI 공식 문서상 `gpt-transcribe`는 녹음 파일 원문 전사에 권장되며, 키워드·언어 힌트를
지원합니다. 앱은 금융 용어와 한국어 힌트를 보냅니다.

## 호출 방식

### 기본: 측면 전원 버튼 두 번

갤럭시 S22에서 `설정 → 유용한 기능 → 측면 버튼 → 두 번 누르기 → 앱 열기 → 주식아`로
설정합니다. 길게 누르기나 볼륨 버튼 가로채기는 사용하지 않으므로 TalkBack·음량 조절과
충돌하지 않습니다.

### 보조: 빅스비

빅스비가 켜져 있으면 `하이 빅스비, 주식아 열어줘`라고 말합니다. 두 경로 모두 Android의
같은 launcher activity를 열기 때문에 앱 동작은 같습니다. 빅스비가 앱 이름을 잘못 찾으면
빅스비의 빠른 명령어에 `주식아 열기`를 한 번 등록합니다.

## 대화 및 안전 동작

- 앱 실행 → `네, 말씀하세요` → 명령 발화 → 서버 전사 → 에이전트 응답 TTS.
- 응답 뒤에는 후속 질문을 받을 수 있습니다. 20초 동안 말이 없으면 마이크와 service를 종료합니다.
- `그만` / `종료해`는 미승인 대화를 중단한 뒤 service를 종료합니다.
- 주문 내용을 끝까지 들은 뒤 `승인` 또는 `취소`라고 말해야 합니다. `네/응`은 승인이 아닙니다.
- 승인 값은 앱이 읽은 미리보기 ID와 결합되고 서버도 동일한 상태·ID를 다시 검사합니다.
- 전사 또는 주문 POST는 자동 재전송하지 않습니다. 결과를 모르는 주문은 내역부터 확인합니다.
- 현재 배포는 MOCK, LIVE 비활성화, kill switch 활성화 상태입니다.

## 최초 등록

1. 서버에서 `python3 deploy/mobile_setup.py --domain jusika.duckdns.org --terminal`로 QR을 만듭니다.
2. 앱의 `QR로 서버 주소와 토큰 한 번에 등록`으로 스캔하고 서버 주소를 확인합니다.
3. 마이크와 알림 권한을 한 번 허용합니다.
4. 삼성 TTS 설정에서 오프라인 한국어 음성을 설치합니다.
5. `지금 음성 대화 시작`으로 먼저 검사한 뒤 측면 버튼 두 번과 빅스비를 각각 검사합니다.

QR은 개인 접속 토큰을 담은 비밀값입니다. 캡처·채팅·Drive·공개 사이트에 올리지 마세요.
OpenAI 키는 APK나 QR에 넣지 않습니다.

## 빌드

Android SDK 35 / Build Tools 35.0.0과 JDK 17 또는 21을 사용합니다.

```bash
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 0.4.0은 Vosk 네이티브 라이브러리를
제거했기 때문에 이전 APK보다 작습니다. 같은 debug 서명으로 설치한 기존 앱은 `adb install -r`로
업데이트하면 암호화 토큰과 서버 주소를 유지합니다.

## 서버 요구사항

서버의 `deploy/.env`에 `OPENAI_API_KEY`가 있어야 하며 `deploy/compose.yaml`은 이 값만 Python
컨테이너에 전달합니다. 앱은 최대 약 1MB의 WAV만 전송하고 서버는 형식·크기·개인 토큰·HTTPS·
사용자/IP 요청 제한을 확인합니다. Caddy는 전사와 기존 음성 명령 두 경로만 외부에 공개하며
access log를 남기지 않습니다.

서버 반영 후 다음을 검사합니다.

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --wait
sudo docker compose --env-file deploy/.env -f deploy/compose.yaml ps
```

## 실기기 체크리스트

- [ ] 앱이 닫힌 평상시에는 마이크 표시와 주식아 알림이 없다.
- [ ] 화면이 꺼진 상태에서 측면 전원 버튼 두 번으로 앱이 열리고 `네, 말씀하세요`가 들린다.
- [ ] `하이 빅스비, 주식아 열어줘`도 같은 안내로 시작한다.
- [ ] 삼성전자·애플·금액·수량을 정확히 전사한다.
- [ ] 응답을 읽는 동안 앱 자신의 소리를 녹음하지 않는다.
- [ ] `네/응`으로 주문이 승인되지 않고 `승인/취소`를 다시 요구한다.
- [ ] 20초 무응답 또는 `그만` 뒤 마이크 표시가 사라진다.
- [ ] 잠금 화면, 절전 모드, TalkBack 사용 중에도 두 호출 경로를 각각 확인한다.

OpenAI 전사 구현 기준: [공식 파일 전사 문서](https://developers.openai.com/api/docs/guides/speech-to-text),
[`gpt-transcribe` 모델 문서](https://developers.openai.com/api/docs/models/gpt-transcribe).

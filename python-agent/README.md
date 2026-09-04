# 파이썬 에이전트

음성 명령을 해석하고 대화 흐름을 관리하는 파이썬 프로젝트입니다.

현재는 환경 설정만 구성되어 있으며 애플리케이션 코드는 아직 없습니다.

## 담당 범위

- STT 결과에서 사용자의 의도와 주문 항목 추출
- 구조화된 출력 검증
- LangGraph 상태와 대화 흐름 관리
- 사용자 승인 대기와 재개
- 스프링 백엔드 도구 호출

증권사 인증정보를 저장하거나 실제 증권사 주문 API를 직접 호출해서는 안 됩니다.

## 환경 준비

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
```

## 환경 확인

```bash
python --version
python -m pip check
python -m ruff --version
python -m mypy --version
python -m pytest --version
```

기능 구현을 시작한 뒤 실행 명령과 테스트 명령을 추가합니다.

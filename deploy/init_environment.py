#!/usr/bin/env python3
"""Create private VM settings without printing any credentials or changing local .env."""

import argparse
import ipaddress
import json
import os
import re
import secrets
from pathlib import Path


def validate_domain(raw: str) -> str:
    domain = raw.lower().strip()
    labels = domain.split(".")
    if (
        len(labels) < 2
        or len(domain) > 253
        or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels)
    ):
        raise ValueError("도메인은 https:// 없이 DNS 호스트 이름만 입력하세요.")
    try:
        ipaddress.ip_address(domain)
    except ValueError:
        return domain
    raise ValueError("IP 주소 대신 DNS 호스트 이름을 사용하세요.")


def create_environment(directory: Path, domain: str, email: str) -> tuple[Path, Path]:
    domain = validate_domain(domain)
    if not re.fullmatch(r"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}", email):
        raise ValueError("인증서 알림용 이메일 형식을 확인하세요.")
    directory.mkdir(parents=True, exist_ok=True)
    env_path = directory / ".env"
    token_path = directory / ".mobile-token"
    if env_path.exists() or token_path.exists():
        raise FileExistsError("기존 설정이 있어 중단했습니다. 기존 비밀값을 덮어쓰지 않습니다.")
    token = secrets.token_urlsafe(32)
    content = "\n".join(
        [
            f"JUSIKA_DOMAIN={domain}",
            f"ACME_EMAIL={email}",
            f"POSTGRES_PASSWORD={secrets.token_urlsafe(32)}",
            f"JUSIKA_INTERNAL_READ_API_KEY={secrets.token_urlsafe(32)}",
            f"JUSIKA_INTERNAL_ORDER_API_KEY={secrets.token_urlsafe(32)}",
            "JUSIKA_AGENT_MOBILE_CREDENTIALS='" + json.dumps({"father": token}) + "'",
            "TOSSINVEST_CLIENT_ID=",
            "TOSSINVEST_CLIENT_SECRET=",
            "OPENAI_API_KEY=",
            "",
        ]
    )
    created: list[Path] = []
    try:
        for path, value in [(env_path, content), (token_path, token + "\n")]:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            created.append(path)
            with os.fdopen(fd, "w", encoding="utf-8") as output:
                output.write(value)
    except Exception:
        for path in created:
            path.unlink()
        raise
    return env_path, token_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--email", required=True)
    args = parser.parse_args()
    try:
        create_environment(Path(__file__).resolve().parent, args.domain, args.email)
    except (ValueError, OSError):
        # Never echo inputs, credentials or a settings dump on failure.
        print("설정을 만들지 못했습니다. 도메인·이메일·기존 파일·권한을 확인하세요.")
        return 1
    print("deploy/.env 및 deploy/.mobile-token 생성 완료.")
    print("비밀값은 채팅·로그·Git에 공유하지 마세요.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

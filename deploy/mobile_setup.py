#!/usr/bin/env python3
"""Create a private registration QR locally; no external service or token in command arguments."""

import argparse
import os
import re
import stat
import sys
from pathlib import Path
from urllib.parse import urlencode


def build_payload(domain: str, token: str) -> str:
    domain = domain.strip().lower()
    labels = domain.split(".")
    if (
        len(labels) < 2
        or len(domain) > 253
        or all(label.isdigit() for label in labels)
        or any(
            not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in labels
        )
        or not re.fullmatch(r"[a-zA-Z0-9_-]{32,256}", token)
    ):
        raise ValueError("Invalid registration settings")
    return "jusika://setup?" + urlencode(
        {"v": "1", "endpoint": f"https://{domain}", "token": token}
    )


def load_payload(directory: Path, domain: str) -> str:
    path = directory / ".mobile-token"
    # Do not read unrelated .env secrets, follow symlinks or use world-readable credentials.
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "r", encoding="utf-8") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) & 0o077:
            raise ValueError("Private token file required")
        token = source.read(258)
    return build_payload(domain, token.rstrip("\r\n"))


def make_qr(payload: str):
    import qrcode

    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4)
    qr.add_data(payload)
    qr.make(fit=True)
    return qr


def write_private_html(directory: Path, qr) -> Path:
    import qrcode.image.svg

    svg = (
        qr.make_image(image_factory=qrcode.image.svg.SvgPathFillImage)
        .to_string()
        .decode("utf-8")
    )
    page = (
        '<!doctype html><html lang="ko"><meta charset="utf-8">'
        '<meta name="referrer" content="no-referrer">'
        '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; '
        "style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
        "<title>주식아 개인 등록 QR</title><style>body{font-family:sans-serif;text-align:center;"
        "background:white;color:black}svg{width:min(85vw,600px);height:auto}</style>"
        "<h1>주식아 앱에서 QR 등록을 눌러 스캔하세요</h1>"
        "<p>개인 접속 토큰이 포함되어 있습니다. 공유·업로드하지 말고 등록 후 삭제하세요.</p>"
        + svg
        + "</html>"
    )
    path = directory / ".mobile-setup.html"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(page)
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    parser.add_argument(
        "--directory",
        type=Path,
        default=Path(__file__).resolve().parent,
        help="Directory containing the private .mobile-token file",
    )
    parser.add_argument(
        "--terminal",
        action="store_true",
        help="Display private QR in interactive SSH only",
    )
    args = parser.parse_args()
    try:
        directory = args.directory
        if args.terminal and not sys.stdout.isatty():
            raise ValueError("Interactive terminal required")
        qr = make_qr(load_payload(directory, args.domain))
        if args.terminal:
            print("개인 등록 QR — 스캔 후 SSH 창을 닫으세요. 캡처·공유하지 마세요.")
            qr.print_ascii(out=sys.stdout, invert=True)
        else:
            write_private_html(directory, qr)
            print(
                "deploy/.mobile-setup.html 생성 완료. SSH에서 다운로드해 열고 앱으로 스캔하세요."
            )
            print("QR 파일은 비밀값입니다. 외부 업로드·공유 없이 등록 후 삭제하세요.")
    except (ValueError, OSError, ImportError):
        # Do not echo inputs, payloads, credentials or a traceback.
        print(
            "QR 생성 실패. 도메인·토큰 파일 권한·기존 QR 파일·python3-qrcode 설치를 확인하세요."
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

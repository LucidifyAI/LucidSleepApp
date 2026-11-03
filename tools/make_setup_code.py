#!/usr/bin/env python3
"""Make a LucidSleep setup code (and optionally a QR image) for one kit.

Usage:
  python make_setup_code.py --org ORG_ID --subject SUBJECT_ID [--label "Pilot sleeper 01"]
                            [--serial PATCH_SERIAL] [--nights 5] [--api https://...] [--qr card.png]

The code holds no secret: it names the org-service org and subject the night goes to,
and optionally the serial number of the patch packed in the kit. The sleeper still
signs in once with the pilot account Lucidify created for them.

--qr needs the "qrcode" package (pip install qrcode[pil]).
"""
import argparse
import base64
import json


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--org", required=True)
    p.add_argument("--subject", required=True)
    p.add_argument("--label")
    p.add_argument("--serial")
    p.add_argument("--nights", type=int, default=5)
    p.add_argument("--api")
    p.add_argument("--qr", help="write a QR code PNG to this path")
    a = p.parse_args()

    payload = {"v": 1, "org": a.org, "subject": a.subject, "nights": a.nights}
    if a.label:
        payload["label"] = a.label
    if a.serial:
        payload["serial"] = a.serial
    if a.api:
        payload["api"] = a.api.rstrip("/")

    raw = json.dumps(payload, separators=(",", ":")).encode()
    code = "LS1:" + base64.urlsafe_b64encode(raw).decode().rstrip("=")
    print(code)

    if a.qr:
        import qrcode  # type: ignore

        qrcode.make(code).save(a.qr)
        print(f"QR written to {a.qr}")


if __name__ == "__main__":
    main()
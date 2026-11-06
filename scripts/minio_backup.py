#!/usr/bin/env python3
"""Minimal path-style S3 client for PostgreSQL backups (Python standard library only)."""

import argparse
import datetime as dt
import hashlib
import hmac
import http.client
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote, urlencode, urlsplit

REGION = "us-east-1"
SERVICE = "s3"
CHUNK = 1024 * 1024


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(CHUNK):
            digest.update(block)
    return digest.hexdigest()


def hmac_sha256(key: bytes, value: str) -> bytes:
    return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()


def request(endpoint: str, access_key: str, secret_key: str, method: str, uri: str,
            query: dict[str, str] | None = None, body_path: Path | None = None,
            output_path: Path | None = None) -> tuple[int, bytes]:
    parsed = urlsplit(endpoint.rstrip("/"))
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise RuntimeError("MINIO_ENDPOINT must be an http(s) URL")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    host = parsed.hostname if parsed.port is None else f"{parsed.hostname}:{parsed.port}"
    canonical_uri = quote(uri, safe="/~-_.")
    query_text = urlencode(sorted((query or {}).items()), quote_via=quote, safe="~-_.")
    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = sha256_file(body_path) if body_path else hashlib.sha256(b"").hexdigest()
    canonical_headers = f"host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n"
    signed_headers = "host;x-amz-content-sha256;x-amz-date"
    canonical_request = "\n".join((method, canonical_uri, query_text, canonical_headers,
                                    signed_headers, payload_hash))
    scope = f"{date_stamp}/{REGION}/{SERVICE}/aws4_request"
    string_to_sign = "\n".join(("AWS4-HMAC-SHA256", amz_date, scope,
                                 hashlib.sha256(canonical_request.encode()).hexdigest()))
    date_key = hmac_sha256(("AWS4" + secret_key).encode(), date_stamp)
    region_key = hmac_sha256(date_key, REGION)
    service_key = hmac_sha256(region_key, SERVICE)
    signing_key = hmac_sha256(service_key, "aws4_request")
    signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    authorization = (f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, "
                    f"SignedHeaders={signed_headers}, Signature={signature}")
    headers = {"Host": host, "x-amz-content-sha256": payload_hash, "x-amz-date": amz_date,
               "Authorization": authorization}
    if body_path:
        headers["Content-Length"] = str(body_path.stat().st_size)
    target = canonical_uri + (f"?{query_text}" if query_text else "")
    conn = (http.client.HTTPSConnection(parsed.hostname, port, timeout=60)
            if parsed.scheme == "https" else http.client.HTTPConnection(parsed.hostname, port, timeout=60))
    try:
        with body_path.open("rb") if body_path else _empty_body() as body:
            conn.request(method, target, body=body if body_path else None, headers=headers)
        response = conn.getresponse()
        if output_path is not None and 200 <= response.status < 300:
            with output_path.open("wb") as output:
                while block := response.read(CHUNK):
                    output.write(block)
            return response.status, b""
        return response.status, response.read()
    finally:
        conn.close()


class _empty_body:
    def __enter__(self):
        return None

    def __exit__(self, *_args):
        return False


def bucket_uri(bucket: str, key: str | None = None) -> str:
    return "/" + bucket + ("/" + key if key else "")


def require_success(status: int, body: bytes, operation: str, expected: set[int] | None = None) -> None:
    allowed = expected or {200, 204}
    if status not in allowed:
        detail = body.decode("utf-8", "replace")[:800]
        raise RuntimeError(f"MinIO {operation} failed (HTTP {status}): {detail}")


def ensure_bucket(endpoint: str, access: str, secret: str, bucket: str) -> None:
    status, body = request(endpoint, access, secret, "HEAD", bucket_uri(bucket))
    if status in (200, 204):
        return
    if status != 404:
        require_success(status, body, "bucket check")
    status, body = request(endpoint, access, secret, "PUT", bucket_uri(bucket))
    require_success(status, body, "bucket creation", {200, 204})


def upload(endpoint: str, access: str, secret: str, bucket: str, key: str, file_path: Path) -> None:
    ensure_bucket(endpoint, access, secret, bucket)
    status, body = request(endpoint, access, secret, "PUT", bucket_uri(bucket, key), body_path=file_path)
    require_success(status, body, "backup upload", {200, 201})
    status, body = request(endpoint, access, secret, "HEAD", bucket_uri(bucket, key))
    require_success(status, body, "uploaded object check", {200, 204})


def download(endpoint: str, access: str, secret: str, bucket: str, key: str, destination: Path) -> None:
    status, body = request(endpoint, access, secret, "GET", bucket_uri(bucket, key), output_path=destination)
    require_success(status, body, "backup download", {200})


def prune(endpoint: str, access: str, secret: str, bucket: str, prefix: str, days: int) -> int:
    cutoff = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=days)
    token = None
    removed = 0
    while True:
        params = {"list-type": "2", "prefix": prefix}
        if token:
            params["continuation-token"] = token
        status, body = request(endpoint, access, secret, "GET", bucket_uri(bucket), query=params)
        require_success(status, body, "retention listing", {200})
        root = ET.fromstring(body)
        namespace = {"s3": "http://s3.amazonaws.com/doc/2006-03-01/"}
        for item in root.findall("s3:Contents", namespace):
            key = item.findtext("s3:Key", namespaces=namespace)
            modified = item.findtext("s3:LastModified", namespaces=namespace)
            if not key or not modified:
                continue
            modified_at = dt.datetime.fromisoformat(modified.replace("Z", "+00:00"))
            if modified_at < cutoff:
                status, body = request(endpoint, access, secret, "DELETE", bucket_uri(bucket, key))
                require_success(status, body, "old backup deletion", {200, 204})
                removed += 1
        truncated = root.findtext("s3:IsTruncated", namespaces=namespace) == "true"
        token = root.findtext("s3:NextContinuationToken", namespaces=namespace)
        if not truncated or not token:
            break
    return removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("upload", "download", "prune"))
    parser.add_argument("--endpoint", default=os.environ.get("MINIO_BACKUP_ENDPOINT", "http://127.0.0.1:9000"))
    parser.add_argument("--bucket", default=os.environ.get("MINIO_BUCKET", "codelab"))
    parser.add_argument("--access-key", default=os.environ.get("MINIO_ROOT_USER", ""))
    parser.add_argument("--secret-key", default=os.environ.get("MINIO_ROOT_PASSWORD", ""))
    parser.add_argument("--key")
    parser.add_argument("--file", type=Path)
    parser.add_argument("--days", type=int, default=7)
    args = parser.parse_args()
    if not args.access_key or not args.secret_key:
        parser.error("MINIO_ROOT_USER and MINIO_ROOT_PASSWORD are required")
    try:
        if args.operation == "upload":
            if not args.key or not args.file:
                parser.error("upload requires --key and --file")
            upload(args.endpoint, args.access_key, args.secret_key, args.bucket, args.key, args.file)
            print(f"uploaded {args.key}")
        elif args.operation == "download":
            if not args.key or not args.file:
                parser.error("download requires --key and --file")
            download(args.endpoint, args.access_key, args.secret_key, args.bucket, args.key, args.file)
            print(f"downloaded {args.key}")
        else:
            removed = prune(args.endpoint, args.access_key, args.secret_key, args.bucket, "backups/", args.days)
            print(f"removed {removed} expired backup(s)")
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI must report and exit non-zero for operational errors.
        print(f"backup operation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

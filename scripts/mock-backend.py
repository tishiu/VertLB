#!/usr/bin/env python3
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class MockBackendHandler(BaseHTTPRequestHandler):
    service = "mock-service"

    def do_GET(self):
        parsed = urlsplit(self.path)

        if parsed.path == "/health":
            self._write_json(200, {
                "service": self.service,
                "status": "ok"
            })
            return

        self._write_json(200, self._payload(parsed, None))

    def do_POST(self):
        parsed = urlsplit(self.path)
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8") if length > 0 else ""

        self._write_json(200, self._payload(parsed, body))

    def log_message(self, format, *args):
        return

    def _payload(self, parsed, body):
        forwarded_headers = {
            name: value
            for name, value in self.headers.items()
            if name.lower().startswith("x-forwarded-")
        }

        payload = {
            "service": self.service,
            "method": self.command,
            "path": parsed.path,
            "query": parsed.query,
            "forwardedHeaders": forwarded_headers
        }

        if body is not None:
            payload["body"] = body

        return payload

    def _write_json(self, status, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser(description="VertiLB mock backend")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--service", required=True)
    args = parser.parse_args()

    handler = type(
        "ConfiguredMockBackendHandler",
        (MockBackendHandler,),
        {"service": args.service}
    )

    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"{args.service} listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

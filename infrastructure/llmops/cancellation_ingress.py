"""Loopback 테스트 요청을 격리 네트워크의 고정된 세 HTTP 서비스에만 전달한다."""

from http.client import HTTPConnection, HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

UPSTREAMS = {8099: "cancellation-probe", 8000: "ops-service", 4200: "prefect"}
HOP_HEADERS = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}


class Relay(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Do not log authentication headers, request paths or bodies.

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        # No CONNECT, absolute URLs, redirects or caller-selected destinations.
        target = self.requestline.split()[1]  # BaseHTTPRequestHandler normalizes leading //.
        if not target.startswith("/") or target.startswith("//"):
            self.send_error(400)
            return
        if self.headers.get("Transfer-Encoding"):
            self.send_error(400)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 1_000_000:
                raise ValueError
        except ValueError:
            self.send_error(400)
            return
        upstream = HTTPConnection(*self.server.upstream, timeout=20)
        try:
            upstream.request(
                self.command,
                self.path,
                body=self.rfile.read(length) if length else None,
                headers={k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS},
            )
            response = upstream.getresponse()
            body = response.read()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in HOP_HEADERS | {"content-length", "server", "date"}:
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (OSError, HTTPException):
            self.send_error(502)
        finally:
            upstream.close()


if __name__ == "__main__":
    servers = []
    for port, host in UPSTREAMS.items():
        server = ThreadingHTTPServer(("0.0.0.0", port), Relay)
        server.upstream = (host, port)
        servers.append(server)
        if port != 4200:
            Thread(target=server.serve_forever, daemon=True).start()
    servers[-1].serve_forever()

"""The sandbox's test site (demo.sh): serves a folder, with HTTP basic auth on /auth/ (user eb, password secret),
plus a few endpoints for the loading / error checks of e2e.sh:
  /slow/<ms>      a page that takes <ms> milliseconds to answer
  /stall/<ms>     a page whose image takes <ms> milliseconds: the page shows, but its load doesn't end until then
  /status/<code>  a page with that HTTP status (e.g. 404, 500)
  /drop           closes the connection without an answer (Chromium: ERR_EMPTY_RESPONSE)
  /pdf            doc.pdf as application/pdf at a URL without .pdf (as a download.php?id=… would)
   python3 -I tools/sandbox/server.py <port> <folder>
"""
import base64
import functools
import sys
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

LOGIN = "Basic " + base64.b64encode(b"eb:secret").decode()


class Handler(SimpleHTTPRequestHandler):
    def send_page(self, status, title, body="", headers=()):
        data = f"<!doctype html><title>{title}</title><h1>{title}</h1>{body}".encode()
        self.send_response(status)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        return data

    def number(self, prefix):
        try:
            return int(self.path[len(prefix):].split("?")[0].split("/")[0])
        except ValueError:
            return 0

    def send_head(self):
        if self.path.startswith("/auth/") and self.headers.get("Authorization") != LOGIN:
            data = self.send_page(401, "EB Unauthorized", headers=[("WWW-Authenticate", 'Basic realm="EB Staging"')])
            self.wfile.write(data)
            return None
        if self.path.startswith("/slow/"):
            time.sleep(self.number("/slow/") / 1000)
            self.wfile.write(self.send_page(200, "EB Slow"))
            return None
        if self.path.startswith("/stall/"):
            ms = self.number("/stall/")
            self.wfile.write(self.send_page(200, "EB Stall", f'<img src="/stallimage/{ms}?{time.time()}">'))
            return None
        if self.path.startswith("/stallimage/"):
            time.sleep(self.number("/stallimage/") / 1000)
            self.send_error(404)
            return None
        if self.path.startswith("/status/"):
            code = self.number("/status/") or 500
            self.wfile.write(self.send_page(code, f"EB Status {code}"))
            return None
        if self.path.startswith("/pdf"):
            with open(f"{self.directory}/doc.pdf", "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return None
        if self.path.startswith("/drop"):
            self.close_connection = True
            return None
        return super().send_head()

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    port, folder = int(sys.argv[1]), sys.argv[2]
    ThreadingHTTPServer(("127.0.0.1", port), functools.partial(Handler, directory=folder)).serve_forever()

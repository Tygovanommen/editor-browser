"""The sandbox's test site (demo.sh): serves a folder, with HTTP basic auth on /auth/ (user eb, password secret).
   python3 -I tools/sandbox/server.py <port> <folder>
"""
import base64
import functools
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

LOGIN = "Basic " + base64.b64encode(b"eb:secret").decode()


class Handler(SimpleHTTPRequestHandler):
    def send_head(self):
        if self.path.startswith("/auth/") and self.headers.get("Authorization") != LOGIN:
            body = b"<!doctype html><title>EB Unauthorized</title><h1>401</h1>"
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="EB Staging"')
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return None
        return super().send_head()


if __name__ == "__main__":
    port, folder = int(sys.argv[1]), sys.argv[2]
    ThreadingHTTPServer(("127.0.0.1", port), functools.partial(Handler, directory=folder)).serve_forever()

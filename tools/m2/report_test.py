"""report.py 的本地契约测试：假后端校验登录/建制品/送审字段。"""
import json, os, subprocess, sys, tempfile, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

captured = []
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        captured.append((self.path, body, self.headers.get("Authorization")))
        if self.path == "/api/auth/login":
            payload = {"data": {"accessToken": "test-token"}}
        elif self.path == "/api/ai-artifacts":
            payload = {"data": {"id": 7}}
        elif self.path == "/api/ai-artifacts/7/submit":
            payload = {"data": {"status": "IN_REVIEW"}}
        else:
            payload = {}
        raw = json.dumps(payload).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)

server = HTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
with tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False) as f:
    f.write("diff --git a/x b/x\n"); patch = f.name
try:
    out = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "report.py"),
        "--base", f"http://127.0.0.1:{server.server_port}", "--password", "p",
        "--project", "1", "--fact", "71", "--branch", "m2/test",
        "--patch", patch, "--task", "\n\n测试任务"], text=True, capture_output=True)
    assert out.returncode == 0, out.stderr
    art = [body for path, body, _ in captured if path == "/api/ai-artifacts"][0]
    assert art["evidenceJson"] == "[71]", art
    assert art["branch"] == "m2/test"
    assert "\n" not in art["title"], art["title"]
    assert any(path == "/api/ai-artifacts/7/submit" for path, _, _ in captured)
    print("report.py contract ok")
finally:
    server.shutdown(); os.unlink(patch)

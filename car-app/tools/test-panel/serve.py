#!/usr/bin/env python3
"""M:YOU 테스트 패널 도우미 (Mac 에서 실행).

- 차량(Pleos) 에뮬레이터를 찾아 Virtual Console(8088)과 폰 동기화(9000) 포트를 연결한다.
- 패널 화면(index.html)과 앱의 휴게소 목록(rest_areas.json)을 보여 준다.
- POST /geo?lat=..&lng=.. : 차량 에뮬레이터 GPS 를 옮긴다 (adb emu geo fix).
127.0.0.1 에서만 열린다.  실행: python3 tools/test-panel/serve.py  → http://127.0.0.1:8090
"""
import json
import os
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
REST_AREAS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "rest_areas.json")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PORT = 8090


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=15)


def car_serial():
    """연결된 에뮬레이터 중 차량(모델명에 Car)인 것."""
    for line in adb("devices").stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            model = adb("-s", parts[0], "shell", "getprop", "ro.product.model").stdout
            if "car" in model.lower():
                return parts[0]
    return None


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            with open(os.path.join(HERE, "index.html"), "rb") as f:
                self._send(200, f.read(), "text/html; charset=utf-8")
        elif path == "/rest_areas.json":
            with open(REST_AREAS, "rb") as f:
                self._send(200, f.read())
        elif path == "/car":
            self._send(200, json.dumps({"serial": car_serial()}))
        else:
            self._send(404, '{"ok":false}')

    def do_POST(self):
        url = urlparse(self.path)
        if url.path != "/geo":
            return self._send(404, '{"ok":false}')
        q = parse_qs(url.query)
        try:
            lat, lng = float(q["lat"][0]), float(q["lng"][0])
            if not (-90 <= lat <= 90 and -180 <= lng <= 180):
                raise ValueError("range")
        except (KeyError, ValueError):
            return self._send(400, '{"ok":false,"error":"lat/lng"}')
        serial = car_serial()
        if not serial:
            return self._send(503, '{"ok":false,"error":"차량 에뮬레이터 없음"}')
        r = adb("-s", serial, "emu", "geo", "fix", str(lng), str(lat))  # geo fix 는 경도, 위도 순서
        ok = r.returncode == 0 and "OK" in r.stdout
        self._send(200 if ok else 500, json.dumps({"ok": ok}))

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    serial = car_serial()
    if serial:
        adb("-s", serial, "forward", "tcp:8088", "tcp:8088")  # Virtual Console
        adb("-s", serial, "forward", "tcp:9000", "tcp:9000")  # 폰 → 차량 프로필 동기화
        print(f"차량 에뮬레이터 {serial}: 포트 8088, 9000 연결")
    else:
        print("차량 에뮬레이터를 찾지 못했습니다. 에뮬레이터를 켠 뒤 다시 실행하세요.")
    print(f"테스트 패널: http://127.0.0.1:{PORT}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()

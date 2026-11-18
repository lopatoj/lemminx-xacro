#!/usr/bin/env python3
"""
End-to-end smoke test: start LemMinX (uber jar with this extension) over stdio, open the
fixture files like an editor would, and print diagnostics / hover / definition results.

    mvn -Puber package -DskipTests
    python3 scripts/lsp_smoke.py target/lemminx-xacro-*-uber.jar
"""
import glob
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WS = ROOT / "src/test/resources/ws"
URDF = WS / "src/demo_description/urdf"

jar = sys.argv[1] if len(sys.argv) > 1 else sorted(glob.glob(str(ROOT / "target/*-uber.jar")))[-1]
proc = subprocess.Popen(["java", "-jar", jar], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL)
lock = threading.Lock()
responses, diagnostics = {}, {}


def send(msg):
    body = json.dumps(msg).encode()
    with lock:
        proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        proc.stdin.flush()


def reader():
    while True:
        headers = {}
        while True:
            line = proc.stdout.readline()
            if not line:
                return
            line = line.decode().strip()
            if not line:
                break
            k, v = line.split(":", 1)
            headers[k.lower()] = v.strip()
        msg = json.loads(proc.stdout.read(int(headers["content-length"])))
        if "id" in msg and "method" not in msg:
            responses[msg["id"]] = msg
        elif msg.get("method") == "textDocument/publishDiagnostics":
            diagnostics[msg["params"]["uri"]] = msg["params"]["diagnostics"]
        elif "id" in msg and "method" in msg:  # server → client request (e.g. workspace/configuration)
            send({"jsonrpc": "2.0", "id": msg["id"], "result": None})


threading.Thread(target=reader, daemon=True).start()
_id = [0]


def request(method, params, timeout=20):
    _id[0] += 1
    i = _id[0]
    send({"jsonrpc": "2.0", "id": i, "method": method, "params": params})
    end = time.time() + timeout
    while time.time() < end:
        if i in responses:
            return responses.pop(i).get("result")
        time.sleep(0.02)
    raise TimeoutError(method)


def notify(method, params):
    send({"jsonrpc": "2.0", "method": method, "params": params})


settings = {"xml": {"xacro": {"python": {"enabled": True}}}}
request("initialize", {"processId": os.getpid(), "rootUri": WS.as_uri(),
                       "capabilities": {"textDocument": {"completion": {"completionItem": {"snippetSupport": True}},
                                                         "hover": {"contentFormat": ["markdown"]},
                                                         "definition": {"linkSupport": True}}},
                       "initializationOptions": {"settings": settings}})
notify("initialized", {})
notify("workspace/didChangeConfiguration", {"settings": settings})


def open_doc(name):
    p = URDF / name
    notify("textDocument/didOpen", {"textDocument": {"uri": p.as_uri(), "languageId": "xml", "version": 1,
                                                     "text": p.read_text()}})
    return p.as_uri()


def pos_of(name, needle, delta):
    text = (URDF / name).read_text()
    i = text.index(needle) + delta
    line = text.count("\n", 0, i)
    return {"line": line, "character": i - (text.rfind("\n", 0, i) + 1)}


uris = {n: open_doc(n) for n in ["robot.urdf.xacro", "broken.urdf.xacro", "dup.urdf.xacro", "wheel.xacro"]}
notify("textDocument/didSave", {"textDocument": {"uri": uris["dup.urdf.xacro"]}})
time.sleep(8)  # static diagnostics are immediate; the xacro (python) run happens in the background

for n, uri in uris.items():
    print("== diagnostics", n)
    for d in diagnostics.get(uri, []):
        if d.get("source", "").startswith("xacro"):
            print("   L%d %-15s %s" % (d["range"]["start"]["line"] + 1, d.get("source"), d["message"]))

h = request("textDocument/hover", {"textDocument": {"uri": uris["robot.urdf.xacro"]},
                                   "position": pos_of("robot.urdf.xacro", "<xacro:wheel prefix=\"left\"", 9)})
print("== hover on <xacro:wheel>\n" + (h["contents"]["value"] if h else "None"))
h = request("textDocument/hover", {"textDocument": {"uri": uris["robot.urdf.xacro"]},
                                   "position": pos_of("robot.urdf.xacro", "${base_width/2}", 4)})
print("== hover on ${base_width}\n" + (h["contents"]["value"] if h else "None"))
d = request("textDocument/definition", {"textDocument": {"uri": uris["wheel.xacro"]},
                                        "position": pos_of("wheel.xacro", "${wheel_radius}", 4)})
print("== definition of ${wheel_radius} in wheel.xacro")
for l in d or []:
    print("   ", l.get("targetUri", l.get("uri")).rsplit("/", 1)[-1], (l.get("targetSelectionRange") or l.get("range"))["start"])
c = request("textDocument/completion", {"textDocument": {"uri": uris["robot.urdf.xacro"]},
                                        "position": pos_of("robot.urdf.xacro", "${base_width/2}", 3)})
items = c["items"] if isinstance(c, dict) else c
print("== completion in ${...}:", [i["label"] for i in items][:8])

request("shutdown", None)
notify("exit", None)

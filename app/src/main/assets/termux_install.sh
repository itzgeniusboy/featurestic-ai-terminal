#!/data/data/com.termux/files/usr/bin/bash
# Featurestic AI Terminal - Termux server installer (self-contained)
# Bundled inside the app. No internet download needed (only pkg install python).
set -u
PREFIX=/data/data/com.termux/files/usr
export PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"

echo ""
echo "=== Featurestic AI Terminal - Termux setup ==="

echo ">> [1/4] allow-external-apps permission"
mkdir -p ~/.termux
grep -q 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings >/dev/null 2>&1 || true

echo ">> [2/4] installing python"
pkg install -y python >/dev/null 2>&1 || pkg install -y python || true

echo ">> [3/4] writing bridge server"
mkdir -p ~/txdost
cat > ~/txdost/server.py <<'FEATURESTIC_SERVER_PYEOF'
#!/usr/bin/env python3
# Featurestic AI Terminal — Termux Bridge Server v6.0
# - Command bridge for the Android app (run / write / live_log / info ...)
# - OpenAI-compatible model proxy  (/v1/chat/completions, /v1/models)
#   The app sends the real provider base in the "X-Provider-Base" header,
#   so requests are forwarded to that provider with streaming passthrough.
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
import subprocess, json, os, signal, time, urllib.request, urllib.error
import threading, queue, uuid

PORT = 8080
_PFX = "/data/data/com.termux/files/usr"
_HOM = "/data/data/com.termux/files/home"
os.environ["PATH"] = f"{_PFX}/bin:{_PFX}/bin/applets:{os.environ.get('PATH','')}"
os.environ.setdefault("HOME", _HOM)
os.environ.setdefault("PREFIX", _PFX)
os.environ.setdefault("TMPDIR", f"{_PFX}/tmp")
os.environ["DEBIAN_FRONTEND"] = "noninteractive"
_ENV = os.environ.copy()


def run_cmd(cmd, timeout=60, workdir=None):
    wd = workdir or os.environ["HOME"]
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                           timeout=timeout, cwd=wd, env=_ENV)
        out = (r.stdout or "").strip()
        err = (r.stderr or "").strip()
        combined = (out + "\n" + err).strip() if (out and err) else (out or err or "(no output)")
        return combined, r.returncode, r.returncode == 0
    except subprocess.TimeoutExpired:
        return f"Timeout ({timeout}s)", -1, False
    except Exception as e:
        return str(e), -1, False


# ── Persistent shell sessions (Termux-like: cd / env / state persist) ──
class ShellSession:
    """A long-lived `bash` process. Commands are written to its stdin and
    output is read until a unique end-marker, so working directory, exported
    variables, functions and aliases all persist between calls."""

    def __init__(self):
        self.proc = subprocess.Popen(
            ["bash"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1, cwd=_HOM, env=_ENV,
        )
        self.q = queue.Queue()
        self.lock = threading.Lock()
        self.counter = 0
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self):
        try:
            for line in self.proc.stdout:
                self.q.put(line)
        except Exception:
            pass
        finally:
            self.q.put(None)

    def run(self, cmd, timeout=120):
        with self.lock:
            self.counter += 1
            marker = f"__FSDONE_{os.getpid()}_{self.counter}__"
            try:
                self.proc.stdin.write(f"{cmd}\nprintf '{marker}%s\\n' \"$?\"\n")
                self.proc.stdin.flush()
            except Exception as e:
                return f"session closed: {e}", -1, False
            buf = []
            deadline = time.time() + timeout
            while True:
                remaining = deadline - time.time()
                if remaining <= 0:
                    buf.append("\n[Featurestic] command timed out (interactive apps not supported — use background run)")
                    return "".join(buf).strip(), -1, False
                try:
                    line = self.q.get(timeout=remaining)
                except queue.Empty:
                    buf.append("\n[Featurestic] command timed out (interactive apps not supported — use background run)")
                    return "".join(buf).strip(), -1, False
                if line is None:
                    return ("".join(buf).strip() + "\n[session ended]").strip(), -1, False
                if marker in line:
                    code = line.split(marker, 1)[1].strip()
                    code = int(code) if code.lstrip("-").isdigit() else 0
                    return "".join(buf).rstrip("\n"), code, code == 0
                buf.append(line)

    def kill(self):
        try:
            self.proc.terminate()
        except Exception:
            pass
        try:
            self.proc.kill()
        except Exception:
            pass


_SESSIONS = {}
_SESS_LOCK = threading.Lock()


def _get_or_create_session(sid):
    with _SESS_LOCK:
        s = _SESSIONS.get(sid) if sid else None
        if s is None:
            sid = uuid.uuid4().hex[:12]
            s = ShellSession()
            _SESSIONS[sid] = s
        return sid, s


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, data):
        b = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _body(self):
        n = int(self.headers.get("Content-Length", 0))
        if n <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(n))
        except Exception:
            return None

    # ── OpenAI-compatible model proxy ──────────────────────────
    def _provider_base(self):
        return (self.headers.get("X-Provider-Base") or "").strip().rstrip("/")

    def _proxy_stream(self, url, body=None):
        base = self._provider_base()
        if not base:
            self._send(400, {"error": "X-Provider-Base header required"})
            return
        target = base + url
        req = urllib.request.Request(target, data=body, method="POST" if body is not None else "GET")
        req.add_header("Content-Type", "application/json")
        auth = self.headers.get("Authorization")
        if auth:
            req.add_header("Authorization", auth)
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                self.send_response(resp.status)
                self.send_header("Content-Type", resp.headers.get("Content-Type", "application/json"))
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                while True:
                    chunk = resp.read(1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except urllib.error.HTTPError as e:
            data = e.read()
            self.send_response(e.code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            self._send(502, {"error": str(e)})

    # ── GET ────────────────────────────────────────────────────
    def do_GET(self):
        if self.path == "/ping":
            self._send(200, {"status": "ok", "version": "6.1", "name": "Featurestic AI Terminal"})
        elif self.path == "/v1/models":
            self._proxy_stream("/models")
        elif self.path == "/info":
            info = {}
            try:
                info["battery"] = open("/sys/class/power_supply/battery/capacity").read().strip() + "%"
            except Exception:
                info["battery"] = "N/A"
            try:
                info["battery_status"] = open("/sys/class/power_supply/battery/status").read().strip()
            except Exception:
                info["battery_status"] = "N/A"
            try:
                m = open("/proc/meminfo").readlines()
                t = int(m[0].split()[1]) // 1024
                f = int(m[2].split()[1]) // 1024
                info.update({"ram_total_mb": t, "ram_free_mb": f, "ram_used_mb": t - f})
            except Exception:
                info["ram"] = "N/A"
            try:
                r = subprocess.run("df -h /sdcard | tail -1", shell=True, capture_output=True, text=True)
                p = r.stdout.split()
                info.update({"storage_total": p[1] if len(p) > 1 else "N/A",
                             "storage_used": p[2] if len(p) > 2 else "N/A",
                             "storage_free": p[3] if len(p) > 3 else "N/A"})
            except Exception:
                info["storage"] = "N/A"
            self._send(200, info)
        elif self.path == "/list_processes":
            out, _, ok = run_cmd("ps aux | grep -E '(python|node|bash|perl)' | grep -v grep")
            self._send(200, {"output": out, "success": ok})
        elif self.path == "/session/list":
            with _SESS_LOCK:
                ids = list(_SESSIONS.keys())
            self._send(200, {"sessions": ids, "count": len(ids)})
        elif self.path.startswith("/file?"):
            path = os.path.expanduser(self.path.split("?", 1)[1])
            try:
                self._send(200, {"content": open(path).read(), "success": True})
            except Exception as e:
                self._send(200, {"error": str(e), "success": False})
        else:
            self._send(404, {"error": "Not found"})

    # ── POST ───────────────────────────────────────────────────
    def do_POST(self):
        if self.path == "/v1/chat/completions":
            n = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(n)
            self._proxy_stream("/chat/completions", body=body)
            return

        d = self._body()
        if d is None:
            self._send(400, {"error": "Invalid JSON"})
            return

        if self.path == "/session/new":
            sid, _ = _get_or_create_session(None)
            self._send(200, {"id": sid, "success": True})
        elif self.path == "/session/run":
            cmd = d.get("command", "")
            if not cmd.strip():
                self._send(400, {"error": "command required"})
                return
            timeout = min(int(d.get("timeout", 120)), 600)
            sid, sess = _get_or_create_session(d.get("id"))
            out, code, ok = sess.run(cmd, timeout)
            self._send(200, {"id": sid, "output": out, "exit_code": code, "success": ok})
        elif self.path == "/session/kill":
            sid = d.get("id", "")
            with _SESS_LOCK:
                sess = _SESSIONS.pop(sid, None)
            if sess:
                sess.kill()
                self._send(200, {"success": True})
            else:
                self._send(200, {"success": False, "error": "no such session"})
        elif self.path == "/run":
            cmd = d.get("command", "").strip()
            timeout = min(int(d.get("timeout", 60)), 600)
            wd = os.path.expanduser(d.get("workdir", "~"))
            if not cmd:
                self._send(400, {"error": "command required"})
                return
            out, code, ok = run_cmd(cmd, timeout, wd)
            self._send(200, {"output": out, "exit_code": code, "success": ok})
        elif self.path == "/run_multi":
            results = []
            for cmd in d.get("commands", []):
                if cmd.strip():
                    out, code, ok = run_cmd(cmd.strip(), 120)
                    results.append({"command": cmd, "output": out, "success": ok, "exit_code": code})
            self._send(200, {"results": results})
        elif self.path == "/install":
            pkg = d.get("package", "").strip()
            if not pkg:
                self._send(400, {"error": "package required"})
                return
            if pkg.startswith("pip:"):
                out, code, ok = run_cmd(f"pip install {pkg[4:].strip()} 2>&1", 180)
            else:
                out, code, ok = run_cmd(f"pkg install -y {pkg} 2>&1", 300)
            self._send(200, {"output": out, "success": ok, "exit_code": code})
        elif self.path == "/write":
            path = os.path.expanduser(d.get("path", "").strip())
            content = d.get("content", "")
            if not path:
                self._send(400, {"error": "path required"})
                return
            try:
                os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
                open(path, "w", encoding="utf-8").write(content)
                self._send(200, {"success": True, "message": f"Written: {path}", "size": len(content)})
            except Exception as e:
                self._send(200, {"success": False, "error": str(e)})
        elif self.path == "/kill":
            kw = d.get("keyword", d.get("script", "")).strip()
            if not kw:
                self._send(400, {"error": "keyword required"})
                return
            try:
                my = str(os.getpid())
                r = subprocess.run(f"pgrep -f '{kw}'", shell=True, capture_output=True, text=True)
                pids = [p for p in r.stdout.strip().split() if p and p != my]
                if not pids:
                    self._send(200, {"output": f"No process: '{kw}'", "success": False, "killed": []})
                    return
                killed = []
                for pid in pids:
                    try:
                        os.kill(int(pid), signal.SIGTERM)
                        killed.append(pid)
                    except Exception:
                        try:
                            os.kill(int(pid), signal.SIGKILL)
                            killed.append(pid)
                        except Exception:
                            pass
                time.sleep(0.5)
                still = [p for p in subprocess.run(f"pgrep -f '{kw}'", shell=True, capture_output=True,
                                                   text=True).stdout.strip().split() if p and p != my]
                for p in still:
                    try:
                        os.kill(int(p), signal.SIGKILL)
                    except Exception:
                        pass
                self._send(200, {"output": f"Killed {len(killed)} process(es): {', '.join(killed)}",
                                 "success": True, "killed": killed})
            except Exception as e:
                self._send(200, {"output": str(e), "success": False, "killed": []})
        elif self.path == "/run_bg":
            cmd = d.get("command", "").strip()
            logfile = os.path.expanduser(d.get("logfile", "~/txdost_bg.log"))
            if not cmd:
                self._send(400, {"error": "command required"})
                return
            try:
                proc = subprocess.Popen(f"nohup {cmd} >> {logfile} 2>&1", shell=True, start_new_session=True)
                self._send(200, {"output": f"Started PID:{proc.pid} Log:{logfile}", "success": True,
                                 "pid": proc.pid, "logfile": logfile})
            except Exception as e:
                self._send(200, {"output": str(e), "success": False})
        elif self.path == "/live_log":
            logfile = os.path.expanduser(d.get("logfile", "").strip())
            lines = int(d.get("lines", 100))
            since_byte = int(d.get("since_byte", 0))
            if not logfile:
                self._send(400, {"error": "logfile required"})
                return
            try:
                if not os.path.exists(logfile):
                    self._send(200, {"content": "", "file_size": 0, "success": True})
                    return
                file_size = os.path.getsize(logfile)
                if since_byte > 0 and since_byte < file_size:
                    with open(logfile, "r", errors="replace") as f:
                        f.seek(since_byte)
                        content = f.read()
                else:
                    out, _, _ = run_cmd(f"tail -{lines} '{logfile}'", 5)
                    content = "" if out == "(no output)" else out
                self._send(200, {"content": content, "file_size": file_size, "success": True})
            except Exception as e:
                self._send(200, {"content": "", "file_size": 0, "success": False, "error": str(e)})
        elif self.path == "/read_log":
            logfile = os.path.expanduser(d.get("logfile", "~/txdost_bg.log"))
            lines = int(d.get("lines", 30))
            try:
                out, _, _ = run_cmd(f"tail -{lines} '{logfile}'", 10)
                self._send(200, {"content": out if out != "(no output)" else "(empty)", "success": True})
            except Exception as e:
                self._send(200, {"content": str(e), "success": False})
        else:
            self._send(404, {"error": f"Unknown: {self.path}"})


if __name__ == "__main__":
    s = Server(("127.0.0.1", PORT), H)
    print(f"[Featurestic AI Terminal bridge v6.1] Port {PORT} | sessions + model-proxy ready")
    try:
        s.serve_forever()
    except KeyboardInterrupt:
        print("Stopped.")
FEATURESTIC_SERVER_PYEOF

echo ">> [4/4] auto-start + launch"
AUTO='(cd ~/txdost && pgrep -f "txdost/server.py" >/dev/null || (nohup python server.py >~/txdost/server.log 2>&1 & disown))'
grep -q 'txdost/server.py' ~/.bashrc 2>/dev/null || echo "$AUTO" >> ~/.bashrc
pkill -f "txdost/server.py" >/dev/null 2>&1 || true
sleep 1
cd ~/txdost && nohup python server.py > ~/txdost/server.log 2>&1 &
sleep 2

if python -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8080/ping',timeout=5).read().decode())" 2>/dev/null; then
  echo ""
  echo "OK - Server online! App me TERMUX:ON dikhega."
else
  echo ""
  echo "FAIL - Ping failed. Log: cat ~/txdost/server.log"
fi
echo ""

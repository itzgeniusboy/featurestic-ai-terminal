#!/data/data/com.termux/files/usr/bin/bash
# Featurestic AI Terminal — one-line Termux server installer
# Usage (paste in Termux):
#   curl -fsSL https://raw.githubusercontent.com/itzgeniusboy/featurestic-ai-terminal/main/termux/install.sh | bash
set -u

RAW_BASE="https://raw.githubusercontent.com/itzgeniusboy/featurestic-ai-terminal/main/termux"
PREFIX="/data/data/com.termux/files/usr"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"

echo ""
echo "=== Featurestic AI Terminal — Termux server setup ==="
echo ""

# 1) Allow external apps (so the app can run commands in Termux) ──
echo ">> [1/5] allow-external-apps permission"
mkdir -p ~/.termux
grep -q 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null \
  || echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings >/dev/null 2>&1 || true

# 2) Install python + curl ───────────────────────────────────────
echo ">> [2/5] installing python + curl"
pkg install -y python curl >/dev/null 2>&1 || pkg install -y python || true

# 3) Download bridge server ──────────────────────────────────────
echo ">> [3/5] downloading server.py"
mkdir -p ~/txdost
if curl -fsSL "$RAW_BASE/server.py" -o ~/txdost/server.py; then
  echo "   server.py saved"
else
  echo "   ✗ download failed — check internet / try again"
  exit 1
fi

# 4) Auto-start on every Termux open ─────────────────────────────
echo ">> [4/5] enabling auto-start"
AUTO='(cd ~/txdost && pgrep -f "txdost/server.py" >/dev/null || (nohup python server.py >~/txdost/server.log 2>&1 & disown))'
grep -q 'txdost/server.py' ~/.bashrc 2>/dev/null || echo "$AUTO" >> ~/.bashrc

# 5) Start now + verify ──────────────────────────────────────────
echo ">> [5/5] starting server"
pkill -f "txdost/server.py" >/dev/null 2>&1 || true
sleep 1
cd ~/txdost && nohup python server.py > ~/txdost/server.log 2>&1 &
sleep 2

if curl -fsS http://127.0.0.1:8080/ping; then
  echo ""
  echo "✅ Server online! App me TERMUX:ON dikhega."
else
  echo ""
  echo "✗ Server ping failed. Log: cat ~/txdost/server.log"
fi
echo ""
echo "Har baar Termux kholne par server auto-start hoga (~/.bashrc)."

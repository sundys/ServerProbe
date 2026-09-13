#!/usr/bin/env bash
# ServerProbe 探针一键安装脚本（Linux, systemd）
# 用法（root 执行）：
#   bash install.sh --token <TOKEN> [--port 9822] [--binary /path/to/serverprobe] [--download URL]
# 二进制查找顺序：--binary 参数 > --download 下载 > 脚本同目录 serverprobe-linux-<arch> > 同目录 serverprobe
set -euo pipefail

PORT="9822"
DIR="/etc/serverprobe"
TOKEN=""
BINARY=""
DOWNLOAD_URL=""

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo -e "\033[32m[serverprobe]\033[0m $*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --token)    TOKEN="${2:-}"; shift 2 ;;
    --port)     PORT="${2:-}"; shift 2 ;;
    --binary)   BINARY="${2:-}"; shift 2 ;;
    --download) DOWNLOAD_URL="${2:-}"; shift 2 ;;
    --dir)      DIR="${2:-}"; shift 2 ;;
    -h|--help)  sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ "$(id -u)" == "0" ]] || die "please run as root"
[[ -n "$TOKEN" ]] || die "--token is required (see 'serverprobe init' output)"
[[ "$TOKEN" =~ ^[A-Za-z0-9_-]{20,}$ ]] || die "token looks invalid"

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)          GOARCH="amd64" ;;
  aarch64|arm64)   GOARCH="arm64" ;;
  armv7l|armv6l)   GOARCH="arm" ;;
  i686|i386)       GOARCH="386" ;;
  *) die "unsupported arch: $ARCH" ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "$BINARY" && -n "$DOWNLOAD_URL" ]]; then
  BINARY="/tmp/serverprobe.download"
  log "downloading binary from $DOWNLOAD_URL"
  curl -fsSL "$DOWNLOAD_URL" -o "$BINARY"
fi
if [[ -z "$BINARY" && -f "$SCRIPT_DIR/serverprobe-linux-$GOARCH" ]]; then
  BINARY="$SCRIPT_DIR/serverprobe-linux-$GOARCH"
fi
if [[ -z "$BINARY" && -f "$SCRIPT_DIR/serverprobe" ]]; then
  BINARY="$SCRIPT_DIR/serverprobe"
fi
[[ -n "$BINARY" && -f "$BINARY" ]] || die "binary not found (use --binary or --download)"

log "installing binary -> /usr/local/bin/serverprobe"
install -m 0755 "$BINARY" /usr/local/bin/serverprobe

mkdir -p "$DIR"
chmod 700 "$DIR"

log "writing config to $DIR/config.json"
INIT_OUT="$(/usr/local/bin/serverprobe init --dir "$DIR" --listen ":$PORT" --token "$TOKEN")"

cat > /etc/systemd/system/serverprobe.service <<EOF
[Unit]
Description=ServerProbe monitoring agent
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/serverprobe serve
Restart=always
RestartSec=3
NoNewPrivileges=yes
ProtectHome=yes
PrivateTmp=yes
RestrictSUIDSGID=yes
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now serverprobe
sleep 1
systemctl is-active --quiet serverprobe || { journalctl -u serverprobe --no-pager -n 20; die "service failed to start"; }

FINGERPRINT="$(echo "$INIT_OUT" | awk '/^fingerprint:/ {print $2}')"
ACTUAL_TOKEN="$(echo "$INIT_OUT" | awk '/^token:/ {print $2}')"

echo ""
log "ServerProbe installed and running."
echo "  ----------------------------------------------------------"
echo "  在 Android App「添加探针」中填写以下信息："
echo "  地址/Address   : <服务器IP>:$PORT"
echo "  Token          : $ACTUAL_TOKEN"
echo "  证书指纹/SHA-256: $FINGERPRINT"
echo "  ----------------------------------------------------------"
log "防火墙请放行 TCP $PORT；指纹建议一并填入 App 以防中间人。"

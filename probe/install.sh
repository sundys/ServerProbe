#!/usr/bin/env bash
# ServerProbe 探针一键安装脚本（Linux, systemd）
# 一键用法（脚本从 /tmp 运行，结束后自动删除）：
#   curl -fsSL -o /tmp/sp-install.sh https://raw.githubusercontent.com/sundys/ServerProbe/main/probe/install.sh && sudo bash /tmp/sp-install.sh
# 手动用法（root）：
#   bash install.sh [--token TOKEN] [--port 9822] [--binary PATH] [--download URL] [--dir DIR]
#
# Token 可省略：首次安装自动生成；重复安装沿用既有 config.json 中的 Token（证书指纹同理）。
# 二进制查找顺序：--binary > --download > 脚本同目录 > 从最新 Release 自动下载。
set -euo pipefail

PORT="9822"
DIR="/etc/serverprobe"
TOKEN=""
BINARY=""
DOWNLOAD_URL=""
RELEASE_BASE="${SP_RELEASE_BASE:-https://github.com/sundys/ServerProbe/releases/latest/download}"
TMP_DIR=""

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo -e "\033[32m[serverprobe]\033[0m $*"; }

cleanup() {
  [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]] && rm -rf -- "$TMP_DIR"
  # 一键命令会把脚本下载到 /tmp：运行结束自动删除，保持系统干净整洁。
  # 从仓库目录直接执行时不动原文件。
  case "${BASH_SOURCE[0]:-}" in
    /tmp/*|/var/tmp/*) rm -f -- "${BASH_SOURCE[0]}" 2>/dev/null || true ;;
  esac
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --token)    TOKEN="${2:-}"; shift 2 ;;
    --port)     PORT="${2:-}"; shift 2 ;;
    --binary)   BINARY="${2:-}"; shift 2 ;;
    --download) DOWNLOAD_URL="${2:-}"; shift 2 ;;
    --dir)      DIR="${2:-}"; shift 2 ;;
    -h|--help)  sed -n '2,10p' "${BASH_SOURCE[0]:-$0}"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ "$(id -u)" == "0" ]] || die "please run as root"
if [[ -n "$TOKEN" ]]; then
  [[ "$TOKEN" =~ ^[A-Za-z0-9_-]{20,}$ ]] || die "token looks invalid"
fi

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)          GOARCH="amd64" ;;
  aarch64|arm64)   GOARCH="arm64" ;;
  armv7l|armv6l)   GOARCH="arm" ;;
  i686|i386)       GOARCH="386" ;;
  *) die "unsupported arch: $ARCH" ;;
esac

TMP_DIR="$(mktemp -d)"

if [[ -z "$BINARY" && -n "$DOWNLOAD_URL" ]]; then
  log "downloading binary from $DOWNLOAD_URL"
  BINARY="$TMP_DIR/serverprobe"
  curl -fsSL "$DOWNLOAD_URL" -o "$BINARY" || die "download failed"
fi

# 解压后的 Release 包：脚本与二进制同目录，直接使用，避免重复下载
SCRIPT_DIR=""
SELF="${BASH_SOURCE[0]:-}"
if [[ -n "$SELF" && -f "$SELF" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "$SELF")" && pwd)"
fi
if [[ -z "$BINARY" && -n "$SCRIPT_DIR" && -f "$SCRIPT_DIR/serverprobe-linux-$GOARCH" ]]; then
  BINARY="$SCRIPT_DIR/serverprobe-linux-$GOARCH"
fi
if [[ -z "$BINARY" && -n "$SCRIPT_DIR" && -f "$SCRIPT_DIR/serverprobe" ]]; then
  BINARY="$SCRIPT_DIR/serverprobe"
fi

# 从最新 Release 自动下载对应架构的探针包（一键用法走这里）
if [[ -z "$BINARY" ]]; then
  URL="$RELEASE_BASE/serverprobe-linux-$GOARCH.tar.gz"
  log "downloading $URL"
  curl -fsSL "$URL" -o "$TMP_DIR/pkg.tar.gz" \
    || die "download failed（国内网络可用 SP_RELEASE_BASE 指定镜像前缀）"
  tar -xzf "$TMP_DIR/pkg.tar.gz" -C "$TMP_DIR" || die "extract failed"
  BINARY="$TMP_DIR/serverprobe"
fi
[[ -f "$BINARY" ]] || die "binary not found: $BINARY"
chmod +x "$BINARY"
# 替换前先确认二进制能在本机运行（防错误架构/损坏文件）
"$BINARY" version >/dev/null 2>&1 || die "binary is not runnable (wrong arch or corrupt): $BINARY"

log "installing binary -> /usr/local/bin/serverprobe"
# 原子替换：目标可能正在运行，直接覆盖会报 "Text file busy"
install -m 0755 "$BINARY" /usr/local/bin/.serverprobe.new
mv -f /usr/local/bin/.serverprobe.new /usr/local/bin/serverprobe

mkdir -p "$DIR"
chmod 700 "$DIR"

# 不传 --token 时 init 会复用已有 Token（首次安装则自动生成）
log "writing config to $DIR"
if [[ -n "$TOKEN" ]]; then
  INIT_OUT="$(/usr/local/bin/serverprobe init --dir "$DIR" --listen ":$PORT" --token "$TOKEN")"
else
  INIT_OUT="$(/usr/local/bin/serverprobe init --dir "$DIR" --listen ":$PORT")"
fi

cat > /etc/systemd/system/serverprobe.service <<EOF
[Unit]
Description=ServerProbe monitoring agent
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/serverprobe serve --config "$DIR/config.json"
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
systemctl enable serverprobe
# 重装时进程仍持有旧二进制，必须 restart 才能生效
systemctl restart serverprobe
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

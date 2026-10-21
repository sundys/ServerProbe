#!/usr/bin/env bash
# ServerProbe 探针一键更新脚本（Linux, systemd）
# 只替换 /usr/local/bin/serverprobe 并重启服务，不改动 /etc/serverprobe 下的
# config.json / cert.pem / key.pem —— Token 与证书指纹保持不变。
# 一键用法（脚本从 /tmp 运行，结束后自动删除）：
#   curl -fsSL -o /tmp/sp-update.sh https://raw.githubusercontent.com/sundys/ServerProbe/main/probe/update.sh && sudo bash /tmp/sp-update.sh
# 手动用法（root）：
#   bash update.sh [--binary PATH] [--download URL] [--service serverprobe] [--dir DIR]
#
# 二进制查找顺序：--binary > --download > 从最新 Release 自动下载。
# 国内网络可用环境变量 SP_RELEASE_BASE 指定镜像前缀。
set -euo pipefail

SERVICE="serverprobe"
TARGET="/usr/local/bin/serverprobe"
DIR="/etc/serverprobe"
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
    --binary)   BINARY="${2:-}"; shift 2 ;;
    --download) DOWNLOAD_URL="${2:-}"; shift 2 ;;
    --service)  SERVICE="${2:-}"; shift 2 ;;
    --dir)      DIR="${2:-}"; shift 2 ;;
    -h|--help)  sed -n '2,12p' "${BASH_SOURCE[0]:-$0}"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ "$(id -u)" == "0" ]] || die "please run as root"
command -v systemctl >/dev/null 2>&1 || die "systemctl not found (systemd is required)"
command -v curl >/dev/null 2>&1 || die "curl not found"
[[ -f "$DIR/config.json" ]] || die "config not found: $DIR/config.json（请先执行一键安装）"

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
# 替换前先确认二进制能在本机运行（防错误架构/损坏文件把服务换挂）
"$BINARY" version >/dev/null 2>&1 || die "binary is not runnable (wrong arch or corrupt): $BINARY"

# 原子替换：先写同目录临时文件再 mv，避免覆盖运行中的二进制报 "Text file busy"
log "updating $TARGET"
install -m 0755 "$BINARY" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

log "restarting $SERVICE"
systemctl restart "$SERVICE"
sleep 1
systemctl is-active --quiet "$SERVICE" || {
  journalctl -u "$SERVICE" --no-pager -n 20 || true
  die "$SERVICE failed to start"
}

echo ""
log "binary updated; config untouched (Token 与证书指纹保持不变)."
systemctl is-active "$SERVICE" | sed 's/^/  service: /'
/usr/local/bin/serverprobe show --dir "$DIR" 2>/dev/null \
  | sed -n 's/^\(listen\|tls\|fingerprint\):/  \1:/p' || true

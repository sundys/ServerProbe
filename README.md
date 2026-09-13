# ServerProbe 服务探针管理系统

[![Release](https://img.shields.io/github/v/release/sundys/ServerProbe?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/sundys/ServerProbe/releases)

管理多台服务器运行状态的 **Android App** + 部署在服务器上的 **Go 探针 Agent**。

> 开源主页：<https://github.com/sundys/ServerProbe>

```
ServerProbe/
├── docs/开发清单.md          # 完整功能清单与安全设计
├── probe/                    # Go 探针（零第三方依赖）
│   ├── install.sh            # 一键安装脚本
│   └── systemd/              # systemd 服务单元
├── app-android/              # Kotlin + Jetpack Compose 管理端
├── .github/workflows/        # 推送 v* 标签自动编译发布（Android armv7/arm64 + 探针 amd64/arm64）
└── CHANGELOG.md              # 每个版本的更新摘要（发布时自动写入 Release 页）
```

## 功能

- **首页大卡片**：左右滑动切换主机，卡片实时显示 CPU / 内存 / 磁盘 / 网速 / 负载 / 运行时长与 CPU 走势图，点击进入详情
- **主机详情**：每核 CPU、内存/交换、多磁盘、网卡速率、systemd 服务列表（支持启动/停止/重启），底部 **SSH 快速连接** 菜单
- **SSH 终端**：密码 + 私钥认证；私钥兼容 OpenSSH 新格式（RSA/ECDSA/Ed25519）、PEM PKCS#1、PKCS#8、PuTTY PPK v2/v3；自研 VT100/256 色终端，首次连接指纹确认（TOFU）
- **添加探针**：表单内置 **SSH 设置项**（绑定已有身份或新建），支持连接测试、证书指纹获取与锁定
- **设置**：日间 / 夜间 / 跟随系统；刷新间隔；生物识别锁；**备份与恢复**（口令加密，PBKDF2 + AES-256-GCM）

## 安全设计

| 层 | 措施 |
|---|---|
| 传输 | 探针强制 TLS（自签 ECDSA），App 支持证书 SHA-256 指纹锁定；明文流量被网络策略全局禁止 |
| 认证 | 32 字节随机 Token，服务端常量时间比较；每 IP 限流（默认 120 次/分） |
| 写操作 | 服务控制独立开关 + 服务名白名单正则 + exec 数组传参（防注入） |
| 存储 | 密码/私钥/Token 经 Android Keystore（AES-256-GCM）加密入库；备份文件口令加密 |
| 应用 | allowBackup=false；生物识别锁；SSH 主机密钥 TOFU 锁定 |

## 探针部署（Linux）

```bash
# 1. 上传 probe/dist/serverprobe-linux-amd64 与 probe/install.sh 到服务器
# 2. 生成配置（输出 Token 与证书指纹）
sudo ./serverprobe-linux-amd64 init
# 3. 一键安装（systemd 常驻 + 开机自启）
sudo bash install.sh --token <上面输出的Token>
# 防火墙放行
sudo ufw allow 9822/tcp   # 或 firewalld/iptables
```

`init` 支持参数：`--listen :9822`、`--token xxx`（自定义）、`--insecure-tls`（关闭 TLS，不推荐）。
查看现有配置：`serverprobe show`。服务控制权限：默认以 root 运行；如需降权，自行配置 polkit 规则并编辑 unit 的 `User=`。

## App 构建

```bash
cd app-android
./gradlew assembleDebug        # 产物: app/build/outputs/apk/debug/
./gradlew test                 # 备份加密与终端模拟器单元测试
```

要求：JDK 17、Android SDK (compileSdk 36)。正式发布直接 **推送 `v*` 标签**，GitHub Actions 自动编译 Android（armv7/arm64）与探针（amd64/arm64）并创建带更新摘要的 Release；本地构建正式包用 `./gradlew assembleRelease -PappVersion=x.y.z`。

> 签名说明：仓库内 `app/signing/serverprobe.keystore` 为个人项目固定签名（保证更新包可覆盖安装）。如公开维护，建议改用 GitHub Secrets 注入签名。

## App 使用

1. 「添加 → 添加探针主机」填写地址 / 端口 / Token，建议点「获取指纹」并核对后锁定
2. 「添加 → 添加 SSH 主机」选择密码或私钥登录方式（支持各类主流私钥格式）
3. 首页大卡片滑动查看主机，点击进详情；下方 SSH 列表点击直接进终端

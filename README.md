# 云枢 Remoto — 服务探针管理系统

[![Release](https://img.shields.io/github/v/release/sundys/ServerProbe?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/sundys/ServerProbe/releases)

管理多台服务器运行状态的 **Android App（云枢 / Remoto）** + 部署在服务器上的 **Go 探针 Agent**。

> 开源主页：<https://github.com/sundys/ServerProbe>

```
ServerProbe/
├── docs/开发清单.md          # 完整功能清单与安全设计
├── probe/                    # Go 探针（零第三方依赖）
│   ├── install.sh            # 一键安装脚本
│   └── systemd/              # systemd 服务单元
├── app-android/              # Kotlin + Jetpack Compose 管理端（applicationId: com.sundys.remoto）
├── .github/workflows/        # 推送 v* 标签自动编译发布（Android armv7/arm64 + 探针 amd64/arm64）
└── CHANGELOG.md              # 每个版本的更新摘要（发布时自动写入 Release 页）
```

- **流量统计**：探针持久化累计今日/本月/总用量（重启不清零，日/月自动翻转，过滤容器虚拟网卡），详情页与首页卡片展示，固定 MB 单位

## 功能

- **三 tab 导航**：管理（探针主机）/ 远程（ZCode 远程控制）/ 设置
- **首页大卡片**：左右滑动切换主机，卡片实时显示 CPU / 内存 / 磁盘 / 网速 / 负载 / 运行时长与 CPU 走势图，点击进入详情；右上角「＋」添加菜单
- **主机详情**：每核 CPU、内存/交换、多磁盘、网卡速率、systemd 服务列表（支持启动/停止/重启），底部 **SSH 快速连接** 菜单
- **SSH 终端**：密码 + 私钥认证；私钥兼容 OpenSSH 新格式（RSA/ECDSA/Ed25519）、PEM PKCS#1、PKCS#8、PuTTY PPK v2/v3；自研 VT100/256 色终端，首次连接指纹确认（TOFU）；选私钥支持 6 通道文件选择回退 + 直接粘贴（国产 ROM 优化）
- **远程（Zrmt 集成）**：ZCode 远程控制链接卡片管理，App 内 WebView 操作界面（UA 切换/重试/文件上传/摄像头麦克风授权），支持系统分享接入
- **添加探针**：表单内置 **SSH 设置项**（绑定已有身份或新建），支持连接测试、证书指纹获取与锁定
- **设置**：日间 / 夜间 / 跟随系统；刷新间隔；生物识别锁（仅启动时验证一次）；**备份与恢复**（口令加密）；**应用内检测更新**（多通道代理，下载进度可视化）

## 探针一键安装（Linux / systemd）

先在服务器上生成配置拿到 Token（也可以只跑一次 `init`）：

```bash
sudo bash -c 'mkdir -p /etc/serverprobe && curl -fsSL -o /tmp/sp.tar.gz https://github.com/sundys/ServerProbe/releases/latest/download/serverprobe-linux-$(uname -m | sed "s/x86_64/amd64/;s/aarch64/arm64/").tar.gz && rm -rf /tmp/sp && mkdir /tmp/sp && tar -xzf /tmp/sp.tar.gz -C /tmp/sp && bash /tmp/sp/install.sh --token $(/tmp/sp/serverprobe init | awk "/^token:/{print \$2}") && rm -rf /tmp/sp /tmp/sp.tar.gz'
```

命令说明：自动识别 amd64/arm64 架构 → 从最新 Release 下载探针包 → 解压 → 执行 `serverprobe init` 生成随机 Token 与自签证书 → `install.sh` 安装并注册 systemd 服务（开机自启），终端会打印 **Token 与证书指纹**（App「添加探针」时使用）。

> - 国内服务器若访问 GitHub 失败，把命令中的 `https://github.com/` 替换为 `https://gh-proxy.com/`（或 ghfast.top 等加速前缀）重试
> - 防火墙放行端口：`sudo ufw allow 9822/tcp`（或 firewalld / 云厂商安全组）
> - 查看现有配置：`sudo serverprobe show`

## App 构建

```bash
cd app-android
./gradlew assembleDebug        # 产物: app/build/outputs/apk/debug/
./gradlew test                 # 备份加密 / 终端模拟器 / 版本比较单元测试
```

要求：JDK 17、Android SDK (compileSdk 36)。正式发布直接 **推送 `v*` 标签**，GitHub Actions 自动编译 Android（armv7/arm64）与探针（amd64/arm64）并创建带更新摘要的 Release；本地构建正式包用 `./gradlew assembleRelease -PappVersion=x.y.z`。

> 签名说明：仓库内 `app/signing/serverprobe.keystore` 为个人项目固定签名（保证更新包可覆盖安装）。如公开维护，建议改用 GitHub Secrets 注入签名。

## App 使用

1. 「＋ → 添加探针主机」填写地址 / 端口 / Token，建议点「获取指纹」并核对后锁定
2. 「＋ → 添加 SSH 主机」选择密码或私钥登录方式（支持各类主流私钥格式）
3. 首页大卡片滑动查看主机，点击进详情；下方 SSH 列表点击直接进终端
4. 设置 → 检测更新：多通道检测并下载新版本

## 常见问题

- **文件选择/权限**：选择私钥文件基于系统 SAF 机制，App **无需存储权限**（仅申请网络、生物识别、安装更新三项）。部分 ROM 会在文件选择器打开期间查杀后台应用，建议在系统设置中将云枢的电池策略设为「无限制」；如仍异常，可改用私钥「直接粘贴输入」，或通过 设置 → 关于 → 复制诊断日志 反馈。

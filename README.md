# 云枢 Remoto — 服务探针管理系统

[![Release](https://img.shields.io/github/v/release/sundys/ServerProbe?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/sundys/ServerProbe/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

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
- **远程（Zrmt 集成）**：ZCode 远程控制链接卡片管理，右上角「＋」二级菜单支持**手动粘贴**与**扫码添加**；App 内 WebView 操作界面（UA 切换/重试/文件上传/摄像头麦克风授权），支持系统分享接入；链接解析与版本号解耦，v4 及后续版本、`#` 片段参数、整段分享文字均可识别
- **添加探针**：表单内置 **SSH 设置项**（绑定已有身份或新建），支持连接测试、证书指纹获取与锁定
- **设置**：日间 / 夜间 / 跟随系统；刷新间隔；生物识别锁（仅启动时验证一次）；**备份与恢复**（口令加密）；**应用内检测更新**（多通道代理，下载进度可视化）；**关于**（AGPL-3.0 许可说明与第三方开源组件清单）

## 探针一键安装（Linux / systemd）

一条命令完成安装（脚本下载到 `/tmp` 运行，结束后自动删除，系统里不留文件）：

```bash
curl -fsSL -o /tmp/sp-install.sh https://github.com/sundys/ServerProbe/releases/latest/download/install.sh && sudo bash /tmp/sp-install.sh
```

脚本会自动识别 amd64/arm64 架构 → 从最新 Release 下载探针 → 生成配置（首次安装自动生成随机 Token 与自签证书；重复安装沿用现有 Token/指纹）→ 注册并启动 systemd 服务（开机自启）。终端会打印 **Token 与证书指纹**，填到 App「添加探针」即可。

需要自定义时在命令末尾追加参数，例如指定 Token 或端口：

```bash
curl -fsSL -o /tmp/sp-install.sh https://github.com/sundys/ServerProbe/releases/latest/download/install.sh && sudo bash /tmp/sp-install.sh --token <TOKEN> --port 9822
```

| 参数 | 说明 |
| --- | --- |
| `--token <TOKEN>` | 指定 Token；省略时首次安装自动生成、重复安装沿用现有 |
| `--port <PORT>` | 监听端口，默认 `9822` |
| `--binary <PATH>` | 使用本地二进制（离线安装） |
| `--download <URL>` | 从指定 URL 下载二进制 |
| `--dir <DIR>` | 配置目录，默认 `/etc/serverprobe` |

> - 国内服务器访问 GitHub 失败时，给脚本 URL 加代理前缀，例如：
>   `curl -fsSL -o /tmp/sp-install.sh https://gh-proxy.com/https://github.com/sundys/ServerProbe/releases/latest/download/install.sh && sudo bash /tmp/sp-install.sh`
> - 若脚本能下载、只是探针包下载慢，可单独指定探针包镜像：
>   `sudo SP_RELEASE_BASE='https://gh-proxy.com/https://github.com/sundys/ServerProbe/releases/latest/download' bash /tmp/sp-install.sh`
> - 防火墙放行端口：`sudo ufw allow 9822/tcp`（或 firewalld / 云厂商安全组）
> - 查看现有配置：`sudo serverprobe show`
> - 离线安装：下载 Release 中的 `serverprobe-linux-<arch>.tar.gz`，解压后执行 `sudo bash install.sh`（脚本与二进制同目录时直接使用本地二进制）。

## 探针一键更新（仅替换二进制）

已安装过的服务器升级探针，一条命令即可（脚本运行结束后自动删除）：

```bash
curl -fsSL -o /tmp/sp-update.sh https://github.com/sundys/ServerProbe/releases/latest/download/update.sh && sudo bash /tmp/sp-update.sh
```

脚本只替换 `/usr/local/bin/serverprobe` 并重启服务，**不会改动 `/etc/serverprobe/config.json`**，Token、证书与指纹保持不变，App 里已添加的主机无需修改。

可选参数：`--service <NAME>`（服务名，默认 `serverprobe`）、`--binary <PATH>`、`--download <URL>`；国内网络同样可用 `SP_RELEASE_BASE` 指定镜像前缀。

> - 如需连 systemd unit 一起更新，请使用上面的「探针一键安装」命令；默认安装路径下重装同样会保留 Token 与指纹。
> - 两个脚本都只在 `/tmp`、`/var/tmp` 下运行时自删；从仓库目录直接执行不会删除源文件。

## App 使用

1. 「＋ → 添加探针主机」填写地址 / 端口 / Token，建议点「获取指纹」并核对后锁定
2. 「＋ → 添加 SSH 主机」选择密码或私钥登录方式（支持各类主流私钥格式）
3. 首页大卡片滑动查看主机，点击进详情；下方 SSH 列表点击直接进终端
4. 设置 → 检测更新：多通道检测并下载新版本

## 常见问题

- **文件选择/权限**：选择私钥文件基于系统 SAF 机制，App **无需存储权限**（仅申请网络、生物识别、安装更新三项）。部分 ROM 会在文件选择器打开期间查杀后台应用，建议在系统设置中将云枢的电池策略设为「无限制」；如仍异常，可改用私钥「直接粘贴输入」，或通过 设置 → 关于 → 复制诊断日志 反馈。

## 开源许可

本项目采用 **GNU Affero General Public License v3.0（AGPL-3.0）** 开源，完整条款见仓库根目录的 [LICENSE](LICENSE)。

```
Copyright (C) 2026 sundys

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published
by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

**这对使用者意味着什么**

- **可以**：自由使用、修改、分发本项目（含商用），无需支付费用。
- **必须**：分发本项目或其修改版时，同样以 AGPL-3.0 开源并提供完整源码；保留版权与许可声明。
- **特别注意（AGPL 与 GPL 的区别）**：即使你**不分发**，只是把修改版部署成网络服务供他人使用，也必须向这些使用者提供对应源码。
- **不提供担保**：软件按「现状」提供，作者不对使用后果承担责任。

> 探针 Agent 与 Android App 均适用上述条款。若需在闭源/商业场景中使用，请联系作者获取另行授权。

## 社区

- 欢迎加入 **Linux.do 社区** 交流反馈：<https://linux.do>

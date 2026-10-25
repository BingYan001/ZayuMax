# 杂鱼Max

一款聚合多网盘资源搜索与高速下载的 Android 客户端，Material 3 + Compose 构建，免费开源。

## 功能

- **多平台聚合解析**：百度网盘 / 123 / 115 / 夸克 / UC / 迅雷 / 天翼 / 蓝奏云 / 移动云盘直链解析
- **资源搜索**：内置多源搜索，找资源不用换 App
- **多线程高速下载**：可自定义线程数，支持第三方下载器接管
- **多账号管理**：多账号切换、登录态自动保活
- **局域网互传**：手机与电脑互传文件/文本，免流量免登录
- **桌面端配套**：`tools/desktop` 内含 Windows 桌面客户端「杂鱼互传」（`lan_client.py`，可打包为单文件 exe），与 App 端自动发现配对，单文件最大 512MB
- **管理端 App**：独立模块 `:admin`，配套公告系统后台管理（需自建公告服务）
- **玻璃拟态 UI**：动态取色、深色模式、流畅动效

## 下载

前往 [Releases](../../releases) 下载最新 APK。

> Android 8.0+，安装前允许安装未知来源应用。

## 项目来源

本项目基于开源项目 **云析**（AGPL-3.0）进行二次开发，感谢原作者。

本仓库遵循 **AGPL-3.0** 协议开源，使用/修改/分发请同样遵守该协议并保留来源声明。

## 构建

```bash
./gradlew assembleRelease
```

- 需要 JDK 17 + Android SDK
- 仓库自带 `debug.keystore`，无签名配置也可直接编译 debug

### 桌面端（杂鱼互传）

```bash
pip install pywebview
python tools/desktop/lan_client.py
# 打包单文件 exe：
pyinstaller --onefile --name ZayuLan tools/desktop/lan_client.py
```

电脑与手机连同一 Wi-Fi 后自动互相发现，即可互传文件与文本。

## UI 说明

当前界面结构源自上游「云析」，后续将进行全面的独立 UI 重设计。

## 声明

- 本项目仅供学习交流，请于下载后 24 小时内自行评估删除
- 所有资源均来自第三方公开接口，本项目不存储、不上传任何资源

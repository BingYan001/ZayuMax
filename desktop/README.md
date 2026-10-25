# 杂鱼Max 桌面端（ZayuMax Desktop）

多网盘聚合搜索与高速下载的 Windows 桌面客户端，Kotlin + Compose Multiplatform 构建，与 [杂鱼Max 手机端](../README.md) 同源。

## 功能

- 百度 / 123 / 115 / 夸克 / UC / 迅雷 / 天翼 / 蓝奏云 / 移动云盘直链解析
- 多线程高速下载、多账号管理、登录态保活
- 液态玻璃折射 UI（SKSL 着色器）、动态取色、自定义背景
- 内嵌浏览器登录（JCEF）、剪贴板链接监听
- 局域网互传（与手机端配对）
- 便携包自带运行时，解压即用，无需安装 Java

## 下载

前往 [Releases](../../releases) 下载 `ZayuMax-Desktop-vX.Y.Z.zip`，解压后运行 `YunX-Desktop.exe`。

## 构建

详见 [docs/BUILD.md](docs/BUILD.md)。

```bash
./gradlew :desktop:packageDistributionForCurrentOS
```

- 需要 JDK 17（`run.bat` 可自动安装）
- 打包便携版：`portable-package.ps1`

## 项目来源

基于开源项目 **YunX-Desktop** 进行二次开发（桌面端），与手机端同基于 **云析**（AGPL-3.0），感谢原作者。

本仓库遵循 **AGPL-3.0** 协议开源。

## 声明

- 本项目仅供学习交流，请于下载后 24 小时内自行评估删除
- 所有资源均来自第三方公开接口，本项目不存储、不上传任何资源

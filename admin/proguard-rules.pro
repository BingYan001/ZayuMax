# Zayu Admin (杂鱼Max 管理端) - 杂鱼Max 公告系统的安卓管理客户端。
# Copyright (C) 2026 CYQawa
#
# R8 规则：管理端没有反射式序列化框架，只用了 org.json 手工解析，
# 因此不需要 keep 任何模型类。这里只保留 Compose 与 OkHttp 的必需项。
#
# 注意：本文件是 ProGuard 语法，注释只能用 #，不能用 /* */（R8 会报
#       "Expected char '-' at ...:1:1"）。

# OkHttp：内部用了大量反射与可选平台类，缺了会在特定机型上崩
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coil：图片解码器靠 ServiceLoader 注册
-keep class coil.** { *; }
-dontwarn coil.**

# 保留行号，便于线上看栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

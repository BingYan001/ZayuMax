/*
 * Zayu Admin (杂鱼Max 管理端) - 杂鱼Max 公告系统的安卓管理客户端。
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- 签名：与主 App 完全同一套逻辑（同一把钥匙，同一套查找顺序）----
// 查找顺序（命中第一个存在的即用）：
//   1) 环境变量 ZAYUMAX_KEYSTORE_PROPERTIES 指向的属性文件；
//   2) 仓库根目录 keystore.properties；
//   3) 仓库上级目录 .signing/keystore.properties。
// 都找不到 ⇒ 回退仓库自带 debug.keystore，保证任何人拿到源码都能直接编。
// ⚠️ 必须 load(Reader)：load(InputStream) 按 ISO-8859-1 解码，会把路径里的中文解成乱码。
val adminKeystoreProps: Properties? = run {
    val candidates = listOfNotNull(
        System.getenv("ZAYUMAX_KEYSTORE_PROPERTIES")?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) },
        rootProject.file("keystore.properties").takeIf { it.isFile },
        rootProject.file("../.signing/keystore.properties").takeIf { it.isFile }
    )
    val propsFile = candidates.firstOrNull { it.isFile } ?: return@run null
    val props = Properties()
    propsFile.reader(Charsets.UTF_8).use { reader -> props.load(reader) }
    props
}
val adminSigningReady: Boolean = adminKeystoreProps?.let { p ->
    p.getProperty("storeFile")?.let { file(it).isFile } == true &&
        !p.getProperty("storePassword").isNullOrBlank() &&
        !p.getProperty("keyAlias").isNullOrBlank() &&
        !p.getProperty("keyPassword").isNullOrBlank()
} == true

logger.lifecycle(
    if (adminSigningReady) "杂鱼Max 管理端 签名：已启用专属密钥 ${adminKeystoreProps?.getProperty("storeFile")}"
    else "杂鱼Max 管理端 签名：未找到 keystore.properties → 回退仓库 debug.keystore"
)

android {
    // ★ namespace 必须与主 App 不同，否则 R 类会撞名（两个模块各自生成 com.zayu.admin.R）。
    namespace = "com.zayu.admin"
    compileSdk = 36

    defaultConfig {
        // 独立 applicationId：与「杂鱼Max」并存安装，互不覆盖。
        applicationId = "com.zayu.admin"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (adminSigningReady) {
            adminKeystoreProps?.let { props ->
                create("zayumax") {
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = if (adminSigningReady) {
                signingConfigs.getByName("zayumax")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = when {
                adminSigningReady -> signingConfigs.getByName("zayumax")
                else -> null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    // ★ 不跨模块共享源码：主 App 的 src 里有 MainActivity / ZayuApp / 一大堆 ViewModel，
    //   整体引入会把重复的 Application 与 Manifest 组件一起编进来，且改动主 App 会莫名其妙地
    //   把管理端编崩。管理端需要什么就自己持有一份（玻璃组件、主题资源），代价是少量复制，
    //   换来的是「改一边不会影响另一边」——这是两个独立软件应有的边界。
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }
}

dependencies {
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // collectAsStateWithLifecycle：退到后台自动停订阅，省电
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 图片加载：公告封面 / 图集缩略图
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(libs.material)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.backdrop)

    debugImplementation(libs.androidx.ui.tooling)

    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    }
}

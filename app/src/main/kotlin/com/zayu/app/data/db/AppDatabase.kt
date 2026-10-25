/*
 * Zayu (杂鱼) - A network drive share-link parser and high-speed downloader for Android.
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

package com.zayu.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zayu.app.data.security.AndroidKeystoreCredentialCipher
import com.zayu.app.data.security.CredentialCipher
import com.zayu.app.data.security.CredentialStore
import com.zayu.app.util.DiagnosticLog

@Database(
    entities = [QuarkAccountEntity::class, DownloadTaskEntity::class, UCAccountEntity::class, XunleiAccountEntity::class, BaiduAccountEntity::class, C139AccountEntity::class, Pan123AccountEntity::class, Pan115AccountEntity::class, GuangYaAccountEntity::class, ILanzouAccountEntity::class, LanzouAccountEntity::class, BookmarkEntity::class, VideoAccountEntity::class, ParseHistoryEntity::class],
    version = 25,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rawQuarkAccountDao(): QuarkAccountDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun rawUcAccountDao(): UCAccountDao

    abstract fun rawXunleiAccountDao(): XunleiAccountDao

    abstract fun rawBaiduAccountDao(): BaiduAccountDao

    abstract fun rawC139AccountDao(): C139AccountDao

    abstract fun rawPan123AccountDao(): Pan123AccountDao

    abstract fun rawPan115AccountDao(): Pan115AccountDao

    abstract fun rawGuangYaAccountDao(): GuangYaAccountDao

    abstract fun rawILanzouAccountDao(): ILanzouAccountDao

    abstract fun rawLanzouAccountDao(): LanzouAccountDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun parseHistoryDao(): ParseHistoryDao

    /** 短视频平台账号（抖音/小红书/快手/微博/AcFun/B站/Pixiv），cookie 本身已加密落库 */
    abstract fun rawVideoAccountDao(): VideoAccountDao

    /** 加密封装版视频账号 DAO（业务侧一律用这个） */
    fun videoAccountDao(): VideoAccountDao = SecureAccountDaos.videoAccount(rawVideoAccountDao(), credentialCipher)

    private lateinit var credentialCipher: CredentialCipher

    fun quarkAccountDao(): QuarkAccountDao = SecureAccountDaos.quark(rawQuarkAccountDao(), credentialCipher)
    fun ucAccountDao(): UCAccountDao = SecureAccountDaos.uc(rawUcAccountDao(), credentialCipher)
    fun xunleiAccountDao(): XunleiAccountDao = SecureAccountDaos.xunlei(rawXunleiAccountDao(), credentialCipher)
    fun baiduAccountDao(): BaiduAccountDao = SecureAccountDaos.baidu(rawBaiduAccountDao(), credentialCipher)
    fun c139AccountDao(): C139AccountDao = SecureAccountDaos.c139(rawC139AccountDao(), credentialCipher)
    fun pan123AccountDao(): Pan123AccountDao = SecureAccountDaos.pan123(rawPan123AccountDao(), credentialCipher)
    fun pan115AccountDao(): Pan115AccountDao = SecureAccountDaos.pan115(rawPan115AccountDao(), credentialCipher)
    fun guangyaAccountDao(): GuangYaAccountDao = SecureAccountDaos.guangya(rawGuangYaAccountDao(), credentialCipher)
    fun ilanzouAccountDao(): ILanzouAccountDao = SecureAccountDaos.ilanzou(rawILanzouAccountDao(), credentialCipher)
    fun lanzouAccountDao(): LanzouAccountDao = SecureAccountDaos.lanzou(rawLanzouAccountDao(), credentialCipher)

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yunx.db"
                )
                    .addMigrations(
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25
                    )
                    // 早期开发版（1-8）无可靠 schema；从 v9 起必须保留凭证和下载任务
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
                    .addCallback(object : RoomDatabase.Callback() {
                        /** 诊断模式：库建/开时记一条（版本 + 全部业务表），排查「表不存在 / 迁移没跑」最有用 */
                        override fun onCreate(db: SupportSQLiteDatabase) = logSchema("create", db)

                        override fun onOpen(db: SupportSQLiteDatabase) = logSchema("open", db)

                        private fun logSchema(action: String, db: SupportSQLiteDatabase) {
                            runCatching {
                                val tables = mutableListOf<String>()
                                db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
                                    while (c.moveToNext()) {
                                        val name = c.getString(0) ?: continue
                                        // sqlite/room 自己的内部表不算业务表
                                        if (!name.startsWith("sqlite_") && !name.startsWith("android_") && name != "room_master_table") {
                                            tables.add(name)
                                        }
                                    }
                                }
                                val version = runCatching { db.version }.getOrDefault(-1)
                                DiagnosticLog.log(
                                    DiagnosticLog.DB,
                                    "db_$action",
                                    status = "v$version",
                                    size = tables.size.toLong(),
                                    summary = "file=yunx.db | tables=${tables.joinToString(",")}"
                                )
                                // 顺手把每张表的行数也记一笔（只在诊断模式开启时执行，且只在库打开那一次）
                                if (DiagnosticLog.isEnabled()) {
                                    tables.forEach { table ->
                                        runCatching {
                                            db.query("SELECT COUNT(*) FROM $table").use { c ->
                                                if (c.moveToFirst()) DiagnosticLog.db(table, "count", 0L, c.getInt(0))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .build()
                    .also { database ->
                        database.credentialCipher = AndroidKeystoreCredentialCipher.shared
                        // 密钥若是「新建」的，说明此前存下的密文必然由另一把（已失效的）密钥加密。
                        // 这里只**记录**这件事（写一个 SharedPreferences 标记），不删任何数据：
                        // 各存储路径自己按「解密失败 → 清掉这一条」自愈（DAO 的 decryptGuarded、
                        // GitHubTokenStore.getToken、DownloadManager.loadPersistedHeaders），
                        // 在这里无差别清空账号表风险太大——判断稍有偏差就会白白抹掉用户的登录态。
                        // 主装配点在 ZayuApp.onCreate（必须早于任何凭证读写），这里再兜一次。
                        CredentialStore.installRecovery(context)
                        instance = database
                    }
            }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN requestHeadersJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE download_task ADD COLUMN chunkCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN plannedTotalSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN cleanupId TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN platform TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN avgSpeed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmark` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`link` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`platform` TEXT NOT NULL, " +
                        "`pwd` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`createTime` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 主页快捷方式标记（0/1）；老收藏默认不在主页显示
                db.execSQL("ALTER TABLE bookmark ADD COLUMN homePinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 主页快捷方式色块的自定义文字；空串 = 自动取标题前几个字
                db.execSQL("ALTER TABLE bookmark ADD COLUMN homeLabel TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 115 网盘登录凭证（Cookie 落库前在 SecureAccountDaos 里加密）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pan115_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`cookie` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v17：下载任务记录 Gopeed 引擎任务 ID（空串 = 内置分片下载器） */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `download_task` ADD COLUMN `engineTaskId` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v18：新增光鸭云盘 / 蓝奏云优享版 / 蓝奏云登录凭证表（凭证落库前在 SecureAccountDaos 里加密） */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `guangya_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`accessToken` TEXT NOT NULL, " +
                        "`refreshToken` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, " +
                        "`deviceSign` TEXT NOT NULL, " +
                        "`account` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ilanzou_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`appToken` TEXT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`account` TEXT NOT NULL, " +
                        "`password` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lanzou_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`cookie` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `xunlei_account` ADD COLUMN `authType` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v20：给 10 张账号表加「运行健康度」字段（S3 阶段 1）。
         *
         * 全部字段都带 DEFAULT，ALTER TABLE 对已有行自动填默认值 —— 
         * **用户已登录的账号数据不受影响**（这是选 ADD COLUMN 而不是重建表的原因）。
         *
         * 只加字段不改结构，因此本迁移无数据丢失风险；新增字段全部是「补充信息」，
         * 旧版本读到它们也只是不认识，不会崩。
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `baidu_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `baidu_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `baidu_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `baidu_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `baidu_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `c139_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `c139_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `c139_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `c139_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `c139_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `guangya_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `guangya_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `guangya_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `guangya_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `guangya_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ilanzou_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ilanzou_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ilanzou_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ilanzou_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ilanzou_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `lanzou_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `lanzou_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `lanzou_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `lanzou_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `lanzou_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan115_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan115_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan115_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan115_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan115_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan123_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan123_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan123_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan123_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pan123_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `quark_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `quark_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `quark_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `quark_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `quark_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `uc_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `uc_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `uc_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `uc_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `uc_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `xunlei_account` ADD COLUMN `lastErrorCode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `xunlei_account` ADD COLUMN `lastErrorAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `xunlei_account` ADD COLUMN `cooldownUntil` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `xunlei_account` ADD COLUMN `failCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `xunlei_account` ADD COLUMN `okCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v21：短视频平台账号表（抖音/小红书/快手/微博/AcFun/B站/Pixiv 共用一张） */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `video_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`platform` TEXT NOT NULL, " +
                        "`cookie` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`isPrimary` INTEGER NOT NULL, " +
                        "`remark` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v22：哔哩哔哩 DASH 音视频合轨（音轨直链 + 音轨请求头，两列默认空 = 单文件下载） */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `mergeAudioUrl` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `mergeAudioHeadersJson` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v23：动图（Live Photo）合成（动图资源直链，默认空 = 普通单文件下载） */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `motionUrl` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 解析历史：视频解析与网盘分享解析成功后的本地记录（v1.3.15 新增）
                // ⚠️ 列顺序必须与 ParseHistoryEntity 的字段声明顺序**完全一致**，
                //    否则 Room 打开库时 schema 校验不通过 → IllegalStateException: Migration didn't properly handle
                //    （v1.3.15 首版把顺序写成 id/kind/platform/title/url/result/createTime，且列名写成 url/result，导致启动即崩）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `parse_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`link` TEXT NOT NULL, `pwd` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`platform` TEXT NOT NULL, `kind` TEXT NOT NULL, `createTime` INTEGER NOT NULL)"
                )
            }
        }
        /**
         * v1.3.15 首版 MIGRATION_23_24 建表列顺序/列名与 Entity 不符，导致 Room 打开库时
         * 「Migration didn't properly handle parse_history」启动即崩。
         * 这里直接删表重建为正确结构（历史是纯附加数据，丢失无影响）。
         */
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `parse_history`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `parse_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`link` TEXT NOT NULL, `pwd` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`platform` TEXT NOT NULL, `kind` TEXT NOT NULL, `createTime` INTEGER NOT NULL)"
                )
            }
        }
    }
}

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

package com.zayu.app.data.files

/**
 * 远程网盘文件条目（统一文件管理的最小字段集）。
 *
 * @param path 完整路径（如 /apps/xxx/a.txt），是所有操作的定位键
 * @param name 文件名（path 的最后一段）
 * @param size 字节数；目录为 0
 * @param isDir 是否目录
 * @param md5 内容摘要（百度 category/list 返回）；服务端未提供时为 null
 * @param modifiedAt 修改时间（Unix 秒）；服务端未提供时为 0
 */
data class RemoteFile(
    val path: String,
    val name: String,
    val size: Long,
    val isDir: Boolean,
    val md5: String? = null,
    val modifiedAt: Long = 0L
)

/**
 * 统一文件管理抽象：面向「浏览 + 整理」（列表/重命名/移动/删除/新建文件夹）的最小操作面。
 *
 * 设计约定：
 * - 所有路径均为**绝对路径**，根目录固定为 "/"；
 * - 全部为 suspend 函数，实现方自行做 IO 调度与风控节流；
 * - 失败抛异常（消息为用户可读中文），UI 直接展示 message 即可；
 * - 「统一」指未来各平台（夸克/UC/123…）各自实现本接口，UI 层不感知平台差异。
 */
interface RemoteFileOperations {

    /** 列出目录内容；dir 为绝对路径（根目录传 "/"）。失败抛异常 */
    suspend fun list(dir: String): List<RemoteFile>

    /** 重命名 path 所指条目为 newName（仅文件名，不含路径）。失败抛异常 */
    suspend fun rename(path: String, newName: String)

    /** 把 path 移动到 targetDir 目录下（保持原文件名）。失败抛异常 */
    suspend fun move(path: String, targetDir: String)

    /** 删除 path（目录递归删除，交给服务端语义）。失败抛异常 */
    suspend fun delete(path: String)

    /** 在 dir 下新建名为 name 的文件夹。失败抛异常 */
    suspend fun createFolder(dir: String, name: String)

    /**
     * 上传本地文件到 dir。本打样阶段未实现——百度上传需要预建分片（precreate）→
     * 逐片 superfile2 上传 → create 合并的三步分片协议，后续单独接入。
     */
    suspend fun upload(localPath: String, dir: String, name: String)
}

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

package com.zayu.admin.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zayu.admin.data.AdminApi
import com.zayu.admin.data.SecureTokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 管理端总状态机。
 *
 * 单 Activity + 单 ViewModel：这个 App 只有「列表 / 编辑 / 统计」三块，拆多 ViewModel
 * 只会多出一堆 handoff 代码。用不可变 data class 承载整屏状态，改一个字段就 copy 一次，
 * 重组范围由 Compose 自己收敛。
 */
class AdminViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureTokenStore(app)

    private val _ui = MutableStateFlow(AdminUiState())
    val ui: StateFlow<AdminUiState> = _ui.asStateFlow()

    private var api: AdminApi? = null

    /** 上传中阻塞重复点击用 */
    private var uploadJob: Job? = null

    init {
        // 有已保存的密钥就直接尝试进主界面（验证失败会由 unauthorized 分支退回登录页）
        val token = store.load()
        _ui.update { it.copy(baseUrl = store.baseUrl, hasStoredToken = token != null) }
        if (token != null) {
            api = AdminApi(store.baseUrl, token).also { it.token = token }
            _ui.update { it.copy(loggedIn = true) }
            refreshAll()
        }
    }

    // ------------------------------------------------------------ 登录

    fun onLoginTokenChange(v: String) = _ui.update { it.copy(loginToken = v) }

    fun onBaseUrlChange(v: String) = _ui.update { it.copy(baseUrl = v) }

    fun login() {
        val state = _ui.value
        val token = state.loginToken.trim()
        val base = state.baseUrl.trim().ifBlank { AdminApi.DEFAULT_BASE_URL }
        if (token.isBlank()) {
            _ui.update { it.copy(loginError = "请先填管理密钥") }
            return
        }
        if (state.loggingIn) return

        _ui.update { it.copy(loggingIn = true, loginError = null) }
        viewModelScope.launch {
            val candidate = AdminApi(base, token)
            when (val r = candidate.stats()) {
                is AdminApi.Result.Success -> {
                    // 验证通过才落盘，避免把错 token 存进去
                    runCatching { store.save(token) }
                        .onFailure { e ->
                            _ui.update {
                                it.copy(
                                    loggingIn = false,
                                    loginError = "无法安全保存密钥：${e.message}"
                                )
                            }
                            return@launch
                        }
                    store.baseUrl = base
                    api = candidate
                    _ui.update {
                        it.copy(
                            loggedIn = true,
                            loggingIn = false,
                            hasStoredToken = true,
                            loginToken = "",
                            loginError = null,
                            baseUrl = base,
                            stats = r.data,
                        )
                    }
                    refreshAll()
                }
                is AdminApi.Result.Failure -> _ui.update {
                    it.copy(loggingIn = false, loginError = r.message)
                }
            }
        }
    }

    fun logout() {
        store.clear()
        api = null
        _ui.value = AdminUiState(baseUrl = store.baseUrl, loggedIn = false, hasStoredToken = false)
    }

    // ------------------------------------------------------------ 列表 / 统计

    fun refreshAll() {
        refreshStats()
        refreshList()
    }

    fun refreshStats() {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.stats()) {
                is AdminApi.Result.Success -> _ui.update { it.copy(stats = r.data) }
                is AdminApi.Result.Failure -> if (r.unauthorized) handleUnauthorized()
            }
        }
    }

    fun refreshList() {
        val a = api ?: return
        _ui.update { it.copy(listLoading = true, listError = null) }
        viewModelScope.launch {
            when (val r = a.list()) {
                is AdminApi.Result.Success -> _ui.update {
                    it.copy(list = r.data, listLoading = false, listError = null)
                }
                is AdminApi.Result.Failure -> {
                    if (r.unauthorized) return@launch handleUnauthorized()
                    _ui.update { it.copy(listLoading = false, listError = r.message) }
                }
            }
        }
    }

    fun setFilter(f: AnnouncementFilter) = _ui.update { it.copy(filter = f) }

    fun setTab(t: AdminTab) = _ui.update { it.copy(tab = t) }

    // ------------------------------------------------------------ 置顶 / 删除

    fun togglePin(item: AdminApi.Announcement) {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.setPinned(item.id, !item.isPinned)) {
                is AdminApi.Result.Success -> {
                    toast(if (r.data) "已置顶" else "已取消置顶")
                    refreshList()
                }
                is AdminApi.Result.Failure -> {
                    if (r.unauthorized) return@launch handleUnauthorized()
                    toast("操作失败：${r.message}")
                }
            }
        }
    }

    fun delete(item: AdminApi.Announcement) {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.delete(item.id)) {
                is AdminApi.Result.Success -> {
                    toast("已删除")
                    refreshAll()
                }
                is AdminApi.Result.Failure -> {
                    if (r.unauthorized) return@launch handleUnauthorized()
                    toast("删除失败：${r.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------ 编辑

    fun openEditor(item: AdminApi.Announcement?) {
        _ui.update {
            it.copy(editor = if (item == null) EditorState() else EditorState.from(item))
        }
    }

    fun closeEditor() = _ui.update { it.copy(editor = null) }

    fun updateEditor(transform: (EditorState) -> EditorState) {
        _ui.update { s -> s.editor?.let { s.copy(editor = transform(it)) } ?: s }
    }

    /** 保存（新建 or 更新）。成功后关编辑器并刷新 */
    fun saveEditor() {
        val a = api ?: return
        val e = _ui.value.editor ?: return
        if (e.title.isBlank()) {
            updateEditor { it.copy(error = "标题不能为空") }
            return
        }
        if (e.saving) return

        updateEditor { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val fields = JSONObject().apply {
                put("title", e.title.trim())
                put("summary", e.summary)
                put("content", e.content)
                put("author", e.author)
                put("publisherName", e.publisherName)
                put("status", e.status)
                put("isPinned", e.pinned)
                put("sortOrder", e.sortOrder)
                put("coverImage", if (e.coverImage.isBlank()) JSONObject.NULL else e.coverImage)
                put("publishAt", if (e.publishAtIso.isBlank()) JSONObject.NULL else e.publishAtIso)
                put(
                    "images",
                    org.json.JSONArray().apply { e.images.forEach { put(it) } }
                )
            }
            val result = if (e.id == null) a.create(fields) else a.update(e.id, fields)
            when (result) {
                is AdminApi.Result.Success -> {
                    _ui.update { it.copy(editor = null) }
                    toast(if (e.id == null) "已发布" else "已保存")
                    refreshAll()
                }
                is AdminApi.Result.Failure -> {
                    if (result.unauthorized) return@launch handleUnauthorized()
                    updateEditor { it.copy(saving = false, error = result.message) }
                }
            }
        }
    }

    /**
     * 上传图片并回填。
     *
     * @param target COVER 填封面，GALLERY 追加到图集
     */
    fun uploadImage(uri: Uri, target: UploadTarget) {        val a = api ?: return
        if (uploadJob?.isActive == true) return
        val ctx = getApplication<Application>()

        updateEditor { it.copy(uploading = target) }
        uploadJob = viewModelScope.launch {
            // content:// -> 私有缓存目录的临时文件（multipart 要真实文件）
            val file = runCatching {
                val ext = ctx.contentResolver.getType(uri)?.substringAfterLast('/', "jpg") ?: "jpg"
                val f = File(ctx.cacheDir, "upload_${System.currentTimeMillis()}.$ext")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { output -> input.copyTo(output) }
                } ?: error("无法读取所选图片")
                f
            }.getOrElse { e ->
                Log.e(TAG, "读取图片失败", e)
                updateEditor { it.copy(uploading = null, error = "读取图片失败：${e.message}") }
                return@launch
            }

            when (val r = a.upload(file)) {
                is AdminApi.Result.Success -> {
                    updateEditor {
                        when (target) {
                            UploadTarget.COVER -> it.copy(coverImage = r.data, uploading = null)
                            UploadTarget.GALLERY -> it.copy(images = it.images + r.data, uploading = null)
                        }
                    }
                }
                is AdminApi.Result.Failure -> {
                    if (r.unauthorized) return@launch handleUnauthorized()
                    updateEditor { it.copy(uploading = null, error = "上传失败：${r.message}") }
                }
            }
            file.delete()
        }
    }

    fun removeImage(target: UploadTarget, url: String? = null) {
        updateEditor {
            when (target) {
                UploadTarget.COVER -> it.copy(coverImage = "")
                UploadTarget.GALLERY -> it.copy(images = it.images.filter { u -> u != url })
            }
        }
    }

    // ------------------------------------------------------------ 杂项

    fun consumeToast() = _ui.update { it.copy(toast = null) }

    private fun toast(msg: String) = _ui.update { it.copy(toast = msg) }

    private fun handleUnauthorized() {
        store.clear()
        api = null
        _ui.update {
            it.copy(
                loggedIn = false,
                hasStoredToken = false,
                editor = null,
                listLoading = false,
                loggingIn = false,
                loginError = "密钥已失效，请重新登录",
            )
        }
    }

    companion object {
        private const val TAG = "ZayuAdmin-VM"
    }
}

// ---------------------------------------------------------------- 状态定义

enum class AdminTab(val label: String) {
    ALL("全部"),
    PUBLISHED("已发布"),
    DRAFT("草稿"),
    ARCHIVED("已归档"),
}

enum class AnnouncementFilter {
    ALL, PINNED, SCHEDULED
}

enum class UploadTarget { COVER, GALLERY }

data class AdminUiState(
    val baseUrl: String = AdminApi.DEFAULT_BASE_URL,
    val loggedIn: Boolean = false,
    val hasStoredToken: Boolean = false,
    val loginToken: String = "",
    val loggingIn: Boolean = false,
    val loginError: String? = null,

    val tab: AdminTab = AdminTab.ALL,
    val filter: AnnouncementFilter = AnnouncementFilter.ALL,
    val list: List<AdminApi.Announcement> = emptyList(),
    val listLoading: Boolean = false,
    val listError: String? = null,

    val stats: AdminApi.Stats? = null,

    val editor: EditorState? = null,
    val toast: String? = null,
) {
    /** 按 tab + filter 过滤后的列表（纯函数，Compose 里直接算） */
    val visible: List<AdminApi.Announcement>
        get() = list.asSequence()
            .filter { a ->
                when (tab) {
                    AdminTab.ALL -> true
                    AdminTab.PUBLISHED -> a.status == "published"
                    AdminTab.DRAFT -> a.status == "draft"
                    AdminTab.ARCHIVED -> a.status == "archived"
                }
            }
            .filter { a ->
                when (filter) {
                    AnnouncementFilter.ALL -> true
                    AnnouncementFilter.PINNED -> a.isPinned
                    AnnouncementFilter.SCHEDULED -> a.isScheduled()
                }
            }
            .toList()
}

data class EditorState(
    val id: String? = null,
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val author: String = "杂鱼团队",
    val publisherName: String = "杂鱼团队",
    val status: String = "published",
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val coverImage: String = "",
    val images: List<String> = emptyList(),
    /** 定时发布时间，ISO8601；空 = 立即发布 */
    val publishAtIso: String = "",
    val saving: Boolean = false,
    val uploading: UploadTarget? = null,
    val error: String? = null,
) {
    val isNew: Boolean get() = id == null

    companion object {
        fun from(a: AdminApi.Announcement) = EditorState(
            id = a.id,
            title = a.title,
            summary = a.summary,
            content = a.content.orEmpty(),
            author = a.author,
            publisherName = a.publisherName.ifBlank { a.author },
            status = a.status,
            pinned = a.isPinned,
            sortOrder = a.sortOrder,
            coverImage = a.coverImage.orEmpty(),
            images = a.images,
            publishAtIso = a.publishAt.orEmpty(),
        )
    }
}

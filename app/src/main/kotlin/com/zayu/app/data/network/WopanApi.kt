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

package com.zayu.app.data.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 分享定位结果：shareId + 提取码 + clientId（全部来自链接本身或短链跳转的 query）。 */
data class WopanShareTarget(
    val shareId: String,
    val passcode: String,
    val clientId: String
)

/**
 * 中国联通云盘 API（分享解析部分）。
 *
 * 协议要点（对齐文析 wopan.dart，抓包口径）：
 * - 短链：`GET pan.wo.cn/s/<id>` 拿 302，`Location` 里带 `shareId` / `shareCode`；
 *   `pan.wo.cn` 会在 302 前断连，路径命中 `/s/xxx` 时提前换成 `panservice.mail.wo.cn`；
 * - 分享根目录：`POST {ORIGIN}/wohome/dispatcher`，header.channel=`100002`、key=`ShareFileDetail`，
 *   `DATA` 是 api-user 密钥加密的**文件数组**；分享根匿名可列；
 * - 子目录：`QueryShareFiles`（wohome 通道，必须带访问令牌）；
 * - 取链：`GetDownloadUrlV2`（wohome 通道，`DATA.list[].downloadUrl`），失败回退 `GetDownloadUrl`。
 *
 * 鉴权：请求头 `Accesstoken`；wohome 通道的 AES 密钥 = 令牌前 16 位，令牌不足 16 位直接判无效。
 */
class WopanApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** reqSeq 必须是随机数（文析用 Random.secure），固定值会被风控 */
    private val random = SecureRandom()

    /** 登录态失效（分享根目录与短链解析不会触发，取链/子目录会） */
    class LoginRequiredException(message: String) : Exception(message)

    /** 单次请求结果：HTTP 状态 + 解析后的 JSON（401/403 无 body 时给空对象） */
    private class ApiResponse(val code: Int, val json: JSONObject) {
        val ok: Boolean get() = code in 200..299
    }

    // ---------- AES/CBC 加解密 ----------

    private fun cipher(opmode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                opmode,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(WopanConstants.AES_IV.toByteArray(Charsets.UTF_8))
            )
        }

    /**
     * 通道密钥：api-user 固定用 clientSecret；其余（wohome）用访问令牌前 16 位。
     * 令牌短于 16 位时服务端无论如何都解不开，提前给可读错误。
     */
    private fun keyFor(channel: String, access: String): ByteArray {
        if (channel == "api-user") return WopanConstants.CLIENT_SECRET.toByteArray(Charsets.UTF_8)
        require(access.length >= 16) { "联通云盘访问令牌无效，请重新登录" }
        return access.substring(0, 16).toByteArray(Charsets.UTF_8)
    }

    /** 明文 JSON → Base64(AES-CBC(明文)) */
    fun encrypt(data: JSONObject, channel: String, access: String): String {
        val bytes = cipher(Cipher.ENCRYPT_MODE, keyFor(channel, access))
            .doFinal(data.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * 响应里的 `DATA` → 明文 JSON。
     * 服务端有时直接回对象/数组（未加密），有时回空串，两种都原样放过。
     */
    fun decryptValue(data: Any?, channel: String, access: String): Any {
        if (data is JSONObject || data is JSONArray) return data
        require(data is String && data.isNotEmpty()) { "联通云盘返回的数据为空" }
        val value = try {
            val plain = String(
                cipher(Cipher.DECRYPT_MODE, keyFor(channel, access))
                    .doFinal(Base64.decode(data, Base64.NO_WRAP)),
                Charsets.UTF_8
            )
            JSONTokener(plain).nextValue()
        } catch (_: Exception) {
            throw IllegalStateException("联通云盘响应解密失败，请刷新或重新登录")
        }
        require(value is JSONObject || value is JSONArray) { "联通云盘响应格式错误" }
        return value
    }

    /** 同 [decryptValue]，但要求顶层是对象 */
    fun decryptObject(data: Any?, channel: String, access: String): JSONObject {
        val value = decryptValue(data, channel, access)
        require(value is JSONObject) { "联通云盘响应格式错误" }
        return value
    }

    // ---------- 公共 header ----------

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 请求头签名块：sign = md5(key + resTime + reqSeq + channel)，version 恒为空串 */
    fun header(channel: String, key: String, milliseconds: Long, sequence: Int): JSONObject =
        JSONObject()
            .put("key", key)
            .put("resTime", milliseconds)
            .put("reqSeq", sequence)
            .put("channel", channel)
            .put("sign", md5Hex("$key$milliseconds$sequence$channel"))
            .put("version", "")

    // ---------- 分享短链 → shareId + 提取码 + clientId ----------

    /**
     * 解析分享链接（可跟短链跳转）。
     *
     * 每一跳都做白名单校验：只允许 http/https + `pan.wo.cn` / `panservice.mail.wo.cn`，
     * 不允许 userInfo、不允许非默认端口 —— 防止被构造的分享文案把请求带去任意地址。
     * `shareId` 与 `shareCode` 既可能在 URL query，也可能在 `#` 之后的 hash 路由里。
     */
    suspend fun resolveShareTarget(url: String, passcode: String? = null): WopanShareTarget =
        withContext(Dispatchers.IO) {
            var uri = runCatching { URI(url.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("联通分享链接无效")
            var code = passcode?.trim().orEmpty()
            val visited = mutableSetOf<String>()
            repeat(WopanConstants.MAX_SHARE_HOPS) {
                uri = normalizeShareUri(uri)
                val params = shareParams(uri)
                if (code.isEmpty()) code = params["sharecode"].orEmpty()
                val shareId = params["shareid"] ?: params["surl"] ?: ""
                if (shareId.isNotEmpty()) {
                    require(WopanConstants.SHARE_ID_REGEX.matches(shareId)) { "联通分享标识无效" }
                    val clientId = params["clientid"].orEmpty()
                    return@withContext WopanShareTarget(
                        shareId = shareId,
                        passcode = code,
                        clientId = if (WopanConstants.CLIENT_ID_REGEX.matches(clientId)) {
                            clientId
                        } else {
                            WopanConstants.CLIENT_ID
                        }
                    )
                }
                require(visited.add(uri.toString())) { "联通分享链接发生循环跳转" }
                val location = fetchLocation(uri)
                uri = uri.resolve(location)
            }
            throw IllegalStateException("联通分享跳转次数过多，请使用官网完整分享链接")
        }

    /** 校验并规范化一跳：http → https，`pan.wo.cn/s/xxx` → API 域（见类注释）。 */
    private fun normalizeShareUri(input: URI): URI {
        val scheme = input.scheme?.lowercase().orEmpty()
        val host = input.host?.lowercase().orEmpty()
        val port = when {
            input.port != -1 -> input.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
        require(
            (scheme == "http" || scheme == "https") &&
                host in WopanConstants.SHARE_HOSTS &&
                input.userInfo == null &&
                port == (if (scheme == "https") 443 else 80)
        ) { "联通分享跳转到了非官方地址，已停止解析" }
        var uri = input
        if (scheme == "http") {
            uri = URI("https", null, uri.host, -1, uri.path, uri.query, uri.fragment)
        }
        // 短链服务在 API 域也提供同一路径；公网 pan.wo.cn 会在返回 302 前重置连接
        if (uri.host.equals(WopanConstants.SHORT_HOST, ignoreCase = true) &&
            WopanConstants.SHORT_PATH_REGEX.matches(uri.path.orEmpty())
        ) {
            uri = URI(
                "https",
                null,
                URI(WopanConstants.ORIGIN).host,
                -1,
                uri.path,
                uri.query,
                uri.fragment
            )
        }
        return uri
    }

    /** URL query + hash 路由 query 合并（键统一小写，hash 覆盖同名键，文析同款）。 */
    private fun shareParams(uri: URI): Map<String, String> {
        val params = linkedMapOf<String, String>()

        fun collect(query: String?) {
            query.orEmpty().split('&').forEach { field ->
                if (field.isEmpty()) return@forEach
                val index = field.indexOf('=')
                val key = (if (index >= 0) field.substring(0, index) else field).lowercase()
                val raw = if (index >= 0) field.substring(index + 1) else ""
                if (key.isEmpty()) return@forEach
                params[key] = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            }
        }

        collect(uri.rawQuery)
        val fragment = uri.rawFragment.orEmpty()
        if (fragment.isNotEmpty()) {
            // 文析把非 / ? 开头的 fragment 当 query 处理（#shareId=xxx 这种写法）
            val query = when {
                fragment.startsWith("?") -> fragment.substring(1)
                fragment.startsWith("/") -> fragment.substringAfter('?', "")
                else -> fragment
            }
            collect(query)
        }
        return params
    }

    /** 发一跳 GET，只取 302 的 Location；**不跟随**（自己逐跳校验域名与端口）。 */
    private fun fetchLocation(uri: URI): String {
        // followRedirects 是 OkHttpClient 级别的开关，只能在 Request 外临时派生一个不跟随的客户端
        val plain = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val request = Request.Builder()
            .url(uri.toString())
            .header("User-Agent", WopanConstants.UA)
            .get()
            .build()
        return plain.newCall(request).execute().use { response ->
            val location = response.header("Location").orEmpty()
            require(
                response.code in setOf(301, 302, 303, 307, 308) && location.isNotEmpty()
            ) { "联通分享短链接无法解析，请确认链接是否有效" }
            location
        }
    }

    // ---------- 分享目录 ----------

    /**
     * 分享根目录（匿名）。
     *
     * 与其它接口不同：channel 是 `100002`，`param` 用 api-user 密钥加密（clientSecret），
     * 且 body 里明确写 `secretType: ClientSecret`。返回**文件数组**（不是对象）。
     * 业务码：STATUS=200 且 RSP_CODE=0000 才算成功；130013 = 分享已删除/取消。
     */
    suspend fun listShareRoot(target: WopanShareTarget): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val param = JSONObject()
                .put("shareId", target.shareId)
                .put("shareCode", target.passcode)
            val body = JSONObject()
                .put(
                    "header",
                    header(
                        WopanConstants.SHARE_CHANNEL,
                        WopanConstants.SHARE_KEY,
                        System.currentTimeMillis(),
                        100000 + random.nextInt(89999)
                    )
                )
                .put(
                    "body",
                    JSONObject()
                        .put("clientId", target.clientId.ifEmpty { WopanConstants.CLIENT_ID })
                        .put("secret", true)
                        .put("secretType", "ClientSecret")
                        .put("param", encrypt(param, "api-user", ""))
                )
            val res = postJson(
                "${WopanConstants.ORIGIN}/wohome/dispatcher",
                body,
                mapOf(
                    "User-Agent" to WopanConstants.UA,
                    "Origin" to WopanConstants.ORIGIN,
                    "Referer" to "${WopanConstants.ORIGIN}/h5/wocloudshare/"
                ),
                access = null
            )
            val rsp = res.json.optJSONObject("RSP") ?: JSONObject()
            val code = rsp.optString("RSP_CODE")
            if (!res.ok || res.json.optString("STATUS") != "200" || code != "0000") {
                val desc = rsp.optString("RSP_DESC")
                throw IllegalStateException(
                    if (code == "130013") {
                        "联通分享文件已删除或分享已取消，请使用有效链接"
                    } else {
                        desc.ifBlank { "联通分享解析失败（$code），请检查链接和提取码" }
                    }
                )
            }
            objects(decryptValue(rsp.opt("DATA"), "api-user", ""))
        }

    /** 分享子目录：QueryShareFiles（必须带访问令牌，wohome 通道）。 */
    suspend fun listShareFolder(
        access: String,
        shareId: String,
        directoryId: String
    ): List<JSONObject> {
        val data = call(
            access,
            "QueryShareFiles",
            JSONObject().put("directoryId", directoryId).put("id", shareId)
        )
        return objects(data.opt("files"))
    }

    /**
     * 取分享文件下载地址（LoginRequired：调用方负责给访问令牌）。
     * 返回的地址是签名直链，有效期短，取到就应立即下载。
     */
    suspend fun shareDownloadUrl(access: String, fid: String): String? {
        val data = call(
            access,
            "GetDownloadUrlV2",
            JSONObject().put("type", "1").put("fidList", JSONArray().put(fid))
        )
        return pickDownloadUrl(data, fid)
    }

    /**
     * 旧版取链（GetDownloadUrl）：`DATA` 是**数组**，被 [call] 包成 `{list:[...]}`。
     * V2 节点不可用时的回退路径；分享场景 spaceType 固定 "0"。
     */
    suspend fun legacyDownloadUrl(access: String, fid: String): String? {
        val data = call(
            access,
            "GetDownloadUrl",
            JSONObject()
                .put("spaceType", "0")
                .put("fidList", JSONArray().put(fid))
        )
        return pickDownloadUrl(data, fid)
    }

    private fun pickDownloadUrl(data: JSONObject, fid: String): String? {
        val list = data.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optString("fid") == fid) {
                return item.optString("downloadUrl").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ---------- 直链探活 ----------

    /**
     * 直链是否可用（Range: bytes=0-0 只看响应头，不读 body）。
     *
     * @return true = 可用；false = 明确不可用（长度对不上/节点报错）；
     *   null = 没拿到 HTTP 响应（连接被重置等）—— **不能**据此判签名失效，
     *   交给下载器发起真实请求去验证/重试。
     */
    suspend fun downloadAvailable(address: String, size: Long): Boolean? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(address)
                .header("User-Agent", WopanConstants.UA)
                .header("Referer", WopanConstants.DOWNLOAD_REFERER)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val range = response.header("Content-Range").orEmpty().trim()
                when (response.code) {
                    206 -> {
                        val match = Regex("^bytes 0-0/(\\d+)$", RegexOption.IGNORE_CASE).find(range)
                        match != null && match.groupValues[1].toLongOrNull() == size
                    }

                    200 -> response.header("Content-Length")?.toLongOrNull() == size
                    else -> size == 0L && response.code == 416 && range == "bytes */0"
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    // ---------- 带令牌的业务调用 ----------

    /**
     * 发一次带令牌的 dispatcher 请求并解出 `DATA`。
     *
     * - wohome 通道（除 ClassifyRule 外）会把 `clientId` 塞进 param；
     * - `ClassifyRule` / `GetZoneInfo` 走 `key: true`（密钥模式），其余走 `secret: true`；
     * - 401/403，或 RSP_DESC 里出现 token/登录/令牌/鉴权 → [LoginRequiredException]；
     * - `GetDownloadUrl` 的 DATA 是数组，包成 `{list:[...]}` 统一成对象。
     */
    suspend fun call(
        access: String,
        key: String,
        param: JSONObject,
        channel: String = "wohome"
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject(param.toString())
        if (channel == "wohome" && key != "ClassifyRule") {
            payload.put("clientId", WopanConstants.CLIENT_ID)
        }
        val classify = key == "ClassifyRule" || key == "GetZoneInfo"
        val body = JSONObject()
            .put(
                "header",
                header(channel, key, System.currentTimeMillis(), 100000 + random.nextInt(8999))
            )
            .put(
                "body",
                JSONObject().apply {
                    if (channel == "api-user") put("clientId", WopanConstants.CLIENT_ID)
                    put(if (classify) "key" else "secret", true)
                    put("param", encrypt(payload, channel, access))
                }
            )
        val res = postJson(
            "${WopanConstants.ORIGIN}/$channel/dispatcher",
            body,
            mapOf(
                "User-Agent" to WopanConstants.UA,
                "Origin" to WopanConstants.WEB_SITE,
                "Referer" to "${WopanConstants.WEB_SITE}/"
            ),
            access
        )
        val rsp = res.json.optJSONObject("RSP") ?: JSONObject()
        val code = rsp.optString("RSP_CODE")
        val desc = rsp.optString("RSP_DESC")
        if (res.code == 401 || res.code == 403 ||
            (code != "0000" && Regex("token|登录|令牌|鉴权", RegexOption.IGNORE_CASE).containsMatchIn(desc))
        ) {
            throw LoginRequiredException("联通云盘登录已失效，请重新登录")
        }
        if (!res.ok || res.json.optString("STATUS") != "200" || code != "0000") {
            val shown = code.ifEmpty { res.json.optString("STATUS") }.ifEmpty { res.code.toString() }
            throw IllegalStateException("联通云盘请求失败（$shown），请稍后重试")
        }
        val data = rsp.opt("DATA")
        if (data == null || data == JSONObject.NULL || data == "") return@withContext JSONObject()
        val decoded = decryptValue(data, channel, access)
        if (key == "GetDownloadUrl" && decoded is JSONArray) {
            return@withContext JSONObject().put("list", decoded)
        }
        require(decoded is JSONObject) { "联通云盘响应格式错误" }
        decoded
    }

    // ---------- 底座 ----------

    private fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        access: String?
    ): ApiResponse {
        val builder = Request.Builder()
            .url(url)
            .header("Accept-Language", "zh-CN")
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (!access.isNullOrEmpty()) builder.header("Accesstoken", access)
        val request = builder.post(body.toString().toRequestBody(jsonMediaType)).build()
        return client.newCall(request).execute().use { response ->
            // 401/403 常常没有 JSON body（网关直接拒），按「空数据 + 状态码」交给上层判登录失效
            if (response.code == 401 || response.code == 403) {
                return@use ApiResponse(response.code, JSONObject())
            }
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("联通云盘请求失败（HTTP ${response.code}），请稍后重试")
            }
            ApiResponse(response.code, json)
        }
    }

    /** 解出 `JSONObject` 数组；结构不对统一报同一条可读错误（文析同口径）。 */
    private fun objects(value: Any?): List<JSONObject> {
        val array = value as? JSONArray ?: throw IllegalStateException("联通未返回有效分享文件列表")
        val list = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) {
            list.add(array.optJSONObject(i) ?: throw IllegalStateException("联通未返回有效分享文件列表"))
        }
        return list
    }
}

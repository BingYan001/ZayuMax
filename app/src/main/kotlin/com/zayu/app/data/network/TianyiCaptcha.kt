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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 天翼安全验证（滑块 / 点选）客户端，文析 AsterLink tianyi_captcha.dart 口径。
 *
 * 触发点（不在分享解析流程里）：天翼**账号密码登录**时 ——
 * - 登录前探测 `oauth2/needcaptcha.do` 返回 `1`；
 * - 或 `oauth2/loginSubmit.do` 返回 result -2 / -1101（安全验证未通过）需要重试。
 *
 * 使用方式（不在本类里弹 UI）：上层先调 [load] 拿 [Image]（含背景图与拼图块/点选提示），
 * 引导用户完成拖动或点选后组装 [Gesture]，调 [verify] 换 [Proof]；
 * 把 Proof 的 `captchaType / captchaToken / validateCode` 填进 loginSubmit.do 表单即可。
 *
 * 协议要点：
 * - 每次请求的业务 JSON 用随机 16 字节 key 做 AES/CBC/PKCS5 加密（key 同时充当 IV），
 *   密文 hex 大写放 `pb`；key 本体用官方 RSA 公钥（RSAES-PKCS1-v1_5）加密后 Base64 放 `cp`；
 * - 接口是 JSONP（`callback=xxx(...)`），`result != "0"` 即失败，50009 = 尝试次数过多；
 * - 校验响应 `data` 是 hex 密文，用同一把 key 解出 `{captchaType, token, validate}`；
 * - 一张图只能验证一次（失败也算用掉），刷新必须重新 load。
 */
class TianyiCaptcha(
    private val referer: String,
    private val appId: String,
    private val clientProvider: () -> OkHttpClient = { captchaClient() }
) {

    // ---------- 数据 ----------

    /** 验证码图片：type 1 = 滑块（有 piece 拼图块），2 = 点选（piece 为空，instruction 是文字提示） */
    class Image(
        val type: Int,
        val token: String,
        val background: ByteArray,
        val piece: ByteArray?,
        val instruction: String
    )

    /** 校验通过凭证：填回登录表单的 captchaType / captchaToken / validateCode */
    class Proof(val type: Int, val token: String, val validate: String)

    /** 用户手势坐标（验证码画布为 310 逻辑像素宽，与屏幕缩放无关） */
    data class Point(val x: Double, val y: Double)

    /** 轨迹采样：相邻点位移 / 相邻采样时间差 */
    data class Rate(val pointDiff: Double, val timeDiff: Double)

    /** 一次拖动/点选的完整手势：type 1 传 1 个点（落点），type 2 传 3 个点（按序点选） */
    data class Gesture(val points: List<Point>, val rates: List<Rate>, val dragTimeMs: Long)

    // ---------- 常量 ----------

    companion object {
        const val GET_URL = "https://open.e.189.cn/gw/captcha/get.do"
        const val CHECK_URL = "https://open.e.189.cn/gw/captcha/check.do"

        /** 画布逻辑宽度（坐标校验用） */
        const val CANVAS_WIDTH = 310.0

        /** 官方验证码 RSA 公钥（加密 AES key 用，文析 _publicKey） */
        private const val PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDky91Sokyr2UI/K87VMiZp/Pmiggg4fFKgclUZoPCoO+FvdeU/wSvv59Z6fEZi4Uvtzzv5UqCMfFRykokoiGSq8B3X1kr24RbtsWif/+pxfRDCA8tXw3V2DIZ/a03tg8BBgQLpdWuwTmM1448WFIs5O9pyFgjKDFoo5cWvs88HBQIDAQAB"

        private const val CALLBACK = "asterlinkCaptcha"

        /** 验证码接口专用客户端：禁重定向，读超时放宽（图片较大） */
        private fun captchaClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        private fun randomId(length: Int): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            return buildString(length) { repeat(length) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
        }
    }

    /** 每个实例一个固定指纹（文析 _finger：SecureRandom nextInt(0x7fffffff)） */
    private val finger = SecureRandom().nextInt(0x7fffffff)

    /** 当前未消费的验证码；一张图只能验证一次（失败也算用掉） */
    private var current: Image? = null

    // ---------- 加密 ----------

    private val rsaCipher: Cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
        val der = Base64.decode(PUBLIC_KEY, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        init(Cipher.ENCRYPT_MODE, key)
    }

    /** 业务 payload → AES/CBC 加密打包；返回请求参数 JSON 与解密响应用的 key（key 兼作 IV） */
    private fun pack(payload: JSONObject): Pair<JSONObject, ByteArray> {
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        }
        val pb = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val cp = Base64.encodeToString(rsaCipher.doFinal(key), Base64.NO_WRAP)
        val packed = JSONObject()
            .put("pb", pb)
            .put("cp", cp)
            .put("appId", appId)
            .put("version", "1.0.1")
            .put("reqId", randomId(32))
        return packed to key
    }

    // ---------- 请求 ----------

    /** GET + JSONP 解包 + result 校验（文析 _response 同款） */
    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            header("User-Agent", TianyiConstants.WEB_UA)
            header("Accept", "application/json, text/plain, */*")
            header("Referer", referer)
            header("Origin", "https://open.e.189.cn")
        }.build()
        clientProvider().newCall(request).execute().use { resp ->
            var body = resp.body?.string().orEmpty()
            require(resp.isSuccessful && body.length <= 2 * 1024 * 1024) {
                "天翼验证码加载失败，请刷新重试"
            }
            body = body.trim()
            if (body.startsWith("$CALLBACK(")) {
                if (body.endsWith(";")) body = body.removeSuffix(";").trim()
                require(body.endsWith(")")) { "天翼验证码响应格式错误" }
                body = body.substring(CALLBACK.length + 1, body.length - 1)
            }
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw IllegalStateException("天翼验证码响应格式错误") }
            val result = json.optString("result")
            require(result == "0") {
                if (result == "50009") "验证码尝试次数过多，请稍后再登录"
                else "天翼验证码未通过，请刷新后重新验证"
            }
            json
        }
    }

    /** data:image/...;base64 → 图片字节（文析 _image 同款校验） */
    private fun imageBytes(value: String): ByteArray {
        require(value.length <= 900000) { "天翼验证码图片过大，请刷新" }
        val match = Regex(
            "^data:image/(?:png|jpg|jpeg|webp);base64,([A-Za-z0-9+/=\\r\\n]+)$"
        ).find(value) ?: throw IllegalStateException("天翼验证码图片格式已变化，请稍后重试")
        val bytes = runCatching {
            Base64.decode(match.groupValues[1].replace(Regex("\\s"), ""), Base64.DEFAULT)
        }.getOrElse { throw IllegalStateException("天翼验证码图片损坏，请刷新") }
        require(bytes.isNotEmpty() && bytes.size <= 650000) { "天翼验证码图片无效" }
        return bytes
    }

    private fun requestUrl(endpoint: String, packed: JSONObject): String {
        val params = buildList {
            for (key in listOf("pb", "cp", "appId", "version", "reqId")) {
                add("${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(packed.optString(key), "UTF-8")}")
            }
            add("callback=$CALLBACK")
        }.joinToString("&")
        return "$endpoint?$params"
    }

    // ---------- 对外流程 ----------

    /**
     * 拉取一张新验证码。type 1 = 滑块拼图（背景图 + 拼图块），type 2 = 文字点选（背景图 + 文字提示）。
     * 上层展示 [Image.background]（宽 310 逻辑像素）引导用户操作。
     */
    suspend fun load(): Image = withContext(Dispatchers.IO) {
        current = null
        val payload = JSONObject()
            .put("appId", appId)
            .put("captchaType", 1)
            .put("referer", referer)
            .put("time", System.currentTimeMillis())
            .put("finger", finger)
            .put("width", "310")
        val (packed, _) = pack(payload)
        val data = getJson(requestUrl(GET_URL, packed)).optJSONObject("data")
            ?: throw IllegalStateException("天翼验证码响应格式错误")
        val type = data.optInt("captchaType", 0)
        require(type == 1 || type == 2) { "天翼返回了暂不支持的验证方式" }
        val token = data.optString("token")
        require(token.isNotEmpty() && token.length <= 8192) { "天翼验证码缺少验证标识，请刷新" }
        val instruction = if (type == 2) {
            data.optString("front")
                .replace(Regex("""[\[\]"']"""), "")
                .replace(",", "、")
        } else {
            "向右拖动滑块，完成拼图"
        }
        require(instruction.length <= 120) { "天翼验证码提示无效，请刷新" }
        Image(
            type = type,
            token = token,
            background = imageBytes(data.optString("bg")),
            piece = if (type == 1) imageBytes(data.optString("front")) else null,
            instruction = instruction
        ).also { current = it }
    }

    /**
     * 提交用户手势换取 [Proof]。同一张图只能提交一次（失败也算用掉，需重新 [load]）。
     *
     * @param image 最近一次 [load] 返回的验证码
     * @param gesture type 1（滑块）：1 个点（滑块落点 x∈[0,310]，y∈[0,1000]）+ 轨迹采样 + 拖动总时长；
     *                type 2（点选）：按序 3 个点
     */
    suspend fun verify(image: Image, gesture: Gesture): Proof = withContext(Dispatchers.IO) {
        require(current === image) { "验证码已更新，请重新验证" }
        require(gesture.points.size == (if (image.type == 1) 1 else 3)) { "请完成验证码操作" }
        require(gesture.rates.size <= 50) { "请完成验证码操作" }
        require(gesture.dragTimeMs in 0..180000) { "请完成验证码操作" }
        for (p in gesture.points) {
            require(
                p.x.isFinite() && p.y.isFinite() &&
                    p.x in 0.0..CANVAS_WIDTH && p.y in 0.0..1000.0
            ) { "验证码坐标无效，请重新操作" }
        }
        for (r in gesture.rates) {
            require(r.pointDiff.isFinite() && r.timeDiff.isFinite() && r.timeDiff >= 0) {
                "验证码操作无效，请重新操作"
            }
        }
        current = null
        val points = JSONArray()
        for (p in gesture.points) points.put(JSONObject().put("x", p.x).put("y", p.y))
        val rates = JSONArray()
        for (r in gesture.rates) {
            rates.put(JSONObject().put("pointDiff", r.pointDiff).put("timeDiff", r.timeDiff))
        }
        val payload = JSONObject()
            .put("token", image.token)
            .put("captchaType", image.type)
            .put("points", points)
            .put("rates", rates)
            .put("dragTime", gesture.dragTimeMs)
            .put("time", System.currentTimeMillis())
            .put("finger", finger)
        val (packed, key) = pack(payload)
        val result = getJson(requestUrl(CHECK_URL, packed))
        val hex = result.optString("data")
        require(
            hex.length <= 32768 && hex.length % 32 == 0 && Regex("^[A-Fa-f0-9]+$").matches(hex)
        ) { "天翼验证码校验响应无效，请刷新" }
        val ciphertext = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        val proof = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            }
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        }.getOrElse { throw IllegalStateException("天翼验证码校验响应无效，请刷新") }
        val token = proof.optString("token")
        val validate = proof.optString("validate")
        require(
            proof.optInt("captchaType", 0) == image.type &&
                token.isNotEmpty() && validate.isNotEmpty() &&
                token.length <= 8192 && validate.length <= 8192
        ) { "天翼验证码校验尚未完成，请重新验证" }
        Proof(image.type, token, validate)
    }
}

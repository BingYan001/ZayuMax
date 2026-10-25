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

package com.zayu.app.data.account

import kotlin.math.pow

/**
 * 账号「运行健康度」的纯逻辑层（平台无关）。
 *
 * ## 为什么需要它
 *
 * 在此之前，账号请求失败的处理是**即时反应式**的：失败了就报错给用户，用户手动重试。
 * 两个问题：
 *  1. **没有熔断**。一个已被限速的账号会被反复打 —— 每次重试都在加深风控印象。
 *  2. **状态不跨进程**。即便某处记了「这个账号刚失败」，重启即清零。
 *
 * 本对象只做**判定与计算**，不持有状态、不碰数据库 —— 状态由各平台
 * `*AccountEntity` 的字段承载（见 v20 迁移新增的 `lastErrorCode` / `cooldownUntil` /
 * `failCount` / `okCount`），因此**天然跨进程存活**。
 *
 * ## 为什么不直接做「多账号池」
 *
 * 核实过：本项目 10 个平台的账号表都是单账号结构（`@PrimaryKey id = "平台名"`，
 * `INSERT ... REPLACE`）。所以「池」里目前只有一个账号，加权轮询无对象可轮。
 * 本层是**多账号的前置地基**：等表结构改成多账号后，[weight] 直接就能用于轮询排序，
 * 不需要重写退避与健康度逻辑。
 */
object AccountHealth {

    // ---------- 退避时长（按错误类型差异化） ----------

    /** 默认退避：普通网络错误（超时、连接失败、5xx）——短，多半是瞬时问题 */
    const val COOLDOWN_DEFAULT_MS = 60_000L

    /** 限速类：平台明确回「太快了」——中，再打就是自找风控 */
    const val COOLDOWN_RATE_LIMIT_MS = 5 * 60_000L

    /** 账号异常类：需要用户介入（重新登录/验证）——长，自动重试没有意义 */
    const val COOLDOWN_ACCOUNT_BAD_MS = 30 * 60_000L

    /** 鉴权失败（HTTP 401/403）：多半需要用户重新登录，冷却长一些避免无谓打点 */
    const val COOLDOWN_AUTH_MS = 10 * 60_000L

    /**
     * 按错误码给出退避时长。
     *
     * 错误码来源有两类，**共用一套判定**：
     *  - **HTTP 状态码**（401/403/429/5xx）
     *  - **平台 errno**（百度为负值：-6 限速、-9 频繁、-12 账号异常，见 `BaiduApi.checkErrno`）
     *
     * 取值依据是「这个错误重试有没有意义」：
     *  - 限速/频繁 → 等一会就好，中等退避
     *  - 账号异常 → 等多久都没用，长退避 + 应提示用户
     *  - 5xx/超时 → 服务端瞬时抖动，短退避
     */
    fun cooldownFor(errorCode: Int): Long = when (errorCode) {
        // 百度 errno：限速 / 频繁请求
        -6, -9 -> COOLDOWN_RATE_LIMIT_MS
        // 百度 errno：账号异常（风控命中、需要验证）
        -12, -32, -62 -> COOLDOWN_ACCOUNT_BAD_MS
        // 31066：百度「该分享已失效」类 —— 重试无意义，但属于链接问题不是账号问题，给中等
        31066 -> COOLDOWN_RATE_LIMIT_MS
        // HTTP：限速
        429 -> COOLDOWN_RATE_LIMIT_MS
        // HTTP：鉴权
        401, 403 -> COOLDOWN_AUTH_MS
        // HTTP：服务端问题（可重试）
        in 500..599 -> COOLDOWN_DEFAULT_MS
        // 其它（含 0 = 无错）不冷却
        else -> 0L
    }

    /**
     * 错误是否属于「账号级」问题（需要提示用户重新登录，而不只是等待）。
     *
     * UI 用它决定文案：账号级问题应该引导用户去重新登录，
     * 而非让用户干等冷却结束（等完了还是失败，体验更差）。
     */
    fun isAccountLevelError(errorCode: Int): Boolean =
        errorCode in setOf(-12, -32, -62, 401, 403)

    // ---------- 可用性判定 ----------

    /**
     * 账号当前是否可用。
     *
     * @param cooldownUntil 熔断截止时刻（毫秒；0 = 未熔断）。
     *   ★ 传的是**绝对时刻**而非剩余时长 —— 这样即使进程被杀、时间流逝，
     *   重启后判定天然正确，不需要任何「补算已过时间」的逻辑。
     */
    fun isAvailable(now: Long, cooldownUntil: Long): Boolean = now >= cooldownUntil

    /** 剩余冷却毫秒（0 表示已可用）。UI 用于显示「还需等待 3 分钟」 */
    fun remainingCooldownMs(now: Long, cooldownUntil: Long): Long =
        (cooldownUntil - now).coerceAtLeast(0L)

    // ---------- 加权（多账号时的排序依据） ----------

    /**
     * 账号权重：成功率² × 经验置信度。
     *
     * 三个设计取舍：
     *
     * 1. **成功率要平方**。线性成功率下，80% 与 95% 的账号权重只差 19%，
     *    不足以让轮询明显偏向好账号。平方后差距拉到 41%，劣质账号被自然边缘化，
     *    但仍保留非零权重 —— 不是"封杀"，只是"少用"，避免单点依赖。
     *
     * 2. **样本少时要向 0.5 收缩（拉普拉斯平滑）**。新账号 1 次成功就是 100% 成功率，
     *    平方后权重 1.0，会瞬间挤掉所有老账号。用 `(ok + 1) / (total + 2)`
     *    让 1 次成功的账号只有 66% 而非 100%，样本足够后才收敛到真实值。
     *
     * 3. **成功权重下限 0.05**。完全不给 100% 失败的账号权重会让"池里全是坏账号"时
     *    无账号可选（返回 null，功能直接不可用）。保留极小权重意味着：
     *    宁可试一次可能失败的，也好过告诉用户"没有可用账号"。
     *
     * @return 权重，范围 (0.0, 1.0]；调用方仍应自行排除冷却中的账号
     */
    fun weight(okCount: Int, failCount: Int): Double {
        val ok = okCount.coerceAtLeast(0)
        val fail = failCount.coerceAtLeast(0)
        val total = ok + fail
        // 拉普拉斯平滑：无样本时 0.5（中性），样本多时趋近真实成功率
        val smoothed = (ok + 1.0) / (total + 2.0)
        return smoothed.pow(2).coerceAtLeast(0.05)
    }

    /**
     * 健康分（0~100），供 UI 展示。与 [weight] 不同，它**把冷却折进去**，
     * 因为用户看到的是"这个账号现在能不能用"，而非纯历史成功率。
     *
     * 冷却中直接压到 0 —— 这是刻意的：如果只降不归零，用户会疑惑
     * "为什么 60 分的账号还是用不了"。0 分配合"冷却中"标签语义自洽。
     */
    fun score(now: Long, okCount: Int, failCount: Int, cooldownUntil: Long): Int {
        if (!isAvailable(now, cooldownUntil)) return 0
        return (weight(okCount, failCount) * 100).toInt().coerceIn(0, 100)
    }
}

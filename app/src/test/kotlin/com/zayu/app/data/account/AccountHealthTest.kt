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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountHealthTest {

    // ---------- 退避时长 ----------

    @Test
    fun rateLimitErrorsGetMediumCooldown() {
        // 百度 errno：-6 限速 / -9 请求过快
        assertEquals(AccountHealth.COOLDOWN_RATE_LIMIT_MS, AccountHealth.cooldownFor(-6))
        assertEquals(AccountHealth.COOLDOWN_RATE_LIMIT_MS, AccountHealth.cooldownFor(-9))
        // HTTP 429
        assertEquals(AccountHealth.COOLDOWN_RATE_LIMIT_MS, AccountHealth.cooldownFor(429))
    }

    @Test
    fun accountLevelErrorsGetLongCooldown() {
        assertEquals(AccountHealth.COOLDOWN_ACCOUNT_BAD_MS, AccountHealth.cooldownFor(-12))
        assertEquals(AccountHealth.COOLDOWN_ACCOUNT_BAD_MS, AccountHealth.cooldownFor(-32))
    }

    @Test
    fun authErrorsGetAuthCooldown() {
        assertEquals(AccountHealth.COOLDOWN_AUTH_MS, AccountHealth.cooldownFor(401))
        assertEquals(AccountHealth.COOLDOWN_AUTH_MS, AccountHealth.cooldownFor(403))
    }

    @Test
    fun serverErrorsGetShortCooldownAndUnknownGetsNone() {
        assertEquals(AccountHealth.COOLDOWN_DEFAULT_MS, AccountHealth.cooldownFor(500))
        assertEquals(AccountHealth.COOLDOWN_DEFAULT_MS, AccountHealth.cooldownFor(503))
        // 0 = 无错；未知码一律不冷却（宁可多试一次，也不要把可用账号误判为冷却）
        assertEquals(0L, AccountHealth.cooldownFor(0))
        assertEquals(0L, AccountHealth.cooldownFor(200))
        assertEquals(0L, AccountHealth.cooldownFor(-9999))
    }

    @Test
    fun accountLevelClassificationMatchesCooldownIntent() {
        assertTrue(AccountHealth.isAccountLevelError(-12))
        assertTrue(AccountHealth.isAccountLevelError(401))
        // 限速不是账号级问题 —— 等一会就好，不该让用户去重新登录
        assertFalse(AccountHealth.isAccountLevelError(-6))
        assertFalse(AccountHealth.isAccountLevelError(429))
        assertFalse(AccountHealth.isAccountLevelError(0))
    }

    // ---------- 可用性 ----------

    @Test
    fun availabilityUsesAbsoluteDeadline() {
        val now = 1_000_000L
        // cooldownUntil 为 0（从未熔断）→ 永远可用
        assertTrue(AccountHealth.isAvailable(now, 0L))
        // 截止时刻在未来 → 不可用
        assertFalse(AccountHealth.isAvailable(now, now + 1))
        // 恰好等于 → 可用（边界取 >=，避免"正好到期却还要等一毫秒"）
        assertTrue(AccountHealth.isAvailable(now, now))
    }

    @Test
    fun remainingCooldownNeverNegative() {
        val now = 1_000_000L
        assertEquals(0L, AccountHealth.remainingCooldownMs(now, 0L))
        assertEquals(0L, AccountHealth.remainingCooldownMs(now, now - 5000))
        assertEquals(5000L, AccountHealth.remainingCooldownMs(now, now + 5000))
    }

    // ---------- 加权 ----------

    @Test
    fun weightIsNeutralForFreshAccount() {
        // 零样本 → 拉普拉斯平滑给出 0.5，平方后 0.25
        val w = AccountHealth.weight(0, 0)
        assertEquals(0.25, w, 1e-9)
    }

    @Test
    fun weightFavoursHighSuccessAccounts() {
        val good = AccountHealth.weight(95, 5)     // 95%
        val bad = AccountHealth.weight(80, 20)     // 80%
        assertTrue("95% 的权重要明显高于 80%", good > bad)
        // 平方的作用：线性下只差 19%，平方后差距应显著拉开
        val linearRatio = 0.95 / 0.80
        val weightRatio = good / bad
        assertTrue("平方应让差距大于线性", weightRatio > linearRatio)
    }

    @Test
    fun weightNeverDropsToZeroSoPoolIsNeverEmpty() {
        // 全失败账号仍有极小权重：宁可试一次，也好过告诉用户"没有可用账号"
        val w = AccountHealth.weight(0, 100)
        assertTrue("全失败账号权重必须 > 0", w > 0.0)
        assertEquals(0.05, w, 1e-9)
    }

    @Test
    fun weightClampsNegativeCounts() {
        // 传入负数（异常数据）不应导致 NaN 或负权重
        val w = AccountHealth.weight(-5, -5)
        assertTrue(w > 0.0)
        assertFalse(w.isNaN())
    }

    // ---------- 健康分 ----------

    @Test
    fun scoreIsZeroWhileCoolingDown() {
        val now = 1_000_000L
        // 历史成绩很好，但正在冷却 → 必须显示 0。否则用户会疑惑"90 分为什么用不了"
        assertEquals(0, AccountHealth.score(now, 100, 0, now + 60_000))
    }

    @Test
    fun scoreReflectsWeightWhenAvailable() {
        val now = 1_000_000L
        val s = AccountHealth.score(now, 100, 0, 0L)
        assertTrue("高成功率账号应接近满分", s in 90..100)
        val low = AccountHealth.score(now, 0, 100, 0L)
        assertEquals("全失败账号应贴地板", 5, low)
    }

    @Test
    fun scoreStaysInRange() {
        val now = 1_000_000L
        for (ok in listOf(0, 1, 50, 1000)) {
            for (fail in listOf(0, 1, 50, 1000)) {
                val s = AccountHealth.score(now, ok, fail, 0L)
                assertTrue("健康分越界: ok=$ok fail=$fail score=$s", s in 0..100)
            }
        }
    }
}

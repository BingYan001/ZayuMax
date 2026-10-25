package com.yunx.app.ui.theme

/** 背景渐变预设（色值与手机端 BackgroundGradients 完全一致） */
data class BackgroundGradientPreset(val name: String, val start: Long, val end: Long)

object BackgroundGradients {

    val presets = listOf(
        BackgroundGradientPreset("深海", 0xFF0B1E3AL, 0xFF14324FL),
        BackgroundGradientPreset("暮色", 0xFF2B1B33L, 0xFF3D2A1FL),
        BackgroundGradientPreset("苔原", 0xFF16281FL, 0xFF2A3B26L),
        BackgroundGradientPreset("岩层", 0xFF23262BL, 0xFF3A3F46L),
        BackgroundGradientPreset("晚霞", 0xFF3A2230L, 0xFF4A3350L),
        BackgroundGradientPreset("雾蓝", 0xFF1B2735L, 0xFF2C3E50L)
    )

    fun byIndex(id: Int): BackgroundGradientPreset? =
        presets.getOrNull(id.coerceIn(0, presets.size - 1))
}

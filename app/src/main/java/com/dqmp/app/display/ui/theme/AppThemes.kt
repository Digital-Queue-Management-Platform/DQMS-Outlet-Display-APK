package com.dqmp.app.display.ui.theme

import androidx.compose.ui.graphics.Color

// Emerald Theme (Default)
object EmeraldTheme {
    val primary = Color(0xFF059669)      // emerald-600
    val primaryDark = Color(0xFF047857)  // emerald-700
    val secondary = Color(0xFF10B981)    // emerald-500
    val accent = Color(0xFF34D399)       // emerald-400
    val background = Color(0xFF064E3B)   // emerald-900
    val surface = Color(0xFF065F46)      // emerald-800
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFA7F3D0) // emerald-200
}

// Sky Theme
object SkyTheme {
    val primary = Color(0xFF0284C7)      // sky-600
    val primaryDark = Color(0xFF0369A1)  // sky-700
    val secondary = Color(0xFF0EA5E9)    // sky-500
    val accent = Color(0xFF38BDF8)       // sky-400
    val background = Color(0xFF0C4A6E)   // sky-900
    val surface = Color(0xFF075985)      // sky-800
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFF7DD3FC) // sky-300
}

// Indigo Theme
object IndigoTheme {
    val primary = Color(0xFF4338CA)      // indigo-600
    val primaryDark = Color(0xFF3730A3)  // indigo-700
    val secondary = Color(0xFF6366F1)    // indigo-500
    val accent = Color(0xFF818CF8)       // indigo-400
    val background = Color(0xFF312E81)   // indigo-900
    val surface = Color(0xFF3730A3)      // indigo-800
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFA5B4FC) // indigo-300
}

// Slate Theme (Dark)
object SlateTheme {
    val primary = Color(0xFF475569)      // slate-600
    val primaryDark = Color(0xFF334155)  // slate-700
    val secondary = Color(0xFF64748B)    // slate-500
    val accent = Color(0xFF94A3B8)       // slate-400
    val background = Color(0xFF0F172A)   // slate-900
    val surface = Color(0xFF1E293B)      // slate-800
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFCBD5E1) // slate-300
}

data class AppTheme(
    val primary: Color,
    val primaryDark: Color,
    val secondary: Color,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val textSecondary: Color
)

object ThemeProvider {
    fun getTheme(themeName: String): AppTheme {
        return when (themeName.lowercase()) {
            "emerald" -> AppTheme(
                primary = EmeraldTheme.primary,
                primaryDark = EmeraldTheme.primaryDark,
                secondary = EmeraldTheme.secondary,
                accent = EmeraldTheme.accent,
                background = EmeraldTheme.background,
                surface = EmeraldTheme.surface,
                text = EmeraldTheme.text,
                textSecondary = EmeraldTheme.textSecondary
            )
            "sky" -> AppTheme(
                primary = SkyTheme.primary,
                primaryDark = SkyTheme.primaryDark,
                secondary = SkyTheme.secondary,
                accent = SkyTheme.accent,
                background = SkyTheme.background,
                surface = SkyTheme.surface,
                text = SkyTheme.text,
                textSecondary = SkyTheme.textSecondary
            )
            "indigo" -> AppTheme(
                primary = IndigoTheme.primary,
                primaryDark = IndigoTheme.primaryDark,
                secondary = IndigoTheme.secondary,
                accent = IndigoTheme.accent,
                background = IndigoTheme.background,
                surface = IndigoTheme.surface,
                text = IndigoTheme.text,
                textSecondary = IndigoTheme.textSecondary
            )
            "slate" -> AppTheme(
                primary = SlateTheme.primary,
                primaryDark = SlateTheme.primaryDark,
                secondary = SlateTheme.secondary,
                accent = SlateTheme.accent,
                background = SlateTheme.background,
                surface = SlateTheme.surface,
                text = SlateTheme.text,
                textSecondary = SlateTheme.textSecondary
            )
            else -> AppTheme(
                primary = EmeraldTheme.primary,
                primaryDark = EmeraldTheme.primaryDark,
                secondary = EmeraldTheme.secondary,
                accent = EmeraldTheme.accent,
                background = EmeraldTheme.background,
                surface = EmeraldTheme.surface,
                text = EmeraldTheme.text,
                textSecondary = EmeraldTheme.textSecondary
            )
        }
    }
}
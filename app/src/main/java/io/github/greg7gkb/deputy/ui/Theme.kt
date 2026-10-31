package io.github.greg7gkb.deputy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The palette from docs/ux/screens.html. Calm and plain on purpose: the ledger is the product. */
object Palette {
    val Background = Color(0xFFF6F5F2)
    val Card = Color.White
    val Line = Color(0xFFDCDAD5)
    val Ink = Color(0xFF17181A)
    val Muted = Color(0xFF5D5F63)
    val Chip = Color(0xFFECECEA)
    val Segment = Color(0xFFE6E5E1)
    val Note = Color(0xFFECEBE7)
    val GreenBg = Color(0xFFE2EFE8)
    val Green = Color(0xFF2B5A44)
    val AmberBg = Color(0xFFF7E8D0)
    val Amber = Color(0xFF6A4717)
    val BlueBg = Color(0xFFE4EBF6)
    val Blue = Color(0xFF2B4A78)
    val Red = Color(0xFFA3321F)
    val RedBg = Color(0xFFFBEEE6)
    val RedLine = Color(0xFFE9C3A8)
}

object Type {
    val Title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    val CardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, lineHeight = 21.sp)
    val Body = TextStyle(fontSize = 14.5.sp, color = Palette.Ink, lineHeight = 20.sp)
    val Sub = TextStyle(fontSize = 13.5.sp, color = Palette.Muted, lineHeight = 19.sp)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = Palette.Muted, lineHeight = 16.sp)
}

private val Colors = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Color.White,
    secondary = Palette.Green,
    onSecondary = Color.White,
    background = Palette.Background,
    onBackground = Palette.Ink,
    surface = Palette.Card,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Chip,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerHighest = Palette.Card,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    error = Palette.Red,
)

@Composable
fun DeputyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(bodyLarge = Type.Body, bodyMedium = Type.Body),
        content = content,
    )
}

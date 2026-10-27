@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.hassanazawi.send.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hassanazawi.send.R

/** Tokens from the Resend-style design file: true black canvas, translucent-white hairlines, no shadows. */
object C {
    val canvas = Color(0xFF000000)
    val card = Color(0xFF0A0A0C)
    val elevated = Color(0xFF101012)
    val deep = Color(0xFF06060A)

    val ink = Color(0xFFFCFDFF)
    val body = Color(0xDBFCFDFF)
    val charcoal = Color(0xB3FCFDFF)
    val mute = Color(0xFFA1A4A5)
    val ash = Color(0xFF888E90)
    val stone = Color(0xFF464A4D)
    val pressed = Color(0xFFF1F7FE)

    val hairline = Color(0x0FFFFFFF)
    val hairlineStrong = Color(0x24FFFFFF)

    val orange = Color(0xFFFF801F)
    val orangeGlow = Color(0x38FF5900)
    val yellow = Color(0xFFFFC53D)
    val blue = Color(0xFF3B9EFF)
    val blueGlow = Color(0x570075FF)
    val green = Color(0xFF11FF99)
    val greenGlow = Color(0x2E22FF99)
    val red = Color(0xFFFF2047)
    val redGlow = Color(0x57FF2047)
}

object R12 {
    const val sm = 6
    const val md = 8
    const val lg = 12
    const val xl = 16
}

// Domaine Display stand-in (free): Instrument Serif. Geist replaces ABC Favorit / Inter.
val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val Sans = FontFamily(
    Font(R.font.geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

val Mono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
)

object T {
    val display = TextStyle(fontFamily = Serif, fontSize = 46.sp, lineHeight = 46.sp, letterSpacing = (-0.46).sp, color = C.ink)
    val title = TextStyle(fontFamily = Serif, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp, color = C.ink)
    val wordmark = TextStyle(fontFamily = Serif, fontSize = 30.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp, color = C.ink)
    val heading = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp, color = C.ink)
    val itemTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp, color = C.ink)
    val body = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp, color = C.mute)
    val bodySm = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = C.mute)
    val button = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = C.ink)
    val caption = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 18.sp, color = C.mute)
    val mono = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 19.sp, color = C.mute)
    val code = TextStyle(fontFamily = Mono, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = 6.sp, color = C.ink)
}

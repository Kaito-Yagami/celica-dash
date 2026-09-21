package com.celica.dash.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.celica.dash.R

/**
 * Three faces, as on the site: Archivo for display and big numerals, IBM Plex Mono for labels and
 * data, Michroma only for the wordmark. Archivo ships as a variable font; each weight below is the
 * same file with a different wght axis.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun archivo(weight: Int) = Font(
    R.font.archivo_var,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val Display = FontFamily(archivo(500), archivo(700), archivo(800), archivo(900))
val Mono = FontFamily(
    Font(R.font.plexmono_regular, FontWeight.Normal),
    Font(R.font.plexmono_medium, FontWeight.Medium),
    Font(R.font.plexmono_semibold, FontWeight.SemiBold),
)
val Wordmark = FontFamily(Font(R.font.michroma_regular, FontWeight.Normal))

/** Mono, small caps-ish, tracked out: the label voice used on every tile. */
val Label = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.16.em, color = Ink.txDim)
val LabelWide = Label.copy(letterSpacing = 0.24.em)
val Data = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.08.em, color = Ink.txDim)
val Numeral = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-0.03).em, color = Ink.tx)
val NumeralUnit = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.12.em, color = Ink.txDim)
val Title = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = (-0.01).em, color = Ink.tx)
val Body = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Ink.txDim)
val Clock = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = 0.01.em, color = Ink.tx)
val WordmarkStyle = TextStyle(fontFamily = Wordmark, fontSize = 13.sp, letterSpacing = 0.2.em, color = Ink.tx)

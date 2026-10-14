package com.gwensmuda.euros

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object EurosColors {
 val Ink=Color(0xFF10151E)
 val Muted=Color(0xFF535F70)
 val Line=Color(0xFFD9E0E9)
 val Blue=Color(0xFF2465DA)
 val Soft=Color(0xFFEDF3FF)
 val Paper=Color(0xFFF5F7FA)
}

/** Shared visual tokens from the approved white/blue prototype. */
@Composable fun EurosTheme(content:@Composable ()->Unit) {
 fun text(size:Int,line:Int,weight:FontWeight=FontWeight.Normal)=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=size.sp,lineHeight=line.sp,fontWeight=weight)
 MaterialTheme(
  colorScheme=lightColorScheme(primary=EurosColors.Blue,onPrimary=Color.White,primaryContainer=EurosColors.Soft,onPrimaryContainer=EurosColors.Ink,secondary=EurosColors.Muted,onSecondary=Color.White,secondaryContainer=EurosColors.Soft,onSecondaryContainer=EurosColors.Blue,tertiary=EurosColors.Blue,background=Color.White,onBackground=EurosColors.Ink,surface=Color.White,onSurface=EurosColors.Ink,surfaceVariant=EurosColors.Paper,onSurfaceVariant=EurosColors.Muted,outline=EurosColors.Line,outlineVariant=EurosColors.Line,surfaceTint=Color.White),
  typography=Typography(headlineLarge=text(30,34,FontWeight.Bold),headlineMedium=text(28,32,FontWeight.Bold),headlineSmall=text(24,29,FontWeight.SemiBold),titleLarge=text(19,25,FontWeight.SemiBold),titleMedium=text(15,21,FontWeight.SemiBold),titleSmall=text(14,20,FontWeight.SemiBold),bodyLarge=text(15,22),bodyMedium=text(14,20),bodySmall=text(12,18),labelLarge=text(13,18,FontWeight.Medium),labelMedium=text(12,16,FontWeight.Medium),labelSmall=text(11,15,FontWeight.Medium)),
  shapes=Shapes(extraSmall=RoundedCornerShape(6.dp),small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(8.dp),large=RoundedCornerShape(12.dp),extraLarge=RoundedCornerShape(16.dp)),content=content)
}

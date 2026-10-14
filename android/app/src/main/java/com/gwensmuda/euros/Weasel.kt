package com.gwensmuda.euros

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val Blue=Color(0xFF2563EB)

@Composable internal fun Weasel(mood:String="neutral") {
 // The character occupies the canvas, rather than a small mark inside a 52dp box.
 Canvas(Modifier.size(width=82.dp,height=90.dp).semantics{contentDescription="Blue-belt weasel"}) {
  val w=size.width;val h=size.height
  val ink=Color(0xFF141923);val fur=Color(0xFFB48C67);val cream=Color(0xFFFFF6E7)
  val outline=androidx.compose.ui.graphics.drawscope.Stroke(width=1.5.dp.toPx())
  fun oval(color:Color,x:Float,y:Float,width:Float,height:Float,border:Boolean=true) {
   val origin=Offset(w*x,h*y);val dimensions=Size(w*width,h*height)
   drawOval(color,origin,dimensions)
   if(border) drawOval(ink,origin,dimensions,style=outline)
  }
  // A long tail and narrow muzzle keep the silhouette recognizably mustelid.
  val tail=androidx.compose.ui.graphics.Path().apply {
   moveTo(w*.66f,h*.76f);cubicTo(w*.95f,h*.92f,w*.99f,h*.48f,w*.82f,h*.44f)
  }
  drawPath(tail,ink,style=androidx.compose.ui.graphics.drawscope.Stroke(12.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))
  drawPath(tail,fur,style=androidx.compose.ui.graphics.drawscope.Stroke(9.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))
  oval(ink,.80f,.405f,.12f,.13f,false)
  oval(fur,.21f,.06f,.18f,.18f);oval(fur,.59f,.06f,.18f,.18f)
  oval(cream,.255f,.10f,.09f,.10f,false);oval(cream,.635f,.10f,.09f,.10f,false)
  oval(Color.White,.25f,.84f,.22f,.11f);oval(Color.White,.52f,.84f,.22f,.11f)
  oval(Color.White,.19f,.48f,.59f,.42f)
  oval(fur,.265f,.11f,.46f,.47f)
  oval(cream,.335f,.30f,.32f,.29f,false)
  if(mood=="neutral") {drawCircle(ink,w*.028f,Offset(w*.385f,h*.29f));drawCircle(ink,w*.028f,Offset(w*.615f,h*.29f))}
  else listOf(.385f,.615f).forEach{x->drawArc(ink,if(mood=="pleased")180f else 0f,180f,false,Offset(w*(x-.04f),h*.27f),Size(w*.08f,h*.06f),style=outline)}
  oval(ink,.455f,.385f,.09f,.055f,false)
  drawLine(ink,Offset(w*.5f,h*.44f),Offset(w*.5f,h*.475f),1.3.dp.toPx())
  drawArc(ink,0f,180f,false,Offset(w*.445f,h*.455f),Size(w*.11f,h*.045f),style=outline)
  // Gi lapels, sleeves and a visible blue belt.
  drawLine(ink,Offset(w*.36f,h*.56f),Offset(w*.57f,h*.74f),1.5.dp.toPx())
  drawLine(ink,Offset(w*.63f,h*.56f),Offset(w*.45f,h*.72f),1.5.dp.toPx())
  drawLine(ink,Offset(w*.27f,h*.65f),Offset(w*.27f,h*.76f),1.2.dp.toPx())
  drawLine(ink,Offset(w*.70f,h*.65f),Offset(w*.70f,h*.76f),1.2.dp.toPx())
  drawRect(Blue,Offset(w*.20f,h*.73f),Size(w*.57f,h*.08f))
  drawRect(ink,Offset(w*.20f,h*.73f),Size(w*.57f,h*.08f),style=outline)
  drawLine(Blue,Offset(w*.50f,h*.775f),Offset(w*.45f,h*.92f),7.dp.toPx())
  drawLine(Blue,Offset(w*.54f,h*.775f),Offset(w*.61f,h*.89f),7.dp.toPx())
  drawCircle(Blue,w*.045f,Offset(w*.52f,h*.775f))
 }
}

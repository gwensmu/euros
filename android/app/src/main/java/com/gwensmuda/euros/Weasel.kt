package com.gwensmuda.euros

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal data class AtlasCell(val offset:IntOffset,val size:IntSize)
/** Integer boundaries include all pixels even when the atlas dimensions aren't divisible by 3/2. */
internal fun weaselAtlasCell(width:Int,height:Int,pose:MascotPose):AtlasCell {
 require(width>=3 && height>=2)
 val index=when(pose){MascotPose.IDLE->0;MascotPose.PLEASED->1;MascotPose.JUDGY->2;MascotPose.TRAINING->3;MascotPose.RECOVERY->4;MascotPose.REST->5}
 val column=index%3;val row=index/3
 val left=width*column/3;val top=height*row/2
 return AtlasCell(IntOffset(left,top),IntSize(width*(column+1)/3-left,height*(row+1)/2-top))
}

@Composable private fun animationsEnabled():Boolean {
 val context=LocalContext.current
 var enabled by remember{mutableStateOf(ValueAnimator.areAnimatorsEnabled())}
 DisposableEffect(context) {
  val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
   override fun onChange(selfChange:Boolean){enabled=ValueAnimator.areAnimatorsEnabled()}
  }
  context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
  enabled=ValueAnimator.areAnimatorsEnabled()
  onDispose{context.contentResolver.unregisterContentObserver(observer)}
 }
 return enabled
}

@Composable internal fun Weasel(pose:MascotPose=MascotPose.IDLE) {
 val atlas=ImageBitmap.imageResource(R.drawable.weasel_poses)
 val cell=remember(atlas,pose){weaselAtlasCell(atlas.width,atlas.height,pose)}
 val painter=remember(atlas,cell){BitmapPainter(atlas,cell.offset,cell.size,FilterQuality.High)}
 val motion=animationsEnabled()
 val reaction=remember{Animatable(0f)}
 LaunchedEffect(pose,motion) {
  reaction.snapTo(0f)
  if(motion && pose in setOf(MascotPose.PLEASED,MascotPose.JUDGY)) {
   reaction.animateTo(1f,tween(220,easing=FastOutSlowInEasing))
   reaction.animateTo(0f,tween(380,easing=FastOutSlowInEasing))
  }
 }
 val breath=if(motion && pose!=MascotPose.JUDGY && pose!=MascotPose.PLEASED) {
  val transition=rememberInfiniteTransition(label="Weasel breathing")
  val phase by transition.animateFloat(0f,1f,infiniteRepeatable(tween(if(pose==MascotPose.TRAINING)1800 else 2800,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="Breath")
  phase
 } else 0f
 val description=when(pose) {
  MascotPose.IDLE->"Blue-belt weasel"
  MascotPose.PLEASED->"Blue-belt weasel giving a small nod"
  MascotPose.JUDGY->"Blue-belt weasel with folded paws and a sideways glance"
  MascotPose.TRAINING->"Blue-belt weasel ready to train"
  MascotPose.RECOVERY->"Blue-belt weasel stretching"
  MascotPose.REST->"Blue-belt weasel resting"
 }
 Image(painter,description,Modifier.size(96.dp,112.dp).graphicsLayer {
  scaleY=1f+breath*.012f
  translationY=-breath*1.dp.toPx()+reaction.value*1.5.dp.toPx()
  rotationZ=when(pose){MascotPose.TRAINING->(breath-.5f)*1.2f;MascotPose.JUDGY->reaction.value*-1.5f;else->0f}
 })
}

package org.saathi.android

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/** One finite reveal. Service work starts concurrently; no startup/network wait or repeating loop. */
@Composable fun SwarmStartup(initiallyVisible:Boolean,content: @Composable () -> Unit){
    val context=LocalContext.current
    val reduced=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f ||
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
    var showing by rememberSaveable {mutableStateOf(initiallyVisible && !reduced)}
    val reveal=remember{Animatable(0f)}
    LaunchedEffect(showing,reduced){if(reduced)showing=false else if(showing){reveal.animateTo(1f,tween(650,easing=FastOutSlowInEasing));showing=false}}
    BackHandler(showing){showing=false}
    if(!showing)content() else Box(Modifier.fillMaxSize().background(Color(0xff003824)).clickable{showing=false}.clearAndSetSemantics{contentDescription="SWARM by CJP. Tap to open."},contentAlignment=Alignment.Center){
        Column(Modifier.widthIn(max=420.dp).fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp)){
            Image(painterResource(R.drawable.swarm_lockup),null,Modifier.fillMaxWidth().aspectRatio(1.5f).graphicsLayer{alpha=reveal.value;scaleX=.94f+.06f*reveal.value;scaleY=scaleX;translationY=(1f-reveal.value)*8.dp.toPx()})
            Text("Connect nearby. Coordinate together.",color=Color(0xfffff2d5),modifier=Modifier.graphicsLayer{alpha=((reveal.value-.3f)/.7f).coerceIn(0f,1f)})
        }
    }
}

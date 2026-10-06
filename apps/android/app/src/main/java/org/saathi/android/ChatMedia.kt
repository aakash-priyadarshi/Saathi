package org.saathi.android

import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.webrtc.SurfaceViewRenderer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory

@Composable fun CallPanel(vm:SaathiViewModel,state:AppState){
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(if(state.callActive)"Nearby call connected" else "Calling nearby…",style=MaterialTheme.typography.titleMedium)
        Text(state.quality.ifBlank{"Stay within range. Leaving the app ends this call."},style=MaterialTheme.typography.bodySmall)
        val track=state.remoteVideo
        if(state.video&&state.callActive&&track!=null){
            var renderer by remember{mutableStateOf<SurfaceViewRenderer?>(null)}
            AndroidView(factory={context->SurfaceViewRenderer(context).apply{init(vm.wifi.egl.eglBaseContext,null);setEnableHardwareScaler(true);track.addSink(this);renderer=this}},modifier=Modifier.fillMaxWidth().height(180.dp))
            DisposableEffect(track){onDispose{renderer?.let{track.removeSink(it);it.release()};renderer=null}}
        }
        Button(onClick={vm.hangup()},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("End call")}
    }
}
private class VerifiedAudio(private val bytes:ByteArray):MediaDataSource(){
    override fun getSize()=bytes.size.toLong()
    override fun readAt(position:Long,buffer:ByteArray,offset:Int,size:Int):Int {
        if(position<0||position>=bytes.size)return -1
        val count=minOf(size,bytes.size-position.toInt());bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+count);return count
    }
    override fun close(){bytes.fill(0)}
}
@Composable fun VoicePlayback(vm:SaathiViewModel,messageId:String){
    var player by remember{mutableStateOf<MediaPlayer?>(null)};var loading by remember{mutableStateOf(false)};var playback by remember{mutableStateOf<Job?>(null)};val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    fun stop(){playback?.cancel();runCatching{player?.stop()};player?.release();player=null;loading=false}
    DisposableEffect(lifecycle,messageId){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)stop()};lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer);stop()}}
    TextButton(onClick={if(player!=null)stop() else {loading=true;playback=scope.launch {var created:MediaPlayer?=null;try{val bytes=vm.chat.attachmentBytes(messageId);created=MediaPlayer();created.setDataSource(VerifiedAudio(bytes));created.setOnCompletionListener{stop()};withContext(Dispatchers.IO){created.prepare()};if(!lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))error("Playback paused");player=created;created.start()}catch(e:Exception){created?.release();if(e !is kotlinx.coroutines.CancellationException)vm.notice("This voice note could not be played. Re-receive or export the verified attachment.")}finally{loading=false}}}},enabled=!loading){Text(if(loading)"Checking voice note…" else if(player!=null)"Stop voice note" else "Play voice note")}
}
@Composable fun PrivatePhoto(vm:SaathiViewModel,messageId:String){
    val preview by produceState<android.graphics.Bitmap?>(null,messageId){value=withContext(Dispatchers.IO){runCatching{
        val bytes=vm.chat.attachmentBytes(messageId);val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..8192&&bounds.outHeight in 1..8192)
        val options=BitmapFactory.Options().apply{inSampleSize=1;while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>640)inSampleSize*=2}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options).also{bytes.fill(0)}
    }.getOrNull()}}
    preview?.let{Image(it.asImageBitmap(),"Private chat photo",Modifier.fillMaxWidth().heightIn(max=260.dp))}
}

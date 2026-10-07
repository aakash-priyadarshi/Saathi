package org.saathi.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/** Camera frames stay local. Bounded luma-only decoding uses the existing ZXing dependency. */
@Composable fun InviteScanner(scanned:(String)->Unit,close:()->Unit){
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    var allowed by remember{mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var error by remember{mutableStateOf<String?>(null)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed=it;if(!it)error="Camera access was declined. You can paste an invitation instead."}
    val onScanned by rememberUpdatedState(scanned)
    val engine=remember{InviteCamera(context,{onScanned(it)},{error=it})}
    LaunchedEffect(Unit){if(!allowed)permission.launch(Manifest.permission.CAMERA)}
    DisposableEffect(lifecycle,allowed){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME && allowed)engine.resume() else if(event==Lifecycle.Event.ON_PAUSE)engine.pause()}
        lifecycle.addObserver(observer);if(allowed)engine.resume()
        onDispose{lifecycle.removeObserver(observer);engine.pause()}
    }
    DisposableEffect(Unit){onDispose{engine.dispose()}}
    AlertDialog(onDismissRequest=close,title={Text("Scan invitation")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Point at a SWARM invitation QR. You will review it before joining or requesting approval.")
        if(allowed && error==null)AndroidView(factory={engine.view},modifier=Modifier.fillMaxWidth().aspectRatio(1f))
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(!allowed)TextButton(onClick={permission.launch(Manifest.permission.CAMERA)}){Text("Allow camera")}
    }},confirmButton={TextButton(onClick=close){Text("Paste invitation instead")}})
}

private class InviteCamera(context:Context,private val scanned:(String)->Unit,private val failed:(String)->Unit){
    private val cameras=context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread=HandlerThread("SwarmInviteScanner").apply{start()};private val handler=Handler(thread.looper)
    val view=TextureView(context)
    @Volatile private var foreground=false
    @Volatile private var opened=false
    @Volatile private var recognized=false
    private var camera:CameraDevice?=null;private var session:CameraCaptureSession?=null
    private var reader:ImageReader?=null;private var preview:Surface?=null;private var lastFrame=0L
    init {view.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(texture:SurfaceTexture,width:Int,height:Int){open()}
        override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,width:Int,height:Int){}
        override fun onSurfaceTextureDestroyed(texture:SurfaceTexture):Boolean{pause();return true}
        override fun onSurfaceTextureUpdated(texture:SurfaceTexture){}
    }}
    fun resume(){foreground=true;if(view.isAvailable)open()}
    fun pause(){foreground=false;session?.close();session=null;camera?.close();camera=null;reader?.close();reader=null;preview?.release();preview=null;opened=false}
    fun dispose(){pause();thread.quitSafely()}
    private fun fail(message:String){view.post{failed(message)};pause()}
    @SuppressLint("MissingPermission") private fun open(){
        if(!foreground || opened || recognized)return
        opened=true
        try{
            val id=cameras.cameraIdList.firstOrNull{cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK}?:error("No rear camera. Paste an invitation instead.")
            val map=cameras.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?:error("Camera preview is unavailable.")
            val size=map.getOutputSizes(ImageFormat.YUV_420_888).filter{it.width<=1280 && it.height<=960}.maxByOrNull{it.width*it.height}?:error("A supported QR preview is unavailable.")
            val texture=view.surfaceTexture?:return;texture.setDefaultBufferSize(size.width,size.height)
            preview=Surface(texture)
            reader=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,2).apply{setOnImageAvailableListener({source->
                val image=runCatching{source.acquireLatestImage()}.getOrNull()?:return@setOnImageAvailableListener
                image.use{frame->
                    val now=SystemClock.elapsedRealtime();if(!foreground || recognized || now-lastFrame<300)return@use;lastFrame=now
                    runCatching{
                        val plane=frame.planes[0];val buffer=plane.buffer;val luminance=ByteArray(frame.width*frame.height)
                        for(y in 0 until frame.height)for(x in 0 until frame.width)luminance[y*frame.width+x]=buffer.get(y*plane.rowStride+x*plane.pixelStride)
                        val bitmap=BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luminance,frame.width,frame.height,0,0,frame.width,frame.height,false)))
                        val result=MultiFormatReader().decode(bitmap,mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text
                        if(result.length in 1..44000 && InviteLink.token(result)!=null){recognized=true;view.post{scanned(result)}}
                    }
                }
            },handler)}
            cameras.openCamera(id,object:CameraDevice.StateCallback(){
                override fun onOpened(device:CameraDevice){
                    if(!foreground){device.close();opened=false;return};camera=device
                    val outputs=listOfNotNull(preview,reader?.surface)
                    device.createCaptureSession(outputs,object:CameraCaptureSession.StateCallback(){
                        override fun onConfigured(capture:CameraCaptureSession){if(!foreground){capture.close();return};session=capture
                            runCatching{val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{outputs.forEach{addTarget(it)};set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)}.build();capture.setRepeatingRequest(request,null,handler)}.onFailure{fail("Camera preview stopped. Paste the invitation or try again.")}}
                        override fun onConfigureFailed(capture:CameraCaptureSession){fail("Camera preview could not start. Paste an invitation instead.")}
                    },handler)
                }
                override fun onDisconnected(device:CameraDevice){device.close();fail("Camera disconnected. Paste an invitation instead.")}
                override fun onError(device:CameraDevice,code:Int){device.close();fail("Camera is unavailable. Close another camera app or paste an invitation.")}
            },handler)
        }catch(_:Exception){fail("Camera could not start. You can paste an invitation instead.")}
    }
}

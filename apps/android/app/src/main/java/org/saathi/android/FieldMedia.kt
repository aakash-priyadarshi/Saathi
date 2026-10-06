package org.saathi.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaCodec
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

data class FieldDerivative(val bytes:ByteArray,val mime:String,val width:Int,val height:Int,val durationSeconds:Int,val thumbnail:ByteArray)
/** Conventional decoding/re-encoding only. Selected originals are never modified. */
object FieldMedia {
    suspend fun prepare(context:Context,uri:Uri,video:Boolean)=withContext(Dispatchers.IO){if(video)video(context,uri) else photo(context,uri)}
    private fun jpeg(bitmap:Bitmap,quality:Int)=ByteArrayOutputStream().use{out->require(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out));out.toByteArray()}
    private fun thumb(bitmap:Bitmap):ByteArray {val scale=minOf(1f,600f/maxOf(bitmap.width,bitmap.height));val small=Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*scale).toInt()),maxOf(1,(bitmap.height*scale).toInt()),true);return jpeg(small,78).also{if(small!==bitmap)small.recycle()}}
    private fun photo(context:Context,uri:Uri):FieldDerivative {
        val resolver=context.contentResolver;val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};resolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 && bounds.outWidth.toLong()*bounds.outHeight<=40000000){"Choose a photo smaller than 40 megapixels."}
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>3200)sample*=2
        val bitmap=resolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}?:error("This photo cannot be decoded.")
        val orientation=runCatching{resolver.openInputStream(uri)!!.use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}}.getOrDefault(1)
        val matrix=Matrix().apply{when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->{setRotate(180f);postScale(-1f,1f)};5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)}}
        val oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true);if(oriented!==bitmap)bitmap.recycle()
        val scale=minOf(1f,1600f/maxOf(oriented.width,oriented.height));val safe=Bitmap.createScaledBitmap(oriented,maxOf(1,(oriented.width*scale).toInt()),maxOf(1,(oriented.height*scale).toInt()),true);if(safe!==oriented)oriented.recycle()
        return try{FieldDerivative(jpeg(safe,88),"image/jpeg",safe.width,safe.height,0,thumb(safe))}finally{safe.recycle()}
    }
    private fun video(context:Context,uri:Uri):FieldDerivative {
        val descriptor=context.contentResolver.openAssetFileDescriptor(uri,"r")?:error("Cannot open this video.")
        descriptor.use{require(it.length<0 || it.length<=16777216){"Choose a video smaller than 16 MB."}}
        val metadata=MediaMetadataRetriever();metadata.setDataSource(context,uri)
        val duration=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0
        val width=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0;val height=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0
        val rotation=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
        try{require(duration in 1..60000 && width in 1..1920 && height in 1..1920){"Choose a video up to 60 seconds and 1080p (1920 × 1080)."};require(minOf(width,height)<=1080){"Choose a 1080p or smaller video."}
            val frame=if(android.os.Build.VERSION.SDK_INT>=27)metadata.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,600,600)else metadata.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            requireNotNull(frame){"Cannot create a video preview."};val thumbnail=try{thumb(frame)}finally{frame.recycle()}
            val dir=File(context.cacheDir,"field-processing").apply{mkdirs()};val output=File.createTempFile("safe-",".mp4",dir);val extractor=MediaExtractor();var muxer:MediaMuxer?=null
            try{extractor.setDataSource(context,uri,null);muxer=MediaMuxer(output.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer.setOrientationHint(rotation.takeIf{it in listOf(0,90,180,270)}?:0);val tracks=mutableMapOf<Int,Int>();val ends=mutableMapOf<Int,Long>();val lastTimes=mutableMapOf<Int,Long>()
                for(i in 0 until extractor.trackCount){val format=extractor.getTrackFormat(i);val mime=format.getString(MediaFormat.KEY_MIME)?:continue;if(mime !in listOf("video/avc","video/hevc","audio/mp4a-latm"))continue
                    val clean=if(mime.startsWith("video"))MediaFormat.createVideoFormat(mime,format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT))else MediaFormat.createAudioFormat(mime,format.getInteger(MediaFormat.KEY_SAMPLE_RATE),format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    for(key in listOf("csd-0","csd-1","csd-2"))if(format.containsKey(key))clean.setByteBuffer(key,format.getByteBuffer(key)!!.duplicate())
                    tracks[i]=muxer.addTrack(clean);ends[i]=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION)else duration*1000;extractor.selectTrack(i)
                };require(tracks.keys.any{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video")==true}){"This video format cannot be prepared safely. Choose an MP4."};muxer.start();val buffer=ByteBuffer.allocate(2*1048576);val info=MediaCodec.BufferInfo();var total=0L
                while(true){val track=extractor.sampleTrackIndex;if(track<0)break;val size=extractor.readSampleData(buffer,0);if(size<0)break;total+=size;require(total<=16777216 && extractor.sampleTime<=60000000){"Choose a shorter, smaller video."};require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0){"Protected video cannot be prepared for public sharing."};info.set(0,size,extractor.sampleTime,if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0);muxer.writeSampleData(tracks.getValue(track),buffer,info);lastTimes[track]=maxOf(lastTimes[track]?:0,extractor.sampleTime);extractor.advance()}
                // Preserve the final sample's duration with the platform's documented empty EOS sample.
                for((track,last) in lastTimes){val end=ends.getValue(track);require(end in last..60000000);info.set(0,0,end,MediaCodec.BUFFER_FLAG_END_OF_STREAM);muxer.writeSampleData(tracks.getValue(track),ByteBuffer.allocate(0),info)};muxer.stop();muxer.release();muxer=null
                require(output.length() in 29..16777216);return FieldDerivative(output.readBytes(),"video/mp4",width,height,((duration+999)/1000).toInt(),thumbnail)
            }finally{extractor.release();muxer?.release();output.delete()}
        }finally{metadata.release()}
    }
}

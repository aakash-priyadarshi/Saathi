package org.saathi.android

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommunityStoreTest {
    @Test fun durableHelpOfferResolutionDedupAndOriginalAuthor():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val names=List(2){"test-community-${UUID.randomUUID()}"};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val repositories=names.map{Repository(context,it)};val sessions=repositories.map{PeerSession(context,it,scope)};val chats=repositories.mapIndexed{i,r->ChatRepository(context,r,sessions[i])};val community=repositories.mapIndexed{i,r->CommunityRepository(context,r,chats[i],sessions[i])}
        try{
            chats[0].rename("Fictional requester");chats[1].rename("Fictional responder")
            repositories[0].store.put("trust","clock",obj("time" to java.time.Instant.now().plusSeconds(30).toString()))
            val help=obj("category" to "WATER","audience" to "MYSELF","quantity" to 2,"details" to "Two water bottles needed","area" to "Fictional Gate 2","priority" to "NORMAL","status" to "OPEN","responderId" to null)
            val id=community[0].saveHelp(help);val original=community[0].helps().single().getJSONObject("envelope");community[1].receive(original,1);community[1].receive(original,1);assertEquals(1,community[1].helps().size)
            assertTrue(runCatching{community[0].saveHelp(help)}.isFailure);assertTrue(runCatching{community[0].saveHelp(JSONObject(help.toString()).put("category","FOOD"))}.isFailure)
            community[1].offer(id);val offer=repositories[1].store.all("community").first{it.getJSONObject("envelope").getJSONObject("body").getString("type")=="HELP_OFFER"}.getJSONObject("envelope");community[0].receive(offer,1)
            community[0].status(id,"RESPONDER_ASSIGNED",ChatProtocol.participant(chats[1].profile()));val assigned=community[0].helps().single().getJSONObject("envelope");community[1].receive(assigned,1);assertEquals("RESPONDER_ASSIGNED",community[1].helps().single().getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("status"))
            assertTrue(runCatching{community[1].status(id,"RESOLVED")}.isFailure);community[0].status(id,"RESOLVED");community[1].receive(community[0].helps().single().getJSONObject("envelope"),1);assertTrue(runCatching{community[0].status(id,"OPEN")}.isFailure)
            val report=community[0].report("Fictional access route blocked","Fictional Gate 2",false);community[1].receive(repositories[0].store.get("community",report)!!.getJSONObject("envelope"),1);assertTrue(community[1].reports().single().getJSONObject("envelope").toString().contains("Fictional requester"));assertFalse(community[1].reports().single().optBoolean("owned"));assertTrue(runCatching{community[1].withdraw(report)}.isFailure)
            val identity=ChatProtocol.participant(chats[0].profile());chats[0].rename("Fictional renamed requester");assertEquals(identity,ChatProtocol.participant(chats[0].profile()));community[0].withdraw(report);val withdrawal=repositories[0].store.all("community").first{it.getJSONObject("envelope").getJSONObject("body").getString("type")=="WITHDRAW"}.getJSONObject("envelope");community[1].receive(withdrawal,1);assertTrue(community[1].reports().isEmpty())
            val reopen=Repository(context,names[1]);try{assertEquals(6,reopen.store.all("community").size)}finally{reopen.store.close()}
        }finally{scope.cancel();repositories.forEachIndexed{i,r->r.store.clearPrivate();r.store.close();context.deleteDatabase("saathi-${names[i]}.db")}}
    }
    @Test fun nativePhotoNormalizesOrientationAndStripsMetadata():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val photo=File.createTempFile("synthetic-exif-",".jpg",context.cacheDir)
        try{val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(0,70,42));photo.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle();val exif=ExifInterface(photo.path);exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,"19/1,7/1,1/1");exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"73/1,32/1,1/1");exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"E");exif.setAttribute(ExifInterface.TAG_MAKE,"PRIVATE DEVICE");exif.setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());exif.saveAttributes();assertTrue("Synthetic source GPS present: "+ExifInterface(photo.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE)+" / "+ExifInterface(photo.path).getAttribute(ExifInterface.TAG_GPS_LONGITUDE),ExifInterface(photo.path).getLatLong(FloatArray(2)));val original=photo.readBytes();val safe=FieldMedia.prepare(context,Uri.fromFile(photo),false)
            assertEquals(80,safe.width);assertEquals(120,safe.height);assertTrue(safe.thumbnail.isNotEmpty());assertArrayEquals(original,photo.readBytes());val clean=ExifInterface(ByteArrayInputStream(safe.file.readBytes()));assertFalse(clean.getLatLong(FloatArray(2)));assertNull(clean.getAttribute(ExifInterface.TAG_MAKE));assertTrue("Prepared photo has no non-normal orientation",clean.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) in listOf(ExifInterface.ORIENTATION_UNDEFINED,ExifInterface.ORIENTATION_NORMAL));assertEquals(64,Protocol.digest(safe.file.readBytes()).length)
        }finally{photo.delete()}
    }
    @Test fun rejectsContactsCoordinatesAndUnsignedAlteration():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="test-community-validation-${UUID.randomUUID()}";val r=Repository(context,name);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val session=PeerSession(context,r,scope);val chat=ChatRepository(context,r,session);val community=CommunityRepository(context,r,chat,session)
        try{chat.rename("Fictional participant");for(area in listOf("a@example.org","+91 9876543210","19.12345, 73.54321"))assertTrue(runCatching{community.report("Fictional report",area,false)}.isFailure)
            val id=community.report("Fictional report","Fictional Gate 2",false);val event=JSONObject(r.store.get("community",id)!!.getJSONObject("envelope").toString());event.getJSONObject("body").getJSONObject("payload").put("caption","Altered report");assertFalse(CommunityProtocol.valid(event,r.clock()))
        }finally{scope.cancel();r.store.clearPrivate();r.store.close();context.deleteDatabase("saathi-$name.db")}
    }
    @Test fun nativeVideoRemuxStripsLocationAndPreservesOriginal():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val source=File.createTempFile("synthetic-video-",".mp4",context.cacheDir);val output=File.createTempFile("synthetic-video-safe-",".mp4",context.cacheDir)
        try {
            instrumentation.context.assets.open("public-report-qa.mp4").use{input->source.outputStream().use{input.copyTo(it)}}
            val original=source.readBytes();val before=MediaMetadataRetriever()
            try{before.setDataSource(source.path);assertTrue("Synthetic input contains a location box",original.toString(Charsets.ISO_8859_1).contains("loci"));assertTrue("Synthetic input contains private comment",original.toString(Charsets.ISO_8859_1).contains("SYNTHETIC PRIVATE"))}finally{before.release()}
            val safe=FieldMedia.prepare(context,Uri.fromFile(source),true);assertEquals("video/mp4",safe.mime);assertEquals(320,safe.width);assertEquals(240,safe.height);assertEquals(2,safe.durationSeconds);assertTrue(safe.thumbnail.isNotEmpty());assertTrue(safe.size<=FieldMedia.MAX_BYTES);assertArrayEquals(original,source.readBytes());output.writeBytes(safe.file.readBytes())
            val after=MediaMetadataRetriever();try{after.setDataSource(output.path);assertNull(after.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION));assertFalse("Output strips location box",safe.file.readBytes().toString(Charsets.ISO_8859_1).contains("loci"));assertFalse("Output strips private comment",safe.file.readBytes().toString(Charsets.ISO_8859_1).contains("SYNTHETIC PRIVATE"));assertEquals("2000",after.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));assertNotNull(after.getFrameAtTime(0))}finally{after.release()}
        }finally{source.delete();output.delete()}
    }
    @Test fun largeVideoIsCompressedTo720pWithoutLocation():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val source=File.createTempFile("synthetic-1080p-",".mp4",context.cacheDir)
        try {
            instrumentation.context.assets.open("field-1080p-qa.mp4").use{input->source.outputStream().use{input.copyTo(it)}}
            val safe=FieldMedia.prepare(context,Uri.fromFile(source),true)
            try {
                assertEquals("video/mp4",safe.mime);assertEquals(1280,safe.width);assertEquals(720,safe.height)
                assertTrue("compressed ${safe.size} < original ${source.length()}",safe.size<source.length())
                val after=MediaMetadataRetriever();try{after.setDataSource(safe.file.path);assertNull(after.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION));assertNotNull(after.getFrameAtTime(0))}finally{after.release()}
            }finally{safe.file.delete()}
        }finally{source.delete()}
    }
}

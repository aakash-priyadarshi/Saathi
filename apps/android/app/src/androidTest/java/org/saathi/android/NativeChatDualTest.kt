package org.saathi.android

import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated fictional identities. USB coordinates barriers/IDs, never chat content or file bytes. */
@RunWith(AndroidJUnit4::class)
class NativeChatDualTest {
    @Test fun physicalChatContinuityMembershipAndEncryptedResume(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.CHAT_ENABLED && BuildConfig.ENVIRONMENT=="development" && args.getString("dualFixture")=="true")
        val role=args.getString("role")!!; val author=role=="author"
        val mode=args.getString("transport")!!
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope="test-chat-${UUID.randomUUID()}"; val repository=Repository(context,storageScope)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=PeerSession(context,repository,scope)
        var chat=ChatRepository(context,repository,session)
        val nearby=withContext(Dispatchers.Main){NearbyTransport(context,scope)}
        val wifi=if(mode=="wifi")withContext(Dispatchers.Main){LocalWifiTransport(context,scope)}else null
        val transport:PeerTransport=wifi?:nearby; session.transport=transport
        val client=OkHttpClient.Builder().readTimeout(310,TimeUnit.SECONDS).callTimeout(310,TimeUnit.SECONDS).build()
        suspend fun meet(step:String,value:JSONObject=obj()):JSONObject=withContext(Dispatchers.IO){
            client.newCall(Request.Builder().url("http://127.0.0.1:4010/$step").header("Authorization","Bearer ${args.getString("bridgeToken")}").post(obj("role" to role,"value" to value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use{ check(it.isSuccessful){"Fixture failed: $step"};JSONObject(it.body!!.string()) }
        }
        suspend fun waitFor(condition:()->Boolean)=withContext(Dispatchers.Main){withTimeout(90000){while(!condition())delay(100)}}
        fun internet()=context.getSystemService(ConnectivityManager::class.java).let{it.getNetworkCapabilities(it.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true}
        var round=0;var connecting=false;var code="";var invitation=""
        val errors=java.util.concurrent.CopyOnWriteArrayList<String>();session.onError={errors.add(it)}
        fun hook(){
            session.onChatConnected={chat.announce()};session.onChatFrame={frame,generation->chat.receive(frame,generation)};session.onChatReset={chat.reset()}
            session.chatFileAllowed={id,hash->chat.fileAllowed(id,hash)};session.onChatFileComplete={chat.completeFile(it)};chat.onInvite={invitation=it}
        }
        hook()
        session.onFile={file->if(session.chatFileAllowed(file.getString("id"),file.getString("hash")))scope.launch{session.acceptFile(file)}}
        nearby.onFrame={session.incoming(it)};wifi?.onFrame={session.incoming(it)};wifi?.onCode={code=it}
        nearby.onPair={digits->if(digits!=null)scope.launch{val other=meet("code-$round",obj("code" to digits));nearby.confirm(other.getString("code")==digits)}}
        nearby.onPeers={peers->if(author&&!connecting&&peers.isNotEmpty()){connecting=true;scope.launch{nearby.connect(peers.keys.first())}}}
        suspend fun pair(){
            round++;connecting=false;code="";meet("pair-ready-$round")
            withContext(Dispatchers.Main){session.reset();if(wifi==null)nearby.scan(!author)else{
                val offer=if(author)wifi.offer()else "";val other=meet("offer-$round",obj("description" to offer))
                val reply=if(!author)wifi.accept(other.getString("description"))else "";val answer=meet("reply-$round",obj("description" to reply));if(author)wifi.accept(answer.getString("description"))
            }}
            waitFor{transport.connected && (wifi==null || code.isNotBlank())}
            if(wifi!=null){val other=meet("code-$round",obj("code" to code));assertEquals(code,other.getString("code"))}
            withContext(Dispatchers.Main){session.confirm()};waitFor{chat.peer!=null && session.maximumFileBytes>1048576}
            meet("connected-$round",obj("internetValidated" to internet()))
        }
        val activity=ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val input=File(context.cacheDir,"chat-test-${UUID.randomUUID()}.txt")
        val photo=File(context.cacheDir,"chat-photo-${UUID.randomUUID()}.png")
        val voice=File(context.cacheDir,"chat-voice-${UUID.randomUUID()}.m4a")
        val fileIds=mutableSetOf<String>()
        try{
            repository.refreshConfiguration();chat.rename(if(author)"Test Arjun" else "Test Priya");meet("prepared",obj("internetValidated" to internet()));pair()
            val dm=chat.direct(chat.peer!!);val other=meet("logical-dm",obj("id" to dm));assertEquals(dm,other.getString("id"))
            val sent=chat.send(dm,obj("text" to "Fictional private $role message"))
            waitFor{chat.messages().any{!it.optBoolean("owned")} && repository.store.get("chat-messages",sent)?.has("deliveredAt")==true}
            assertFalse(repository.store.get("chat-messages",sent)!!.getJSONObject("envelope").getJSONObject("body").getString("content").contains("Fictional"))
            chat.read(dm,chat.messages().filter{!it.optBoolean("owned")&&it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==dm}.map{it.getString("id")});waitFor{repository.store.get("chat-messages",sent)?.has("readAt")==true};meet("private-dm-delivered-read")
            chat.sync();meet("dm-server-saved");chat.sync();assertEquals(2,chat.messages().size);meet("online-radio-deduplicated")
            val identity=Protocol.hash(chat.profile());chat=ChatRepository(context,repository,session);hook();assertEquals(identity,Protocol.hash(chat.profile()));assertEquals(dm,chat.direct(chat.peer?:chat.contacts().first{it.getString("id")!=ChatProtocol.participant(chat.profile())}.getJSONObject("profile")));assertEquals(2,chat.messages().size);meet("repository-reopened-identity-and-history")
            // Re-announce the reopened repository before joining channels.
            chat.announce();waitFor{chat.peer!=null}
            val open=if(author)chat.create("Physical medical help","OPEN")else ""
            val openMeta=meet("open-created",obj("id" to open));val openId=if(author)open else openMeta.getString("id")
            if(!author){waitFor{chat.discovery.any{it.getString("id")==openId}};chat.join(openId)}
            waitFor{chat.conversations().any{it.getString("id")==openId&&it.optBoolean("joined")} && chat.policies().first{it.getJSONObject("body").getString("id")==openId}.getJSONObject("body").getJSONArray("members").length()==2}
            meet("open-owner-signed-join");val channelMessage=chat.send(openId,obj("text" to "Fictional $role channel coordination"))
            waitFor{chat.messages().count{it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==openId}==2};meet("open-channel-messages")
            var privateId=""
            if(author){privateId=chat.create("Physical private coordination","INVITE");val link=chat.invite(privateId,chat.peer!!);session.send("CHAT_INVITE",link)}
            if(!author){waitFor{invitation.isNotBlank()};privateId=chat.acceptInvite(invitation);assertEquals(privateId,chat.acceptInvite(invitation))}
            val privateOther=meet("recipient-invite-replay-idempotent",obj("id" to privateId));assertEquals(privateId,privateOther.getString("id"))
            chat.send(privateId,obj("text" to "Private encrypted $role coordination"));waitFor{chat.messages().count{it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==privateId}==2};meet("private-channel-decrypted")
            if(author){
                val bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.rgb(35,87,67));photo.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                InstrumentationRegistry.getInstrumentation().context.assets.open("voice-qa.m4a").use{source->voice.outputStream().use{source.copyTo(it)}}
                chat.attach(dm,Uri.fromFile(photo),"image/png","Fictional photo.png");chat.attach(dm,Uri.fromFile(voice),"audio/mp4","Synthetic voice-note tone.m4a")
            }
            waitFor{chat.messages().count{it.getJSONObject("envelope").getJSONObject("body").getString("format") in listOf("PHOTO","VOICE")}==2}
            if(!author)waitFor{chat.messages().filter{it.getJSONObject("envelope").getJSONObject("body").getString("format") in listOf("PHOTO","VOICE")}.all{repository.store.get("attachments",it.getJSONObject("payload").getJSONObject("attachment").getString("id"))?.optBoolean("complete")==true}}
            for(record in chat.messages().filter{it.getJSONObject("envelope").getJSONObject("body").getString("format") in listOf("PHOTO","VOICE")})assertEquals(record.getJSONObject("payload").getJSONObject("attachment").getString("hash"),Protocol.digest(chat.attachmentBytes(record.getString("id"))))
            meet("photo-and-aac-voice-note-verified")
            if(author){input.writeBytes(ByteArray(4*1048576){(it%251).toByte()});chat.attach(dm,Uri.fromFile(input),"text/plain","Fictional encrypted resume.txt")}
            waitFor{chat.messages().any{it.getJSONObject("envelope").getJSONObject("body").getString("format")=="FILE"}}
            val attachmentMessage=chat.messages().first{it.getJSONObject("envelope").getJSONObject("body").getString("format")=="FILE"};val attachment=attachmentMessage.getJSONObject("payload").getJSONObject("attachment");fileIds.add(attachment.getString("id"))
            if(!author)waitFor{repository.store.get("attachments",attachment.getString("id"))?.optJSONArray("received")?.let{arr->(0 until arr.length()).count{arr.optBoolean(it)}>=16}==true}
            meet("encrypted-file-partial-persisted")
            if(!author)withContext(Dispatchers.Main){transport.disconnect();session.reset()};waitFor{!transport.connected};meet("file-radio-interrupted");delay(11000);pair()
            if(author)chat.offerAttachment(attachmentMessage.getString("id"))
            waitFor{repository.store.get("attachments",attachment.getString("id"))?.optBoolean("complete")==true && (author || repository.store.get("attachments",attachment.getString("id"))?.optBoolean("chatOnly")==true)}
            val bytes=chat.attachmentBytes(attachmentMessage.getString("id"));assertEquals(4*1048576,bytes.size);assertEquals(attachment.getString("hash"),Protocol.digest(bytes));assertFalse(session.readSavedBytes(attachment.getString("id")).contentEquals(bytes));meet("encrypted-resume-plaintext-hash-verified")
            chat.sync();meet("channel-and-media-metadata-synced");chat.sync();meet("second-sync-deduplicated")
            assertEquals(9,chat.messages().size)
            if(author)chat.synchronizeAttachment(attachmentMessage.getString("id"),true)
            meet("encrypted-server-upload-complete")
            if(!author){session.removeFile(attachment.getString("id"));chat.synchronizeAttachment(attachmentMessage.getString("id"),true);assertEquals(attachment.getString("hash"),Protocol.digest(chat.attachmentBytes(attachmentMessage.getString("id"))))}
            meet("encrypted-server-download-plaintext-verified")
            if(author)chat.membership(privateId,ChatProtocol.participant(chat.peer!!))
            if(!author)waitFor{chat.conversations().first{it.getString("id")==privateId}.optBoolean("joined")==false}
            meet("private-member-removal-applied")
            if(!author)assertTrue(runCatching{chat.send(privateId,obj("text" to "Must be rejected"))}.isFailure)
            val before=chat.messages().size
            if(!author)chat.block(ChatProtocol.participant(chat.peer!!));meet("blocked-recipient")
            if(author)chat.send(dm,obj("text" to "Must not appear on blocked recipient"))
            delay(1500);if(!author)assertEquals(before,chat.messages().size);meet("block-prevents-new-delivery")
            assertTrue(errors.joinToString("; "),errors.filterNot{it.contains("paused",true)||it.contains("Connection changed",true)}.isEmpty())
            meet("complete",obj("messages" to 9,"resumedBytes" to 4*1048576))
        }finally{
            chat.messages().forEach{it.getJSONObject("payload").optJSONObject("attachment")?.let{a->fileIds.add(a.getString("id"))}}
            withContext(Dispatchers.Main){transport.disconnect();session.reset();fileIds.forEach{session.removeFile(it)};wifi?.release()};scope.cancel();input.delete();photo.delete();voice.delete();repository.store.clearPrivate();repository.store.close();context.deleteDatabase("saathi-$storageScope.db");activity.close()
        }
    }
}

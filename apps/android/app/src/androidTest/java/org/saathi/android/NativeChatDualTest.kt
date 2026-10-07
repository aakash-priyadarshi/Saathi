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
        val milestoneOnly=args.getString("milestoneOnly")=="true"
        val radioOnly=args.getString("radioOnly")=="true"
        val mode=args.getString("transport")!!
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope="test-chat-${UUID.randomUUID()}"; val repository=Repository(context,storageScope)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=PeerSession(context,repository,scope)
        lateinit var community:CommunityRepository
        var chat=ChatRepository(context,repository,session)
        val nearby=withContext(Dispatchers.Main){NearbyTransport(context,scope)}
        val wifi=if(mode=="wifi")withContext(Dispatchers.Main){LocalWifiTransport(context,scope)}else null
        val transport:PeerTransport=wifi?:nearby; session.transport=transport
        val client=OkHttpClient.Builder().readTimeout(310,TimeUnit.SECONDS).callTimeout(310,TimeUnit.SECONDS).build()
        suspend fun meet(step:String,value:JSONObject=obj()):JSONObject=withContext(Dispatchers.IO){
            client.newCall(Request.Builder().url("http://127.0.0.1:4010/$step").header("Authorization","Bearer ${args.getString("bridgeToken")}").post(obj("role" to role,"value" to value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use{ check(it.isSuccessful){"Fixture failed: $step"};JSONObject(it.body!!.string()) }
        }
        fun internet()=context.getSystemService(ConnectivityManager::class.java).let{it.getNetworkCapabilities(it.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true}
        var round=0;var connecting=false;var code="";var invitation=""
        val errors=java.util.concurrent.CopyOnWriteArrayList<String>();session.onError={errors.add(it)}
        nearby.onError={errors.add(it)}
        suspend fun waitFor(condition:()->Boolean)=withContext(Dispatchers.Main){try{withTimeout(90000){while(!condition())delay(100)}}catch(e:TimeoutCancellationException){error("Physical condition timed out; protocol errors: ${errors.distinct().take(8)}")}}
        fun hook(){
            community=CommunityRepository(context,repository,chat,session)
            session.onChatConnected={chat.announce();community.announce()};session.onChatFrame={frame,generation->chat.receive(frame,generation)};session.onChatReset={chat.reset()}
            session.onCommunityFrame={frame,generation->community.frame(frame,generation)};session.publicFileAllowed={id,hash->community.fileAllowed(id,hash)};session.onPublicFileComplete={community.completeFile(it)}
            session.chatFileAllowed={id,hash->chat.fileAllowed(id,hash)};session.onChatFileComplete={chat.completeFile(it)};chat.onInvite={invitation=it}
        }
        hook()
        session.onFile={file->if(session.chatFileAllowed(file.getString("id"),file.getString("hash")) || session.publicFileAllowed(file.getString("id"),file.getString("hash")))scope.launch{session.acceptFile(file)}}
        nearby.onFrame={session.incoming(it)};wifi?.onFrame={session.incoming(it)};wifi?.onCode={code=it}
        nearby.onPair={digits->if(digits!=null)scope.launch{val other=meet("code-$round",obj("code" to digits));nearby.confirm(other.getString("code")==digits)}}
        nearby.onPeers={peers->if(author&&!connecting&&peers.isNotEmpty()){connecting=true;scope.launch{nearby.connect(peers.keys.first())}}}
        suspend fun pair(){
            round++;connecting=false;code="";meet("pair-ready-$round")
            withContext(Dispatchers.Main){session.reset();if(wifi==null)nearby.scan(!author,displayName=chat.profile().getJSONObject("body").getString("name"))else{
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
        val video=File(context.cacheDir,"chat-video-${UUID.randomUUID()}.mp4")
        val fileIds=mutableSetOf<String>()
        try{
            if(radioOnly){
                assertFalse("No validated internet for the no-hotspot proof",internet())
                assertTrue(context.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled)
                assertFalse("Disconnect infrastructure Wi-Fi before direct Nearby pairing",LocalNetworkAdvice.hasWifiAddress())
                Repository::class.java.getDeclaredField("configuration").apply{isAccessible=true}.set(repository,obj("body" to obj("features" to obj("largeFiles" to true,"localCalls" to true))))
            }else repository.refreshConfiguration()
            chat.rename(if(author)"Test Arjun" else "Test Priya");meet("prepared",obj("internetValidated" to internet()));pair()
            val dm=chat.direct(chat.peer!!);val other=meet("logical-dm",obj("id" to dm));assertEquals(dm,other.getString("id"))
            val sent=chat.send(dm,obj("text" to "Fictional private $role message"))
            waitFor{chat.messages().any{!it.optBoolean("owned")} && repository.store.get("chat-messages",sent)?.has("deliveredAt")==true}
            assertFalse(repository.store.get("chat-messages",sent)!!.getJSONObject("envelope").getJSONObject("body").getString("content").contains("Fictional"))
            chat.read(dm,chat.messages().filter{!it.optBoolean("owned")&&it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==dm}.map{it.getString("id")});waitFor{repository.store.get("chat-messages",sent)?.has("readAt")==true};meet("private-dm-delivered-read")
            if(!radioOnly){chat.sync();meet("dm-server-saved");chat.sync();assertEquals(2,chat.messages().size);meet("online-radio-deduplicated")}
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
                if(radioOnly)InstrumentationRegistry.getInstrumentation().context.assets.open("public-report-qa.mp4").use{source->video.outputStream().use{source.copyTo(it)}}
                for(destination in if(radioOnly)listOf(dm,privateId)else listOf(dm)){
                    chat.attach(destination,Uri.fromFile(photo),"image/png","Fictional photo.png");chat.attach(destination,Uri.fromFile(voice),"audio/mp4","Synthetic voice-note tone.m4a")
                    if(radioOnly)chat.attach(destination,Uri.fromFile(video),"video/mp4","Synthetic video.mp4")
                }
            }
            val mediaFormats=if(radioOnly)listOf("PHOTO","VOICE","VIDEO")else listOf("PHOTO","VOICE")
            waitFor{chat.messages().count{it.getJSONObject("envelope").getJSONObject("body").getString("format") in mediaFormats}==if(radioOnly)6 else 2}
            if(!author)waitFor{chat.messages().filter{it.getJSONObject("envelope").getJSONObject("body").getString("format") in mediaFormats}.all{repository.store.get("attachments",it.getJSONObject("payload").getJSONObject("attachment").getString("id"))?.optBoolean("complete")==true}}
            for(record in chat.messages().filter{it.getJSONObject("envelope").getJSONObject("body").getString("format") in mediaFormats})assertEquals(record.getJSONObject("payload").getJSONObject("attachment").getString("hash"),Protocol.digest(chat.attachmentBytes(record.getString("id"))))
            meet(if(radioOnly)"dm-and-private-group-photo-audio-video-verified"else "photo-and-aac-voice-note-verified")
            if(!milestoneOnly){
            if(author){input.writeBytes(ByteArray(4*1048576){(it%251).toByte()});chat.attach(dm,Uri.fromFile(input),"text/plain","Fictional encrypted resume.txt")}
            waitFor{chat.messages().any{it.getJSONObject("envelope").getJSONObject("body").getString("format")=="FILE"}}
            val attachmentMessage=chat.messages().first{it.getJSONObject("envelope").getJSONObject("body").getString("format")=="FILE"};val attachment=attachmentMessage.getJSONObject("payload").getJSONObject("attachment");fileIds.add(attachment.getString("id"))
            if(!author)waitFor{repository.store.get("attachments",attachment.getString("id"))?.optJSONArray("received")?.let{arr->(0 until arr.length()).count{arr.optBoolean(it)}>=16}==true}
            meet("encrypted-file-partial-persisted")
            if(!author)withContext(Dispatchers.Main){transport.disconnect();session.reset()};waitFor{!transport.connected}
            val offlineDmText="Fictional DM queued while disconnected";val offlineGroupText="Fictional channel post queued while disconnected"
            val offlineDm=if(author)chat.send(dm,obj("text" to offlineDmText))else ""
            val offlineGroup=if(author)chat.send(openId,obj("text" to offlineGroupText))else ""
            meet("file-radio-interrupted");delay(11000);pair()
            if(author)waitFor{repository.store.get("chat-messages",offlineDm)?.has("deliveredAt")==true && repository.store.get("chat-messages",offlineGroup)?.has("deliveredAt")==true}
            else waitFor{chat.messages().any{it.getJSONObject("payload").optString("text")==offlineDmText} && chat.messages().any{it.getJSONObject("payload").optString("text")==offlineGroupText}}
            if(author)chat.offerAttachment(attachmentMessage.getString("id"))
            try{withTimeout(180000){while(repository.store.get("attachments",attachment.getString("id"))?.let{it.optBoolean("complete") && (author || it.optBoolean("chatOnly"))}!=true)delay(200)}}catch(e:TimeoutCancellationException){val f=repository.store.get("attachments",attachment.getString("id"));val parts=f?.optJSONArray("received");error("Encrypted resume did not finish: received ${parts?.let{p->(0 until p.length()).count{p.optBoolean(it)}}?:0} parts; complete=${f?.optBoolean("complete")}; errors=${errors.distinct().take(8)}")}
            val bytes=chat.attachmentBytes(attachmentMessage.getString("id"));assertEquals(4*1048576,bytes.size);assertEquals(attachment.getString("hash"),Protocol.digest(bytes));assertFalse(session.readSavedBytes(attachment.getString("id")).contentEquals(bytes));meet("encrypted-resume-plaintext-hash-verified")
            chat.sync();meet("channel-and-media-metadata-synced");chat.sync();meet("second-sync-deduplicated")
            assertEquals(11,chat.messages().size)
            if(author)chat.synchronizeAttachment(attachmentMessage.getString("id"),true)
            meet("encrypted-server-upload-complete")
            if(!author){session.removeFile(attachment.getString("id"));chat.synchronizeAttachment(attachmentMessage.getString("id"),true);assertEquals(attachment.getString("hash"),Protocol.digest(chat.attachmentBytes(attachmentMessage.getString("id"))))}
            meet("encrypted-server-download-plaintext-verified")
            }
            // New milestone: real radio transports admission, thread state, help and sanitized public media.
            suspend fun channelMilestone(){
            invitation="";var approvalId=""
            if(author){approvalId=chat.create("Physical approval announcements","INVITE","ANNOUNCEMENT","INVITE_PLUS_APPROVAL");session.send("CHAT_INVITE",chat.invite(approvalId,chat.peer!!))}
            if(!author){waitFor{invitation.isNotBlank()};val invite=chat.decodeInvite(invitation);assertFalse(invite.toString().contains("\"keys\""));approvalId=chat.acceptInvite(invitation);assertNull(chat.current(approvalId))}
            meet("approval-request-without-key",obj("id" to approvalId))
            if(author){waitFor{chat.joinRequests(approvalId).isNotEmpty()};chat.moderate(approvalId,"APPROVE_JOIN",ChatProtocol.participant(chat.peer!!))}
            waitFor{chat.conversations().any{it.getString("id")==approvalId && it.optBoolean("joined")}};meet("physical-approval-fresh-key-delivered")
            if(!author)assertTrue(runCatching{chat.send(approvalId,obj("text" to "Raw forbidden announcement"))}.isFailure)
            val rootId=if(author)chat.send(approvalId,obj("text" to "Fictional distribution announcement"))else "";val rootMeta=meet("announcement-root",obj("id" to rootId));val root=if(author)rootId else rootMeta.getString("id")
            waitFor{chat.messages().any{it.getString("id")==root}}
            if(!author)chat.send(approvalId,obj("text" to "Fictional allowed thread reply"),threadRootId=root)
            waitFor{chat.messages().any{it.getJSONObject("envelope").getJSONObject("body").optString("threadRootId")==root}};meet("physical-authorized-thread-reply")
            if(author)chat.moderate(approvalId,"LOCK_THREAD",root)
            waitFor{chat.threadLocked(approvalId,root)};if(!author)assertTrue(runCatching{chat.send(approvalId,obj("text" to "Raw forbidden locked reply"),threadRootId=root)}.isFailure);meet("physical-thread-lock-enforced")
            if(author)chat.moderate(approvalId,"BAN",ChatProtocol.participant(chat.peer!!))
            if(!author)waitFor{chat.conversations().first{it.getString("id")==approvalId}.optBoolean("joined")==false}
            if(!author){val removed=chat.current(approvalId)!!;assertNull(repository.store.get("chat-keys",Protocol.hash(removed)));val beforeName=ChatProtocol.participant(chat.profile());chat.rename("Test renamed Priya");assertEquals(beforeName,ChatProtocol.participant(chat.profile()))};meet("physical-ban-excludes-fresh-key-after-rename")
            val freshPost=if(author)chat.send(approvalId,obj("text" to "Fictional post after ban"))else ""
            val postMeta=meet("post-after-ban-created",obj("id" to freshPost));delay(1500)
            if(!author)assertTrue(chat.messages().none{it.getString("id")==postMeta.getString("id")})
            meet("physical-banned-recipient-excluded-from-future-post")
            if(author)chat.moderate(approvalId,"UNBAN",ChatProtocol.participant(chat.peer!!));meet("physical-authorized-unban")
            };channelMilestone()
            suspend fun publicMilestone(){
            val helpId=if(author)community.saveHelp(obj("category" to "WATER","audience" to "MYSELF","quantity" to 2,"details" to "Fictional two water bottles needed","area" to "Physical fictional Gate 2","priority" to "NORMAL","status" to "OPEN","responderId" to null))else "";val helpMeta=meet("help-created",obj("id" to helpId));val help=if(author)helpId else helpMeta.getString("id")
            waitFor{community.helps().any{it.getJSONObject("envelope").getJSONObject("body").getString("objectId")==help}};if(!author)community.offer(help)
            if(author)waitFor{community.offers(help).isNotEmpty()};meet("physical-help-request-and-offer")
            if(author)community.status(help,"RESPONDER_ASSIGNED",ChatProtocol.participant(chat.peer!!))
            waitFor{community.helps().first{it.getJSONObject("envelope").getJSONObject("body").getString("objectId")==help}.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("status")=="RESPONDER_ASSIGNED"};meet("physical-help-responder-assigned")
            if(author)community.status(help,"RESOLVED");waitFor{community.helps().first{it.getJSONObject("envelope").getJSONObject("body").getString("objectId")==help}.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("status")=="RESOLVED"};meet("physical-help-resolved")
            val reportId=if(author)community.report("Fictional public supplies photo","Physical fictional Gate 2",false,FieldMedia.prepare(context,Uri.fromFile(photo),false))else "";val reportMeta=meet("public-report-authored",obj("id" to reportId));val report=if(author)reportId else reportMeta.getString("id")
            waitFor{community.reports().any{it.getString("id")==report}};val record=community.reports().first{it.getString("id")==report};val originalAuthor=ChatProtocol.participant(record.getJSONObject("envelope").getJSONObject("body").getJSONObject("author"));val publicMedia=record.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("media");fileIds.add(publicMedia.getString("id"))
            if(author)community.shareMedia(report);if(!author)waitFor{repository.store.get("attachments",publicMedia.getString("id"))?.optBoolean("complete")==true}
            assertEquals(publicMedia.getString("hash"),Protocol.digest(session.readSavedBytes(publicMedia.getString("id"))));meet("physical-public-derivative-hash-verified")
            repository.store.put("preferences","local",obj("relay" to "OFF","mediaRelay" to false,"dailyLimitMiB" to 50,"batteryMinimum" to 20));if(!author){runCatching{community.sync()};assertFalse(repository.store.get("community",report)!!.optBoolean("serverSaved"))};meet("physical-carrier-relay-off-enforced")
            if(!author){repository.store.put("preferences","local",obj("relay" to "ANY","mediaRelay" to true,"dailyLimitMiB" to 50,"batteryMinimum" to 20));community.sync();val saved=repository.store.get("community",report)!!;val detail="Public gateway: saved=${saved.optBoolean("serverSaved")}, media=${saved.optBoolean("mediaOnline")}, rejection=${saved.optString("rejectedReason")}, waiting=${saved.optString("waitingReason")}, internet=${internet()}";assertTrue(detail,saved.optBoolean("serverSaved"));assertTrue(detail,saved.optBoolean("mediaOnline"));assertEquals(originalAuthor,ChatProtocol.participant(saved.getJSONObject("envelope").getJSONObject("body").getJSONObject("author")))};meet("physical-consenting-carrier-original-author-upload")
            if(author)community.withdraw(report);if(!author)waitFor{community.reports().none{it.getString("id")==report}};meet("physical-report-withdrawal-relayed")
            };if(!radioOnly)publicMilestone()
            if(author)chat.membership(privateId,ChatProtocol.participant(chat.peer!!))
            if(!author)waitFor{chat.conversations().first{it.getString("id")==privateId}.optBoolean("joined")==false}
            meet("private-member-removal-applied")
            if(!author)assertTrue(runCatching{chat.send(privateId,obj("text" to "Must be rejected"))}.isFailure)
            val before=chat.messages().size
            if(!author)chat.block(ChatProtocol.participant(chat.peer!!));meet("blocked-recipient")
            if(author)chat.send(dm,obj("text" to "Must not appear on blocked recipient"))
            delay(1500);if(!author)assertEquals(before,chat.messages().size);meet("block-prevents-new-delivery")
            assertTrue(errors.joinToString("; "),errors.filterNot{it.contains("paused",true)||it.contains("Connection changed",true)}.isEmpty())
            if(radioOnly)assertFalse("Direct Nearby must remain offline",internet())
            meet("complete",obj("baselineMessages" to if(milestoneOnly)8 else 9,"milestoneMessages" to (if(author)12 else 11)-(if(milestoneOnly)1 else 0),"resumedBytes" to if(milestoneOnly)0 else 4*1048576,"internetValidated" to internet()))
        }finally{
            chat.messages().forEach{it.getJSONObject("payload").optJSONObject("attachment")?.let{a->fileIds.add(a.getString("id"))}}
            withContext(Dispatchers.Main){transport.disconnect();session.reset();fileIds.forEach{session.removeFile(it)};wifi?.release()};scope.cancel();input.delete();photo.delete();voice.delete();video.delete();repository.store.clearPrivate();repository.store.close();context.deleteDatabase("saathi-$storageScope.db");runCatching{activity.close()}
        }
    }
}

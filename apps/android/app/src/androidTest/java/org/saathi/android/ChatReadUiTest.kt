package org.saathi.android

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Long fictional history verifies the actual viewport/read boundary, never owner storage. */
@RunWith(AndroidJUnit4::class)
class ChatReadUiTest {
    @get:Rule val ui=createComposeRule()

    @Composable private fun ThemedChatSurface(content:@Composable ()->Unit) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,content=content)
    }

    private fun saveReviewCapture(context:android.content.Context,name:String,image:Bitmap) {
        val review=Bitmap.createScaledBitmap(image,(image.width*.75f).toInt(),(image.height*.75f).toInt(),true)
        File(context.cacheDir,name).outputStream().use{review.compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun messageActionsStayOutOfTheTranscriptUntilRequested() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope="test-chat-simple-${UUID.randomUUID()}"
        lateinit var vm:SaathiViewModel
        val viewModels=ViewModelStore();val shown=mutableStateOf(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync{vm=SaathiViewModel(context.applicationContext as Application,storageScope,startServices=false);viewModels.put("fixture",vm)}
        val imageFile=File(context.cacheDir,"chat-simple-preview.png")
        try {
            val preview=Bitmap.createBitmap(144,96,Bitmap.Config.ARGB_8888).apply{
                for(y in 0 until height)for(x in 0 until width)setPixel(x,y,when{
                    x<width/3->android.graphics.Color.rgb(35,87,67)
                    x<width*2/3->android.graphics.Color.rgb(226,166,76)
                    else->android.graphics.Color.rgb(226,236,218)
                })
            }
            imageFile.outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)};preview.recycle()
            val channel=runBlocking {
                vm.chat.rename("Fictional channel owner")
                val id=vm.chat.create("Fictional briefing","INVITE","ANNOUNCEMENT","INVITE_PLUS_APPROVAL")
                vm.chat.send(id,obj("text" to "Fictional coordination: meet at the public entrance."))
                vm.chat.attach(id,Uri.fromFile(imageFile),"image/png","Fictional field photo.png")
                id
            }
            vm.refreshLocal()
            ui.waitUntil(15000){vm.state.value.chatMessages.any{it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==channel&&it.getJSONObject("payload").has("attachment")}}
            val attachmentMessageId=vm.state.value.chatMessages.first{it.getJSONObject("payload").has("attachment")}.getString("id")
            ui.setContent { if(shown.value){val state by vm.state.collectAsState();SaathiTheme{ThemedChatSurface{ConversationScreen(vm,state,channel,{_,_->},{},{},{},androidx.compose.ui.Modifier.fillMaxSize())}}} }
            ui.onNodeWithTag("chat-transcript").performScrollToIndex(1)
            ui.onNodeWithText("Fictional coordination: meet at the public entrance.").assertIsDisplayed()
            ui.onNodeWithTag("chat-transcript").performScrollToIndex(0)
            ui.onNodeWithTag("chat-photo-$attachmentMessageId").assertIsDisplayed()
            ui.onNodeWithText("Fictional field photo.png").assertDoesNotExist()
            listOf("0 replies","Thanks","Lock thread","Hide","Report","Export verified attachment","Sync encrypted attachment","Create Help Request").forEach{ui.onNodeWithText(it).assertDoesNotExist()}
            ui.onRoot().captureToImage().asAndroidBitmap().let{saveReviewCapture(context,"chat-simple-message.png",it)}
            ui.onNodeWithTag("chat-photo-$attachmentMessageId").performClick()
            ui.onNodeWithContentDescription("Close photo preview").assertIsDisplayed()
            ui.onNodeWithContentDescription("Close photo preview").performClick()
            val textMessageId=vm.state.value.chatMessages.first{it.getJSONObject("payload").optString("text").contains("Fictional coordination")}.getString("id")
            ui.onNodeWithTag("chat-transcript").performScrollToIndex(1)
            ui.onNodeWithTag("message-actions-$textMessageId").performClick()
            listOf("Copy message","Reply in thread","Thank sender","Lock replies","Hide message").forEach{ui.onNodeWithText(it).assertIsDisplayed()}
            ui.onRoot().captureToImage().asAndroidBitmap().let{saveReviewCapture(context,"chat-simple-actions.png",it)}
            ui.onNodeWithText("Copy message").performClick()
            ui.onNodeWithTag("message-actions-$attachmentMessageId").performClick()
            listOf("Save attachment","Check for updates").forEach{ui.onNodeWithText(it).assertIsDisplayed()}
        } finally {
            ui.runOnIdle{shown.value=false;viewModels.clear()}
            SecureStore(context,storageScope).use{it.clearPrivate()};context.deleteDatabase("saathi-$storageScope.db");imageFile.delete()
        }
    }

    @Test fun channelSettingsRemainInteractiveDuringIncomingRefreshBurst() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val storageScope="test-chat-refresh-${UUID.randomUUID()}"
        lateinit var vm:SaathiViewModel
        val viewModels=ViewModelStore();val shown=mutableStateOf(true)
        instrumentation.runOnMainSync{vm=SaathiViewModel(context.applicationContext as Application,storageScope,startServices=false);viewModels.put("fixture",vm)}
        try {
            val channel=runBlocking{vm.chat.rename("Fictional refresh tester");vm.chat.create("Refresh briefing","INVITE","ANNOUNCEMENT","INVITE_PLUS_APPROVAL")}
            ui.waitUntil(15000){vm.state.value.chatPolicies.any{it.getJSONObject("body").getString("id")==channel}}
            ui.setContent{if(shown.value){val state by vm.state.collectAsState();SaathiTheme{ThemedChatSurface{ConversationScreen(vm,state,channel,{_,_->},{},{},{},Modifier.fillMaxSize())}}}}
            // Real frame/file callbacks can arrive faster than hardware Keystore reads finish.
            repeat(100){vm.refreshLocal()}
            ui.onNodeWithContentDescription("Conversation settings").performClick()
            ui.onNodeWithText("Channel settings").assertIsDisplayed()
            ui.onNodeWithText("Create private-channel join link").assertIsDisplayed()
            ui.onNodeWithText("Done").performClick()
            ui.onNodeWithText("Channel settings").assertDoesNotExist()
            assertNull(vm.state.value.notice)
        } finally {
            ui.runOnIdle{shown.value=false;viewModels.clear()}
            SecureStore(context,storageScope).use{it.clearPrivate()};context.deleteDatabase("saathi-$storageScope.db")
        }
    }

    @Test fun sentAndReceivedMessagesUseDistinctTonalBubbles() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val storageScope="test-chat-bubbles-${UUID.randomUUID()}"
        lateinit var vm:SaathiViewModel
        val viewModels=ViewModelStore()
        instrumentation.runOnMainSync{vm=SaathiViewModel(context.applicationContext as Application,storageScope,startServices=false);viewModels.put("fixture",vm)}
        val other=Repository(context,"$storageScope-other")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val counterpart=ChatRepository(context,other,PeerSession(context,other,scope))
        val shown=mutableStateOf(true)
        val appearance=mutableStateOf("LIGHT")
        try{
            val direct=runBlocking{vm.chat.rename("Fictional recipient");counterpart.rename("Fictional sender");vm.chat.direct(counterpart.profile())}
            val reverse=runBlocking{counterpart.direct(vm.chat.profile())}
            val sentId=runBlocking{vm.chat.send(direct,obj("text" to "Fictional: heading to the public gate."))}
            val incomingId=runBlocking{counterpart.send(reverse,obj("text" to "Fictional: I’m at the public gate."))}
            val incoming=other.store.get("chat-messages",incomingId)!!
            ChatProtocol.message(incoming.getJSONObject("envelope"),null);incoming.put("owned",false)
            vm.repository.store.put("chat-messages",incomingId,incoming);vm.refreshLocal()
            ui.setContent{if(shown.value){val state by vm.state.collectAsState();SaathiTheme(appearance.value){ThemedChatSurface{ConversationScreen(vm,state,direct,{_,_->},{},{},{},Modifier.fillMaxSize())}}}}
            ui.onNodeWithText("Fictional: heading to the public gate.").assertIsDisplayed()
            ui.onNodeWithText("Fictional: I’m at the public gate.").assertIsDisplayed()
            val sent=ui.onNodeWithTag("chat-bubble-sent-$sentId").assertIsDisplayed().captureToImage().asAndroidBitmap()
            val received=ui.onNodeWithTag("chat-bubble-received-$incomingId").assertIsDisplayed().captureToImage().asAndroidBitmap()
            assertNotEquals("Sent and received bubbles should use different Material tones",sent.getPixel(sent.width/2,2),received.getPixel(received.width/2,2))
            ui.onRoot().captureToImage().asAndroidBitmap().let{image->saveReviewCapture(context,"chat-bubble-directions.png",image)}
            appearance.value="DARK"
            ui.waitForIdle()
            val darkSent=ui.onNodeWithTag("chat-bubble-sent-$sentId").assertIsDisplayed().captureToImage().asAndroidBitmap()
            val darkReceived=ui.onNodeWithTag("chat-bubble-received-$incomingId").assertIsDisplayed().captureToImage().asAndroidBitmap()
            assertEquals("Dark chat should use the dark app surface",android.graphics.Color.rgb(21,34,30),ui.onRoot().captureToImage().asAndroidBitmap().getPixel(1,1))
            assertEquals("Outgoing dark bubble should use the brighter forest tone",android.graphics.Color.rgb(62,118,93),darkSent.getPixel(darkSent.width/2,2))
            assertEquals("Incoming dark bubble should use the warm contrasting tone",android.graphics.Color.rgb(128,103,64),darkReceived.getPixel(darkReceived.width/2,2))
            ui.onRoot().captureToImage().asAndroidBitmap().let{image->saveReviewCapture(context,"chat-bubble-directions-dark.png",image)}
        }finally{
            ui.runOnIdle{shown.value=false;viewModels.clear()};scope.cancel();other.store.close()
            SecureStore(context,storageScope).use{it.clearPrivate()};context.deleteDatabase("saathi-$storageScope.db")
        }
    }

    @Test fun privateChannelJoinLinkCreatesPendingRequestWithoutExposingChannelKeys() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val ownerScope="test-chat-link-owner-${UUID.randomUUID()}";val visitorScope="test-chat-link-visitor-${UUID.randomUUID()}"
        lateinit var owner:SaathiViewModel;lateinit var visitor:SaathiViewModel
        val ownerStore=ViewModelStore();val visitorStore=ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner=SaathiViewModel(context.applicationContext as Application,ownerScope,startServices=false);ownerStore.put("owner",owner)
            visitor=SaathiViewModel(context.applicationContext as Application,visitorScope,startServices=false);visitorStore.put("visitor",visitor)
        }
        try {
            val channel=runBlocking {
                owner.chat.rename("Fictional channel owner")
                visitor.chat.rename("Fictional invited participant")
                owner.chat.create("Fictional private room","INVITE","DISCUSSION","INVITE_AUTO")
            }
            val link=runBlocking{owner.chat.createJoinLink(channel)}
            val invitation=owner.chat.decodeInvite(link);val body=invitation.getJSONObject("body")
            assertEquals("*",body.getString("recipientId"));assertFalse(body.has("policy"));assertFalse(body.has("keys"))
            assertEquals(channel,runBlocking{visitor.chat.acceptInvite(link)})
            val request=visitor.repository.store.get("chat-joins",channel)!!.getJSONObject("request").getJSONObject("body")
            assertEquals("CHAT_ADMISSION",request.getJSONObject("invitation").getJSONObject("body").getString("kind"))
            assertFalse(visitor.chat.conversations().first{it.getString("id")==channel}.optBoolean("joined"))
            assertNull(visitor.chat.current(channel))
            assertThrows(IllegalArgumentException::class.java){runBlocking{visitor.chat.acceptInvite(link)}}
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync{ownerStore.clear();visitorStore.clear()}
            listOf(ownerScope,visitorScope).forEach{scope->SecureStore(context,scope).use{it.clearPrivate()};context.deleteDatabase("saathi-$scope.db")}
        }
    }

    @Test fun unseenHistoryStaysUnreadAndNewMessagesDoNotDisplaceHistory() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val scopeName="test-chat-read-${UUID.randomUUID()}"
        lateinit var vm:SaathiViewModel
        val viewModels=ViewModelStore()
        instrumentation.runOnMainSync { vm=SaathiViewModel(context.applicationContext as Application,scopeName,startServices=false);viewModels.put("fixture",vm) }
        val other=Repository(context,"$scopeName-other")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val counterpart=ChatRepository(context,other,PeerSession(context,other,scope))
        val shown=mutableStateOf(true)
        val statusProof=mutableStateOf(false)
        val observed=java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        var latestId=""
        fun phase(value:String){File(context.cacheDir,"swarm-read-phase.txt").writeText(value)}
        phase("initialized")
        try {
            val dm=runBlocking { phase("reader-renaming");vm.chat.rename("Test reader");phase("sender-renaming");counterpart.rename("Test sender");phase("direct-creating");vm.chat.direct(counterpart.profile()) }
            val reverse=runBlocking { counterpart.direct(vm.chat.profile()) }
            val incoming=mutableListOf<org.json.JSONObject>()
            fun appendIncoming(number:Int):org.json.JSONObject=runBlocking {
                val id=counterpart.send(reverse,obj("text" to "Fictional message $number: coordinate sealed supplies and check the verified need before sending. This is isolated test history."))
                val record=other.store.get("chat-messages",id)!!;ChatProtocol.message(record.getJSONObject("envelope"),null);record.put("owned",false)
                vm.repository.store.put("chat-messages",id,record);incoming.add(record);record
            }
            repeat(20){appendIncoming(it+1)}
            phase("history-created")
            val screen=mutableStateOf(AppState(conversations=vm.chat.conversations(),chatMessages=incoming.toList(),chatProfile=vm.chat.profile()))
            ui.setContent { if(shown.value)SaathiTheme { ThemedChatSurface { if(statusProof.value)Column(Modifier.fillMaxSize()){androidx.compose.material3.Text("DEVELOPMENT · Fictional connection-state preview");ConnectionStatus(AppState(confirmed=true))} else ConversationScreen(vm,screen.value,dm,{_,_->},{},{},{},Modifier.fillMaxSize(),onVisibleMessages={observed.add(it);vm.readChat(dm,it)}) } } }
            phase("content-set")
            ui.onNodeWithText(incoming.last().getJSONObject("payload").getString("text")).assertIsDisplayed()
            ui.waitUntil(10000){vm.repository.store.get("chat-messages",incoming.last().getString("id"))?.optBoolean("readLocally")==true}
            assertFalse("Offscreen older history must stay unread",vm.repository.store.get("chat-messages",incoming.first().getString("id"))!!.optBoolean("readLocally"))
            ui.onRoot().captureToImage().asAndroidBitmap().let{image->File(context.cacheDir,"swarm-long-history-latest.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
            repeat(5){ui.onNodeWithTag("chat-transcript").performTouchInput{swipeDown()};ui.waitForIdle()}
            val new=appendIncoming(21)
            latestId=new.getString("id")
            ui.runOnIdle {screen.value=screen.value.copy(chatMessages=incoming.toList())}
            ui.waitForIdle()
            ui.onNodeWithText(new.getJSONObject("payload").getString("text")).assertDoesNotExist()
            assertFalse("Incoming message below the history viewport must stay unread",vm.repository.store.get("chat-messages",new.getString("id"))!!.optBoolean("readLocally"))
            ui.onRoot().captureToImage().asAndroidBitmap().let{image->File(context.cacheDir,"swarm-long-history-scrolled.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
            ui.onNodeWithText("Latest messages").performClick()
            phase("latest-clicked")
            ui.waitForIdle()
            phase("latest-idle")
            ui.onNodeWithText(new.getJSONObject("payload").getString("text")).assertIsDisplayed()
            ui.waitUntil(10000){vm.repository.store.get("chat-messages",new.getString("id"))?.optBoolean("readLocally")==true}
            ui.onNodeWithText(new.getJSONObject("payload").getString("text")).assertIsDisplayed()
            val readReceipts=vm.repository.store.all("chat-receipts").filter{it.getJSONObject("receipt").getJSONObject("body").getString("status")=="READ"}
            assertEquals(1,readReceipts.count{it.getJSONObject("receipt").getJSONObject("body").getString("messageId")==new.getString("id")})
            repeat(2){ui.onNodeWithTag("chat-transcript").performTouchInput{swipeDown()};ui.waitForIdle()}
            val own=runBlocking { vm.chat.send(dm,obj("text" to "Fictional own message stays visible")) }
            ui.runOnIdle{screen.value=screen.value.copy(chatMessages=incoming+vm.repository.store.get("chat-messages",own)!!)}
            ui.onNodeWithText("Fictional own message stays visible").assertIsDisplayed()
            ui.runOnIdle{statusProof.value=true};ui.waitForIdle()
            ui.onNodeWithText("Connected nearby · Saved work remains on this phone").assertIsDisplayed()
            ui.onRoot().captureToImage().asAndroidBitmap().let{image->File(context.cacheDir,"swarm-nearby-status.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
            phase("passed")
        } catch(error:Throwable){File(context.cacheDir,"swarm-read-failure.txt").writeText("Visible callbacks=${observed.size}; latest observed=${observed.any{latestId in it}}; notice=${vm.state.value.notice}\n"+error.stackTraceToString());throw error
        } finally {
            ui.runOnIdle {shown.value=false}
            scope.cancel();other.store.clearPrivate();other.store.close();context.deleteDatabase("saathi-$scopeName-other.db")
            vm.repository.store.clearPrivate();ui.runOnIdle {viewModels.clear()};context.deleteDatabase("saathi-$scopeName.db")
        }
    }
}

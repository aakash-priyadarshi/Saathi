package org.saathi.android

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
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

    @Test fun messageActionsStayOutOfTheTranscriptUntilRequested() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope="test-chat-simple-${UUID.randomUUID()}"
        lateinit var vm:SaathiViewModel
        val viewModels=ViewModelStore();val shown=mutableStateOf(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync{vm=SaathiViewModel(context.applicationContext as Application,storageScope,startServices=false);viewModels.put("fixture",vm)}
        val imageFile=File(context.cacheDir,"chat-simple-preview.png")
        try {
            val preview=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(35,87,67))}
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
            ui.setContent { if(shown.value){val state by vm.state.collectAsState();SaathiTheme{ConversationScreen(vm,state,channel,{_,_->},{},{},{},androidx.compose.ui.Modifier.fillMaxSize())}} }
            ui.onNodeWithText("Fictional coordination: meet at the public entrance.").assertIsDisplayed()
            ui.waitUntil(15000){ui.onAllNodesWithContentDescription("Private chat photo").fetchSemanticsNodes().isNotEmpty()}
            listOf("0 replies","Thanks","Lock thread","Hide","Report","Export verified attachment","Sync encrypted attachment","Create Help Request").forEach{ui.onNodeWithText(it).assertDoesNotExist()}
            ui.onRoot().captureToImage().asAndroidBitmap().let{capture->File(context.cacheDir,"chat-simple-message.png").outputStream().use{capture.compress(Bitmap.CompressFormat.PNG,100,it)}}
            val textMessageId=vm.state.value.chatMessages.first{it.getJSONObject("payload").optString("text").contains("Fictional coordination")}.getString("id")
            ui.onNodeWithTag("message-$textMessageId").performTouchInput{longClick()}
            listOf("Reply","Copy","Forward","Delete").forEach{ui.onNodeWithText(it).assertIsDisplayed()}
            ui.onRoot().captureToImage().asAndroidBitmap().let{capture->File(context.cacheDir,"chat-simple-actions.png").outputStream().use{capture.compress(Bitmap.CompressFormat.PNG,100,it)}}
            ui.onNodeWithText("Copy").performClick()
            val attachmentMessageId=vm.state.value.chatMessages.first{it.getJSONObject("payload").has("attachment")}.getString("id")
            ui.onNodeWithTag("message-$attachmentMessageId").performTouchInput{longClick()}
            listOf("Forward","Save","Delete").forEach{ui.onNodeWithText(it).assertIsDisplayed()}
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
            ui.setContent{if(shown.value){val state by vm.state.collectAsState();SaathiTheme{ConversationScreen(vm,state,channel,{_,_->},{},{},{},Modifier.fillMaxSize())}}}
            // Real frame/file callbacks can arrive faster than hardware Keystore reads finish.
            repeat(100){vm.refreshLocal()}
            ui.onNodeWithContentDescription("Group info").performClick()
            ui.onNodeWithText("Group info").assertIsDisplayed()
            ui.onNodeWithText("Add people").performClick()
            ui.onNodeWithText("Create join link").assertIsDisplayed()
            ui.onNodeWithText("Done").performClick()
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithText("Group info").assertDoesNotExist()
            assertNull(vm.state.value.notice)
        } finally {
            ui.runOnIdle{shown.value=false;viewModels.clear()}
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
            ui.setContent { if(shown.value)SaathiTheme { if(statusProof.value)Column(Modifier.fillMaxSize()){androidx.compose.material3.Text("DEVELOPMENT · Fictional connection-state preview");ConnectionStatus(AppState(confirmed=true))} else ConversationScreen(vm,screen.value,dm,{_,_->},{},{},{},Modifier.fillMaxSize(),onVisibleMessages={observed.add(it);vm.readChat(dm,it)}) } }
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

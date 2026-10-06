package org.saathi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional layout fixture, deliberately separate from device-owner storage. Not transport evidence. */
@RunWith(AndroidJUnit4::class)
class ChatUiFixtureTest {
    @Test fun prepareFictionalLayoutFixture():Unit=runBlocking{
        assumeTrue(BuildConfig.DEBUG&&BuildConfig.ENVIRONMENT=="development"&&InstrumentationRegistry.getArguments().getString("uiFixture")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=Repository(context,"test-ui-swarm-preview");val other=Repository(context,"test-ui-swarm-other")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=PeerSession(context,repository,scope);val chat=ChatRepository(context,repository,session);val counterpart=ChatRepository(context,other,PeerSession(context,other,scope))
        try{
            repository.store.clearPrivate();other.store.clearPrivate();chat.rename("Test Arjun");counterpart.rename("Test Priya")
            val dm=chat.direct(counterpart.profile());val reverse=counterpart.direct(chat.profile())
            chat.send(dm,obj("text" to "Can you bring water to Gate 2?"));counterpart.send(reverse,obj("text" to "I can bring two sealed packs. This is a fictional preview."))
            val inbound=counterpart.messages().single();ChatProtocol.message(inbound.getJSONObject("envelope"),null);inbound.put("owned",false);repository.store.put("chat-messages",inbound.getString("id"),inbound)
            val open=chat.create("Medical help","OPEN");chat.send(open,obj("text" to "Fictional coordination: check the current verified need before arranging supplies."))
            val private=chat.create("Coordination","INVITE");chat.send(private,obj("text" to "Messages stay separate from public relief updates."));chat.mute(private)
            val announcement=chat.create("Announcements","INVITE","ANNOUNCEMENT","INVITE_PLUS_APPROVAL");val root=chat.send(announcement,obj("text" to "Fictional briefing: first aid is available near the public gate."));chat.send(announcement,obj("text" to "Fictional thread reply: check conditions before travelling."),threadRootId=root)
            val community=CommunityRepository(context,repository,chat,session);community.saveHelp(obj("category" to "WATER","audience" to "MYSELF","quantity" to 2,"details" to "Fictional preview: two sealed water bottles needed.","area" to "Fictional Gate 2","priority" to "NORMAL","status" to "OPEN","responderId" to null));community.report("Fictional preview: the public access route is clear.","Fictional Gate 2",false)
            java.io.File(context.cacheDir,"swarm-qa-public-profile.json").writeText(chat.profile().toString())
            InstrumentationRegistry.getArguments().getString("qaRecipient")?.let{encoded->val recipient=org.json.JSONObject(String(Protocol.decode(encoded)));ChatProtocol.profile(recipient);chat.direct(recipient)}
        }finally{scope.cancel();repository.store.close();other.store.close()}
    }
}

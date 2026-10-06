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
        val chat=ChatRepository(context,repository,PeerSession(context,repository,scope));val counterpart=ChatRepository(context,other,PeerSession(context,other,scope))
        try{
            repository.store.clearPrivate();other.store.clearPrivate();chat.rename("Test Arjun");counterpart.rename("Test Priya")
            val dm=chat.direct(counterpart.profile());val reverse=counterpart.direct(chat.profile())
            chat.send(dm,obj("text" to "Can you bring water to Gate 2?"));counterpart.send(reverse,obj("text" to "I can bring two sealed packs. This is a fictional preview."))
            val inbound=counterpart.messages().single();ChatProtocol.message(inbound.getJSONObject("envelope"),null);inbound.put("owned",false);repository.store.put("chat-messages",inbound.getString("id"),inbound)
            val open=chat.create("Medical help","OPEN");chat.send(open,obj("text" to "Fictional coordination: check the current verified need before arranging supplies."))
            val private=chat.create("Coordination","INVITE");chat.send(private,obj("text" to "Messages stay separate from public relief updates."));chat.mute(private)
        }finally{scope.cancel();repository.store.close();other.store.close()}
    }
}

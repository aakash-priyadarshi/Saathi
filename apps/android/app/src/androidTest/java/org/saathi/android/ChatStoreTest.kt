package org.saathi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatStoreTest {
    @Test fun reopenedEncryptedConversationKeepsIdentityMessagesAndMembership():Unit=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope="test-chat-store-${UUID.randomUUID()}"
        var repository=Repository(context,storageScope)
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        var chat=ChatRepository(context,repository,PeerSession(context,repository,scope))
        try{
            chat.rename("Fictional persistence tester");val identity=Protocol.hash(chat.profile())
            val channel=chat.create("Stored coordination","INVITE")
            val text="Fictional durable private conversation ${UUID.randomUUID()}"
            val id=chat.send(channel,obj("text" to text));chat.mute(channel)
            val raw=repository.store.readableDatabase.rawQuery("SELECT data,private FROM records WHERE bucket='chat-messages' AND id=?",arrayOf(id)).use{it.moveToFirst();assertEquals(1,it.getInt(1));it.getBlob(0)}
            assertFalse(String(raw).contains(text))
            scope.cancel();repository.store.close()
            repository=Repository(context,storageScope);scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);chat=ChatRepository(context,repository,PeerSession(context,repository,scope))
            assertEquals(identity,Protocol.hash(chat.profile()));assertEquals(text,chat.messages().single().getJSONObject("payload").getString("text"));assertEquals(channel,chat.conversations().single().getString("id"));assertTrue(chat.conversations().single().optBoolean("muted"))
            assertEquals(1,chat.policies().size)
            assertTrue(chat.send(channel,obj("text" to "Continues after reopen")).isNotBlank());assertEquals(2,chat.messages().size)
            repository.store.deleteIdentity("swarm-chat")
            assertTrue("Missing keys must not silently reset identity",runCatching{chat.profile()}.isFailure)
            assertEquals(2,repository.store.all("chat-messages").size)
        }finally{scope.cancel();repository.store.clearPrivate();repository.store.close();context.deleteDatabase("saathi-$storageScope.db")}
    }
}

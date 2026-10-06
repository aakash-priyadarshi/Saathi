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
    @Test fun updatedPeerProfileRefreshesContactAndExistingDirectTitle():Unit=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val ownScope="test-chat-name-${UUID.randomUUID()}";val peerScope="$ownScope-peer"
        val ownRepository=Repository(context,ownScope);val peerRepository=Repository(context,peerScope)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val own=ChatRepository(context,ownRepository,PeerSession(context,ownRepository,scope))
        val peer=ChatRepository(context,peerRepository,PeerSession(context,peerRepository,scope))
        try{
            peer.rename("Earlier display name")
            val oldProfile=peer.profile();val direct=own.direct(oldProfile)
            assertEquals("Earlier display name",own.conversations().single().getString("title"))
            peer.rename("Updated display name")
            own.remember(peer.profile())
            assertEquals("Updated display name",own.contacts().single().getJSONObject("profile").getJSONObject("body").getString("name"))
            assertEquals("Updated display name",own.conversations().single().getString("title"))
            own.remember(oldProfile)
            assertEquals("Updated display name",own.conversations().single().getString("title"))
            assertEquals(direct,own.conversations().single().getString("id"))
        }finally{
            scope.cancel();ownRepository.store.clearPrivate();peerRepository.store.clearPrivate();ownRepository.store.close();peerRepository.store.close()
            context.deleteDatabase("saathi-$ownScope.db");context.deleteDatabase("saathi-$peerScope.db")
        }
    }

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

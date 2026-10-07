package org.saathi.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatPinsTest {
    private fun message(id:String,at:String)=JSONObject("""{"id":"$id","payload":{"text":"Fictional $id"},"envelope":{"body":{"conversationId":"c","createdAt":"$at"}}}""")
    private val chat=JSONObject("""{"id":"c","type":"CHANNEL","title":"Fictional group","deletedLocally":true}""")

    @Test fun aChatKeepsAtMostThreePins() {
        val three=listOf("a","b","c").fold(emptyList<String>()){pins,id->togglePin(pins,id)}
        assertEquals("Up to 3 pinned messages. Unpin one first.",assertThrows(IllegalArgumentException::class.java){togglePin(three,"d")}.message)
        assertEquals(listOf("a","c","d"),togglePin(togglePin(three,"b"),"d"))
    }

    @Test fun deletedMessagesDropOutOfPins() {
        val state=AppState(chatMessages=listOf(message("a","2026-10-09T10:00:00Z"),message("b","2026-10-09T10:01:00Z")),chatDeleted=setOf("a"),chatPins=mapOf("c" to listOf("a","b")))
        assertEquals(listOf("b"),state.pins("c").map{it.getString("id")})
    }

    @Test fun deletedChatStaysHiddenWhenOldMessagesSyncAgainAndReturnsWithNewOnes() {
        // Delete chat marks every held message deleted for me; a re-synced copy has the same id, so it stays hidden.
        val old=message("old","2026-10-09T10:00:00Z")
        val deleted=AppState(conversations=listOf(chat),chatMessages=listOf(old,JSONObject(old.toString())),chatDeleted=setOf("old"))
        assertFalse(deleted.listed(chat));assertTrue(deleted.shownMessages("c").isEmpty())
        val reply=deleted.copy(chatMessages=deleted.chatMessages+message("new","2026-10-09T11:00:00Z"))
        assertTrue(reply.listed(chat));assertEquals(listOf("new"),reply.shownMessages("c").map{it.getString("id")})
    }
}

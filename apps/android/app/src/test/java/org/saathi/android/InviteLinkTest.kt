package org.saathi.android

import org.junit.Assert.*
import org.junit.Test

class InviteLinkTest {
    @Test fun acceptsBothFormsOnlyForSwarm() {
        assertEquals("H4sI_a-b",InviteLink.token(" https://swarm.cockroachjantaparty.org/join#H4sI_a-b\n"))
        assertEquals("H4sI_a-b",InviteLink.token("cjpswarm://invite/H4sI_a-b"))
        listOf("https://evil.example/join#H4sI","https://swarm.cockroachjantaparty.org.evil.example/join#H4sI","https://swarm.cockroachjantaparty.org/other#H4sI",
            "http://swarm.cockroachjantaparty.org/join#H4sI","https://swarm.cockroachjantaparty.org/join#","https://swarm.cockroachjantaparty.org/join#a/b","cjpswarm://invite/a?b",null).forEach{assertNull(it,InviteLink.token(it))}
    }
}

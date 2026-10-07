package org.saathi.android

/** Invitations travel as `https://swarm.cockroachjantaparty.org/join#<base64url(gzip(json))>`; the fragment never reaches the server. Older `cjpswarm://invite/<payload>` links still work. */
object InviteLink {
    const val WEB="https://swarm.cockroachjantaparty.org/join#"
    private const val APP="cjpswarm://invite/"
    /** The payload of either link form, or null for anything else (another host, path, query or character set). */
    fun token(link:String?):String?{
        val t=link?.trim()?:return null
        val token=when{t.startsWith(WEB)->t.substring(WEB.length);t.startsWith(APP)->t.substring(APP.length);else->return null}
        return token.takeIf{it.length in 1..699980 && it.all{c->c in 'A'..'Z'||c in 'a'..'z'||c in '0'..'9'||c=='-'||c=='_'}}
    }
}

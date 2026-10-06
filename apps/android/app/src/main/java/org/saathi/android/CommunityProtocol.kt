package org.saathi.android

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Signed public statements. A valid participant signature is never volunteer authority. */
object CommunityProtocol {
    val categories=listOf("WATER","FIRST_AID","FOOD","CHARGING","ACCESSIBILITY","OTHER")
    private val digest=Regex("[a-f0-9]{64}")
    private val privateDetails=Regex("(?:[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?:\\+?\\d[\\d ()-]{7,}\\d)|-?\\d{1,3}\\.\\d{4,}\\s*[,/]\\s*-?\\d{1,3}\\.\\d{4,})",RegexOption.IGNORE_CASE)
    fun publicText(value:String,max:Int)=value.length in 1..max && value==value.trim() && !privateDetails.containsMatchIn(value) && value.codePoints().toArray().all{it>=32 && it !in 127..159 && it !in 0x202a..0x202e && it !in 0x2066..0x2069}
    private fun integer(value:JSONObject,key:String,min:Long,max:Long){require(value.get(key) is Int || value.get(key) is Long);require(value.getLong(key) in min..max)}
    private fun strings(value:JSONObject,vararg keys:String){require(keys.all{value.get(it) is String})}
    private fun uuid(value:String){require(UUID.fromString(value).toString()==value)}
    fun valid(event:JSONObject,now:Instant):Boolean=runCatching {
        event.exact("body","signature");val b=event.getJSONObject("body")
        b.exact("v","kind","id","objectId","author","createdAt","expiresAt","maxHops","payloadHash","type","payload")
        require(b.get("v")==1 && b.getString("kind")=="COMMUNITY_EVENT" && event.get("signature") is String);strings(b,"kind","id","objectId","createdAt","expiresAt","payloadHash","type")
        uuid(b.getString("id"));uuid(b.getString("objectId"));integer(b,"maxHops",1,6)
        ChatProtocol.profile(b.getJSONObject("author"),now)
        require(publicText(b.getJSONObject("author").getJSONObject("body").getString("name"),32)){"Choose a public display name without contact details."}
        val created=Instant.parse(b.getString("createdAt"));val expires=Instant.parse(b.getString("expiresAt"));val type=b.getString("type");val p=b.getJSONObject("payload")
        val duration=if(type in listOf("HELP","HELP_OFFER"))7200L else 7*86400L
        require(created<=now.plusSeconds(300) && expires>now && expires>created && expires<=created.plusSeconds(duration))
        when(type){
            "HELP"->{p.exact("version","previousHash","help");integer(p,"version",1,1000);require(p.isNull("previousHash") || p.get("previousHash") is String);val h=p.getJSONObject("help");h.exact("category","audience","quantity","details","area","priority","status","responderId");strings(h,"category","audience","details","area","priority","status");integer(h,"quantity",1,1000);require(h.isNull("responderId") || h.get("responderId") is String)
                require(h.getString("category") in categories && h.getString("audience") in listOf("MYSELF","GROUP") && h.getInt("quantity") in 1..1000)
                require(publicText(h.getString("details"),400) && publicText(h.getString("area"),80) && h.getString("priority") in listOf("NORMAL","IMPORTANT","URGENT"))
                require(h.getString("status") in listOf("OPEN","RESPONDER_ASSIGNED","RESOLVED","CANCELLED"))
                require((h.getString("status")=="RESPONDER_ASSIGNED")==!h.isNull("responderId"));if(!h.isNull("responderId"))require(h.getString("responderId").matches(digest))
                if(p.getInt("version")==1)require(b.getString("id")==b.getString("objectId") && p.isNull("previousHash") && h.getString("status")=="OPEN") else require(b.getString("id")!=b.getString("objectId") && p.getString("previousHash").matches(digest))
            }
            "HELP_OFFER"->{p.exact("requestHash");strings(p,"requestHash");require(p.getString("requestHash").matches(digest))}
            "REPORT"->{p.exact("caption","area","contentWarning","media");strings(p,"caption","area");require(p.get("contentWarning") is Boolean && (p.isNull("media") || p.get("media") is JSONObject));require(b.getString("id")==b.getString("objectId") && publicText(p.getString("caption"),2000) && publicText(p.getString("area"),80))
                p.optJSONObject("media")?.let{m->m.exact("id","mime","size","hash","width","height","durationSeconds");strings(m,"id","mime","hash");uuid(m.getString("id"));integer(m,"size",1,16777216);integer(m,"width",1,1920);integer(m,"height",1,1920);integer(m,"durationSeconds",0,60);require(m.getString("mime") in listOf("image/jpeg","video/mp4") && m.getString("hash").matches(digest));require(if(m.getString("mime")=="image/jpeg")m.getInt("durationSeconds")==0 else m.getInt("durationSeconds") in 1..60)}
            }
            "WITHDRAW"->{p.exact("reportHash");strings(p,"reportHash");require(p.getString("reportHash").matches(digest))}
            "FLAG"->{p.exact("targetHash","reason");strings(p,"targetHash","reason");require(p.getString("targetHash").matches(digest) && p.getString("reason") in listOf("SPAM","HARASSMENT","UNSAFE","OTHER"))}
            else->error("Unsupported participant statement.")
        }
        require(Protocol.canonical(event).size<=16000 && b.getString("payloadHash")==Protocol.hash(p))
        Protocol.verify(b,event.getString("signature"),b.getJSONObject("author").getJSONObject("body").getJSONObject("publicKey"))
    }.getOrDefault(false)
}

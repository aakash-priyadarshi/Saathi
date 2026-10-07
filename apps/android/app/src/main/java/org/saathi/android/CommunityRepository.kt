package org.saathi.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Public assistance/reporting shares the existing identity, store, receipt trust and file transport. */
class CommunityRepository(private val context:Context,private val repository:Repository,private val chat:ChatRepository,private val session:PeerSession) {
    private val store get()=repository.store
    private val lock=Mutex();private val syncLock=Mutex()
    var onChange:()->Unit={}
    private var advertised=emptySet<String>();private var requested=mutableSetOf<String>();private var generation=-1L
    private fun now()=repository.clock()
    private fun self()=ChatProtocol.participant(chat.profile())
    private fun signed(body:JSONObject)=obj("body" to body,"signature" to Protocol.sign(body,store.privateKey("swarm-chat")))
    private fun relayLimitMB()=preferences().optInt("dailyLimitMB",preferences().optInt("dailyLimitMiB",500)).coerceIn(500,5000)
    private fun remainingRelayBytes():Long {
        val day=LocalDate.now(ZoneOffset.UTC).toString()
        val used=store.get("relay-usage",day)?.optLong("bytes")?:0
        return (relayLimitMB()*1_000_000L-used).coerceAtLeast(0)
    }
    private fun localGatewayRoute():GatewayRoute? {
        val p=preferences();if(p.optString("relay","OFF")=="OFF" || !gatewayAllowed())return null
        val checked=store.get("community-meta","gateway-check")?.optString("checkedAt")?.let{runCatching{Instant.parse(it)}.getOrNull()}?:return null
        val time=now();if(checked.plusSeconds(GatewayRouting.LEASE_SECONDS)<=time)return null
        val media=gatewayAllowed(true);val prior=store.get("community-meta","gateway-lease")?.optJSONObject("lease")
        val priorBody=prior?.optJSONObject("body")
        val priorTime=priorBody?.optString("issuedAt")?.let{runCatching{Instant.parse(it)}.getOrNull()}
        val profile=chat.profile()
        if(prior!=null && priorBody?.optBoolean("acceptsMedia")==media && priorBody.optLong("remainingBytes")==remainingRelayBytes()
            && priorBody.optJSONObject("profile")?.toString()==profile.toString() && priorTime?.plusSeconds(45)?.isAfter(time)==true
            && GatewayRouting.fromStored(obj("lease" to prior,"hops" to 0,"viaPeer" to "","mediaPath" to media),time)!=null) {
            return GatewayRouting.fromStored(obj("lease" to prior,"hops" to 0,"viaPeer" to "","mediaPath" to media),time)
        }
        val oldSequence=store.get("community-meta","gateway-sequence")?.optLong("sequence")?:0L
        val sequence=oldSequence+1
        val lease=GatewayRouting.lease(profile,sequence,time,media,remainingRelayBytes(),store.privateKey("swarm-chat"))
        store.put("community-meta","gateway-sequence",obj("sequence" to sequence))
        store.put("community-meta","gateway-lease",obj("lease" to lease))
        return GatewayRouting.fromStored(obj("lease" to lease,"hops" to 0,"viaPeer" to "","mediaPath" to media),time)
    }
    private fun knownGatewayRoutes():List<GatewayRoute> {
        val time=now();val valid=store.all("gateway-routes").mapNotNull{GatewayRouting.fromStored(it,time)}
        store.all("gateway-routes").forEach{row->if(GatewayRouting.fromStored(row,time)==null)store.remove("gateway-routes",row.getJSONObject("lease").getJSONObject("body").optString("gatewayId"))}
        return (valid+listOfNotNull(localGatewayRoute())).distinctBy{it.gatewayId}
            .sortedWith(compareBy<GatewayRoute>{it.hops}.thenByDescending{it.mediaPath}.thenByDescending{it.expiresAt})
            .take(GatewayRouting.MAX_ROUTES)
    }
    fun gatewayStatus():String {
        val routes=knownGatewayRoutes();val local=self()
        routes.firstOrNull{it.gatewayId==local}?.let{return "This phone has a verified Swarm internet connection · ${if(it.mediaPath)"media enabled"else"text only"}."}
        val route=routes.firstOrNull()?:return if(preferences().optString("relay","OFF")=="OFF")"Internet relay is off on this phone. Saved updates stay here until you share them." else "No recent Swarm internet gateway is reachable. Public updates remain saved."
        val age=now().epochSecond-route.issuedAt.epochSecond
        return "Gateway ${route.displayName} · ${route.hops} hop${if(route.hops==1)"" else "s"} · checked ${age.coerceAtLeast(0)}s ago${if(route.mediaPath)" · media route" else " · text route"}."
    }
    private fun acceptGatewayRoute(advertisement:JSONObject,viaPeer:String?):Boolean {
        val route=GatewayRouting.receive(advertisement,viaPeer,now())?:return false
        if(route.gatewayId==self())return false
        val id=route.gatewayId;val old=store.get("gateway-routes",id)?.let{GatewayRouting.fromStored(it,now())}
        if(old!=null && (route.sequence<old.sequence || route.sequence==old.sequence && route.hops>=old.hops && (!route.mediaPath || old.mediaPath)))return false
        val all=store.all("gateway-routes").mapNotNull{GatewayRouting.fromStored(it,now())}.filter{it.gatewayId!=id}
        if(all.size>=32){val worst=all.maxWithOrNull(compareBy<GatewayRoute>{it.hops}.thenBy{it.mediaPath}.thenBy{it.expiresAt})?:return false;if(route.hops>=worst.hops)return false;store.remove("gateway-routes",worst.gatewayId)}
        store.put("gateway-routes",id,GatewayRouting.stored(route));onChange();return true
    }
    private suspend fun announceGateways() {
        val routes=knownGatewayRoutes();if(routes.isEmpty())return
        val mediaLink=session.transport?.supportsFiles==true
        val maxBytes=session.negotiatedFrameBytes
        val batch=mutableListOf<JSONObject>()
        fun size(items:List<JSONObject>)=obj("v" to 1,"kind" to "COMMUNITY_GATEWAYS","id" to "00000000-0000-0000-0000-000000000000","value" to JSONArray(items)).toString().toByteArray().size
        suspend fun flush(){if(batch.isNotEmpty()){session.send("COMMUNITY_GATEWAYS",JSONArray(batch.toList()),priority=0);batch.clear()}}
        for(route in routes){
            val item=GatewayRouting.advertisement(route,route.mediaPath&&mediaLink)
            if(size(batch+item)>maxBytes)flush()
            if(size(listOf(item))<=maxBytes)batch.add(item)
        }
        flush()
    }
    fun records()=store.all("community").filter{Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"))>now()}
    fun helps()=store.all("community").filter{val b=it.getJSONObject("envelope").getJSONObject("body");b.getString("type")=="HELP" && Instant.parse(b.getString("expiresAt"))>now().minusSeconds(86400)}.groupBy{it.getJSONObject("envelope").getJSONObject("body").getString("objectId")}.values.map{rows->rows.maxBy{it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getInt("version")}}.filter{!it.optBoolean("hidden") && !chat.blocked(ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author")))}.map{JSONObject(it.toString()).put("expired",Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"))<=now())}
    fun reports()=records().filter{it.getJSONObject("envelope").getJSONObject("body").getString("type")=="REPORT" && !it.optBoolean("hidden") && !withdrawn(it.getString("id")) && !chat.blocked(ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author")))}.sortedByDescending{it.getJSONObject("envelope").getJSONObject("body").getString("createdAt")}
    fun publicPostVisible(id:String)=store.get("community",id)?.let{!it.optBoolean("hidden") && !withdrawn(id) && !chat.blocked(ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author")))}?:true
    fun offers(id:String)=records().filter{val b=it.getJSONObject("envelope").getJSONObject("body");b.getString("type")=="HELP_OFFER" && b.getString("objectId")==id && !chat.blocked(ChatProtocol.participant(b.getJSONObject("author")))}
    private fun withdrawn(id:String)=records().any{val b=it.getJSONObject("envelope").getJSONObject("body");b.getString("type")=="WITHDRAW" && b.getString("objectId")==id}
    private fun latestHelp(id:String)=helps().firstOrNull{!it.optBoolean("expired") && it.getJSONObject("envelope").getJSONObject("body").getString("objectId")==id}
    private fun prune(){val time=now();store.all("community").filter{val b=it.getJSONObject("envelope").getJSONObject("body");Instant.parse(b.getString("expiresAt"))<=if(b.getString("type")=="HELP")time.minusSeconds(86400)else time}.forEach{store.remove("community",it.getString("id"))}}
    private fun receiveUnlocked(event:JSONObject,hops:Int,fromServer:Boolean=false){
        val time=now();require(CommunityProtocol.valid(event,time)){"This participant statement could not be verified or has expired."}
        val b=event.getJSONObject("body");val id=b.getString("id");val objectId=b.getString("objectId");val author=ChatProtocol.participant(b.getJSONObject("author"));val digest=Protocol.hash(event)
        require(hops in 0..b.getInt("maxHops"))
        store.get("community",id)?.let{require(it.getString("hash")==digest){"A saved statement changed."};if(fromServer){it.put("serverSaved",true);store.put("community",id,it)};return}
        prune();require(store.all("community").size<500){"Nearby Help and Updates storage is full. Wait for old items to expire."}
        val p=b.getJSONObject("payload")
        when(b.getString("type")){
            "HELP"->{val previous=latestHelp(objectId);val version=p.getInt("version")
                if(previous!=null){val old=previous.getJSONObject("envelope").getJSONObject("body");require(author==ChatProtocol.participant(old.getJSONObject("author")) && previous.getString("hash")==p.getString("previousHash") && version==old.getJSONObject("payload").getInt("version")+1 && old.getJSONObject("payload").getJSONObject("help").getString("status") !in listOf("RESOLVED","CANCELLED") && Instant.parse(b.getString("expiresAt"))<=Instant.parse(old.getString("expiresAt"))){"Help changed or ended. Refresh before updating."}
                    val next=p.getJSONObject("help");val priorHelp=old.getJSONObject("payload").getJSONObject("help")
                    if(next.getString("status")=="RESPONDER_ASSIGNED" && !(priorHelp.getString("status")=="RESPONDER_ASSIGNED" && priorHelp.optString("responderId")==next.optString("responderId")))require(records().any{o->val ob=o.getJSONObject("envelope").getJSONObject("body");ob.getString("type")=="HELP_OFFER" && ob.getString("objectId")==objectId && ChatProtocol.participant(ob.getJSONObject("author"))==next.getString("responderId") && ob.getJSONObject("payload").getString("requestHash")==previous.getString("hash")}){"This responder has not offered help for the current request."}
                }else require(version==1){"Receive the original help request before its update."}
            }
            "HELP_OFFER"->{val root=latestHelp(objectId)?:error("Receive the help request before its response.");require(root.getString("hash")==p.getString("requestHash") && root.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("status")=="OPEN"){"This request is no longer open."};require(offers(objectId).none{ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author"))==author}){"Your offer is already saved."}}
            "WITHDRAW"->{val report=store.get("community",objectId)?:error("Receive the report before its withdrawal.");require(report.getString("hash")==p.getString("reportHash") && author==ChatProtocol.participant(report.getJSONObject("envelope").getJSONObject("body").getJSONObject("author"))){"Only the original author may withdraw this report."};report.put("hidden",true);store.put("community",objectId,report)}
            "FLAG"->{val target=records().firstOrNull{it.getString("hash")==p.getString("targetHash")}?:error("The reported statement is unavailable.");require(target.getJSONObject("envelope").getJSONObject("body").getString("objectId")==objectId)}
        }
        store.put("community",id,obj("id" to id,"envelope" to event,"hash" to digest,"hops" to hops,"owned" to (author==self()),"receivedNearbyAt" to time.toString(),"serverSaved" to fromServer));onChange()
    }
    suspend fun receive(event:JSONObject,hops:Int)=withContext(Dispatchers.IO){lock.withLock{receiveUnlocked(event,hops)}}
    private fun create(type:String,objectId:String?,payload:JSONObject,expiry:String?=null):JSONObject {
        val lifetime=if(type in listOf("HELP","HELP_OFFER"))7200L else 7*86400L
        // Inheriting a request's expiry must not exceed the offer's own bounded lifetime under clock skew.
        val observed=now();val time=if(expiry==null)observed else maxOf(observed,Instant.parse(expiry).minusSeconds(lifetime))
        val id=UUID.randomUUID().toString();val b=obj("v" to 1,"kind" to "COMMUNITY_EVENT","id" to id,"objectId" to (objectId?:id),"author" to chat.profile(),"createdAt" to time.toString(),"expiresAt" to (expiry?:time.plusSeconds(lifetime).toString()),"maxHops" to CommunityProtocol.MAX_HOPS,"type" to type,"payload" to payload,"payloadHash" to Protocol.hash(payload));return signed(b)
    }
    suspend fun saveHelp(help:JSONObject,existingId:String?=null)=withContext(Dispatchers.IO){lock.withLock{
        val previous=existingId?.let{latestHelp(it)?:error("This request expired or was removed.")};val time=now()
        if(previous==null){val active=helps().filter{!it.optBoolean("expired") && ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author"))==self() && it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("status") in listOf("OPEN","RESPONDER_ASSIGNED")}
            require(active.size<3){"Resolve or cancel an active request first."};require(active.none{it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("category")==help.getString("category")}){"You already have an active request in this category. Update it instead."}
            require(records().none{val b=it.getJSONObject("envelope").getJSONObject("body");b.getString("type")=="HELP" && ChatProtocol.participant(b.getJSONObject("author"))==self() && b.getJSONObject("payload").getInt("version")==1 && Instant.parse(b.getString("createdAt"))>time.minusSeconds(60)}){"Wait one minute before creating another request."}
        }else require(previous.optBoolean("owned")){"Only the requester may update this help request."}
        val old=previous?.getJSONObject("envelope")?.getJSONObject("body");val event=create("HELP",existingId,obj("version" to ((old?.getJSONObject("payload")?.getInt("version")?:0)+1),"previousHash" to previous?.getString("hash"),"help" to help),old?.getString("expiresAt"));receiveUnlocked(event,0);event.getJSONObject("body").getString("objectId")
    }.also{runCatching{announce()}}}
    suspend fun offer(id:String)=withContext(Dispatchers.IO){lock.withLock{val root=latestHelp(id)?:error("This request expired.");require(!root.optBoolean("owned")){"This is your request."};val event=create("HELP_OFFER",id,obj("requestHash" to root.getString("hash")),root.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"));receiveUnlocked(event,0)};runCatching{announce()}}
    suspend fun status(id:String,status:String,responder:String?=null){val root=latestHelp(id)?:error("This request expired.");val help=JSONObject(root.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").toString()).put("status",status).put("responderId",responder?:JSONObject.NULL);saveHelp(help,id)}
    suspend fun report(caption:String,area:String,warning:Boolean,derivative:FieldDerivative?=null)=withContext(Dispatchers.IO){lock.withLock{
        require(CommunityProtocol.publicText(caption,2000) && CommunityProtocol.publicText(area,80)){"Use a public landmark and remove contact details and precise coordinates."}
        val metadata=derivative?.let{d->val id=UUID.randomUUID().toString();session.savePublicBytes(id,d.bytes,d.mime);obj("id" to id,"mime" to d.mime,"size" to d.bytes.size,"hash" to Protocol.digest(d.bytes),"width" to d.width,"height" to d.height,"durationSeconds" to d.durationSeconds)}
        val event=create("REPORT",null,obj("caption" to caption,"area" to area,"contentWarning" to warning,"media" to metadata));receiveUnlocked(event,0);val id=event.getJSONObject("body").getString("id");store.get("community",id)!!.also{it.put("publishRequested",true);if(derivative!=null)it.put("thumbnail",Protocol.b64(derivative.thumbnail));store.put("community",id,it)};id
    }.also{runCatching{announce()}}}
    suspend fun withdraw(id:String)=withContext(Dispatchers.IO){lock.withLock{val original=store.get("community",id)?:error("Report unavailable.");require(original.optBoolean("owned"));receiveUnlocked(create("WITHDRAW",id,obj("reportHash" to original.getString("hash"))),0)};runCatching{announce()}}
    suspend fun flag(id:String,reason:String)=withContext(Dispatchers.IO){lock.withLock{val target=store.get("community",id)?:error("Statement unavailable.");receiveUnlocked(create("FLAG",target.getJSONObject("envelope").getJSONObject("body").getString("objectId"),obj("targetHash" to target.getString("hash"),"reason" to reason)),0)};runCatching{sync()}}
    fun fileAllowed(id:String,hash:String)=reports().any{r->r.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").optJSONObject("media")?.let{it.getString("id")==id && it.getString("hash")==hash}==true}
    suspend fun completeFile(id:String)=withContext(Dispatchers.IO){val file=store.get("attachments",id)?:return@withContext;val report=reports().firstOrNull{r->r.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").optJSONObject("media")?.optString("id")==id}?:return@withContext;val media=report.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("media");require(file.getString("hash")==media.getString("hash") && file.getInt("size")==media.getInt("size") && file.getString("mime")==media.getString("mime")){"Report media does not match its signed description."};file.put("publicOnly",true);store.put("attachments",id,file)
        runCatching{val directory=java.io.File(context.cacheDir,"field-processing").apply{mkdirs()};val temporary=java.io.File.createTempFile("preview-",if(media.getString("mime")=="image/jpeg")".jpg"else".mp4",directory);try{temporary.writeBytes(session.readSavedBytes(id));val preview=FieldMedia.prepare(context,android.net.Uri.fromFile(temporary),media.getString("mime")=="video/mp4",media.getString("mime").startsWith("audio/"));report.put("thumbnail",Protocol.b64(preview.thumbnail));store.put("community",report.getString("id"),report)}finally{temporary.delete()}};onChange();if(gatewayAllowed(true))runCatching{sync()}
    }
    suspend fun shareMedia(reportId:String){val r=store.get("community",reportId)?:error("Report unavailable.");require(!withdrawn(reportId) && !r.optBoolean("hidden"));val m=r.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").optJSONObject("media")?:error("This is a text report.");val f=store.get("attachments",m.getString("id"))?:error("Receive the report’s media first.");session.offerSaved(f)}
    private fun ordered(rows:List<JSONObject>):List<JSONObject>{
        val pending=rows.sortedWith(compareBy<JSONObject>{it.getJSONObject("envelope").getJSONObject("body").getString("createdAt")}.thenBy{it.getString("id")}).toMutableList();val output=mutableListOf<JSONObject>()
        while(pending.isNotEmpty()){val hashes=pending.map{it.getString("hash")}.toSet();val next=pending.firstOrNull{r->val b=r.getJSONObject("envelope").getJSONObject("body");val p=b.getJSONObject("payload");val offerPending=if(b.getString("type")=="HELP" && p.getJSONObject("help").getString("status")=="RESPONDER_ASSIGNED")pending.any{o->val ob=o.getJSONObject("envelope").getJSONObject("body");ob.getString("type")=="HELP_OFFER" && ob.getString("objectId")==b.getString("objectId") && ChatProtocol.participant(ob.getJSONObject("author"))==p.getJSONObject("help").getString("responderId") && ob.getJSONObject("payload").getString("requestHash")==p.optString("previousHash")}else false;!offerPending && listOf("previousHash","requestHash","reportHash","targetHash").none{p.optString(it,"") in hashes}}?:error("Public statement dependencies conflict.");output.add(next);pending.remove(next)};return output
    }
    suspend fun announce()=withContext(Dispatchers.IO){
        if(!session.confirmed)return@withContext
        if(generation!=session.connectionGeneration){generation=session.connectionGeneration;requested.clear()}
        val rows=ordered(records().filter{!it.optBoolean("hidden") && it.getInt("hops")<it.getJSONObject("envelope").getJSONObject("body").getInt("maxHops") && it.getJSONObject("envelope").getJSONObject("body").getString("type")!="FLAG" && !chat.blocked(ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author")))});advertised=rows.map{it.getString("id")}.toSet()
        for(batch in rows.chunked(100))session.send("COMMUNITY_INVENTORY",JSONArray(batch.map{obj("id" to it.getString("id"),"hash" to it.getString("hash"),"receiptHash" to (it.optJSONObject("receipt")?.let{r->Protocol.hash(r)}?:""))}),priority=1)
        announceGateways()
    }
    suspend fun frame(frame:JSONObject,receivedGeneration:Long)=withContext(Dispatchers.IO){
        if(receivedGeneration!=session.connectionGeneration)return@withContext
        when(frame.getString("kind")){
            "COMMUNITY_GATEWAYS"->{val entries=frame.getJSONArray("value");require(entries.length()<=GatewayRouting.MAX_ROUTES);val via=chat.peer?.let{ChatProtocol.participant(it)};if(via!=null){var changed=false;for(entry in entries.objects())changed=acceptGatewayRoute(entry,via)||changed;if(changed)announceGateways()}}
            "COMMUNITY_INVENTORY"->{val rows=frame.getJSONArray("value").objects();require(rows.size<=100);val ids=rows.filter{r->r.exact("id","hash","receiptHash");UUID.fromString(r.getString("id"));require(r.getString("hash").matches(Regex("[a-f0-9]{64}")));val held=store.get("community",r.getString("id"));held==null || held.getString("hash")!=r.getString("hash") || (held.optJSONObject("receipt")?.let{Protocol.hash(it)}?:"")!=r.optString("receiptHash","")}.map{it.getString("id")};session.send("COMMUNITY_WANT",JSONArray(ids),priority=1)}
            "COMMUNITY_WANT"->{val ids=frame.getJSONArray("value").strings();require(ids.size<=100);for(id in ids){if(id !in advertised)continue;val row=store.get("community",id)?:continue;val b=row.getJSONObject("envelope").getJSONObject("body");if(row.optBoolean("hidden") || row.getInt("hops")>=b.getInt("maxHops") || Instant.parse(b.getString("expiresAt"))<=now())continue;session.send("COMMUNITY_EVENT",obj("envelope" to row.getJSONObject("envelope"),"hops" to row.getInt("hops")+1,"receipt" to row.optJSONObject("receipt")),priority=if(b.getString("type")=="HELP" && b.getJSONObject("payload").getJSONObject("help").getString("priority")=="URGENT")0 else 1)}}
            "COMMUNITY_EVENT"->{val value=frame.getJSONObject("value");value.exact("envelope","hops","receipt");val event=value.getJSONObject("envelope");receive(event,value.getInt("hops"));value.optJSONObject("receipt")?.let{acceptReceipt(it)};session.send("COMMUNITY_ACK",obj("id" to event.getJSONObject("body").getString("id"),"hash" to Protocol.hash(event)),priority=1);if(gatewayAllowed())runCatching{sync()}}
            "COMMUNITY_ACK"->{val v=frame.getJSONObject("value");v.exact("id","hash");if(v.getString("id") in advertised)store.get("community",v.getString("id"))?.takeIf{it.getString("hash")==v.getString("hash")}?.let{it.put("sharedAt",now().toString());store.put("community",it.getString("id"),it);onChange()}}
        }
    }
    private fun acceptReceipt(receipt:JSONObject){val id=receipt.getJSONObject("body").getString("eventId");val record=store.get("community",id)?:return;val keys=repository.configuration?.getJSONObject("body")?.getJSONArray("receiptKeys")?:return
        require(Protocol.validReceipt(receipt,record.getJSONObject("envelope"),keys,now())){"Public receipt could not be verified."}
        val prior=record.optJSONObject("receipt");if(prior!=null && Instant.parse(prior.getJSONObject("body").getString("recordedAt"))>Instant.parse(receipt.getJSONObject("body").getString("recordedAt")))return
        val status=receipt.getJSONObject("body").getString("status")
        record.put("receipt",receipt).put("serverSaved",true);if(status in listOf("INVALIDATED","REJECTED"))record.put("hidden",true);store.put("community",id,record);onChange()
    }
    private fun preferences()=store.get("preferences","local")?:obj("relay" to "OFF","mediaRelay" to false,"dailyLimitMB" to 500,"batteryMinimum" to 20)
    private fun gatewayAllowed(media:Boolean=false):Boolean{val p=preferences();val cm=context.getSystemService(ConnectivityManager::class.java);val network=cm.getNetworkCapabilities(cm.activeNetwork)?:return false;return CommunityRelayPolicy.mayForward(p.optString("relay","OFF"),p.optBoolean("mediaRelay"),media,network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))}
    private fun reserve(bytes:Int,media:Boolean=false){val p=preferences();val battery=context.getSystemService(BatteryManager::class.java);require(CommunityRelayPolicy.resourcesReady(battery.isCharging,battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),p.optInt("batteryMinimum",20),media,if(media)StatFs(context.filesDir.path).availableBytes else 0)){"Public relay paused. Check the phone's battery and free media storage."}
        synchronized(store){val day=LocalDate.now(ZoneOffset.UTC).toString();val old=store.get("relay-usage",day)?:obj("day" to day,"bytes" to 0);old.put("bytes",CommunityRelayPolicy.nextReservation(old.getLong("bytes"),p.optInt("dailyLimitMB",p.optInt("dailyLimitMiB",500)).coerceIn(500,5000),bytes));store.put("relay-usage",day,old);store.all("relay-usage").filter{it.optString("day")<LocalDate.now(ZoneOffset.UTC).minusDays(7).toString()}.forEach{store.remove("relay-usage",it.getString("day"))}}
    }
    suspend fun sync()=withContext(Dispatchers.IO){syncLock.withLock{
        if(repository.configuration==null)return@withLock
        val rows=ordered(records());val gateway=gatewayAllowed();val eligible=rows.filter{it.optBoolean("owned") || gateway && !chat.blocked(ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author")))};val selectedArea=store.get("community-meta","area")?.optString("area")?:"";if(eligible.isEmpty() && selectedArea.isBlank() && (preferences().optString("relay","OFF")=="OFF" || !gateway))return@withLock
        val pending=eligible.filter{!it.optBoolean("serverSaved") && it.optString("rejectedReason").isBlank()}.take(20)
        if(pending.isEmpty() && selectedArea.isBlank()){
            JSONObject(repository.api("/public/config"));store.put("community-meta","gateway-check",obj("checkedAt" to now().toString()));onChange();runCatching{announce()};return@withLock
        }
        val cursor=store.get("community-meta","receipt-cursor")?.optInt("offset",0)?:0;val receiptIds=if(rows.isEmpty())emptyList()else (0 until minOf(rows.size,100)).map{rows[(cursor+it)%rows.size].getString("id")}
        val areas=(listOf(selectedArea).filter{it.isNotBlank()}+helps().map{it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("area")}).distinct().take(10)
        val b=obj("v" to 1,"kind" to "COMMUNITY_SYNC","id" to UUID.randomUUID().toString(),"profile" to chat.profile(),"issuedAt" to now().toString(),"areas" to JSONArray(areas),"known" to JSONArray(rows.map{it.getString("id")}.takeLast(500)),"receiptIds" to JSONArray(receiptIds),"events" to JSONArray(pending.map{it.getJSONObject("envelope")}))
        val request=signed(b);reserve(request.toString().toByteArray().size+262144);val response=JSONObject(repository.api("/community/sync",request,responseLimit=262144))
        store.put("community-meta","gateway-check",obj("checkedAt" to now().toString()))
        lock.withLock{response.getJSONArray("accepted").strings().forEach{id->store.get("community",id)?.let{it.put("serverSaved",true);it.remove("waitingReason");store.put("community",id,it)}};response.getJSONArray("rejected").objects().forEach{r->store.get("community",r.getString("id"))?.let{it.put(if(r.optBoolean("retryable"))"waitingReason"else"rejectedReason",r.getString("reason"));store.put("community",r.getString("id"),it)}};ordered(response.getJSONArray("events").objects().map{obj("id" to it.getJSONObject("body").getString("id"),"hash" to Protocol.hash(it),"envelope" to it)}).forEach{receiveUnlocked(it.getJSONObject("envelope"),0,true)};response.getJSONArray("receipts").objects().forEach{acceptReceipt(it)};store.put("community-meta","receipt-cursor",obj("offset" to ((cursor+100)%maxOf(1,rows.size))))}
        for(row in eligible.filter{val b0=it.getJSONObject("envelope").getJSONObject("body");b0.getString("type")=="REPORT" && !it.optBoolean("hidden") && !withdrawn(it.getString("id")) && (it.optBoolean("owned") || gatewayAllowed(true))}.take(2)){
            if(!store.get("community",row.getString("id"))!!.optBoolean("serverSaved"))continue;val m=row.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").optJSONObject("media")?:continue
            if(row.optBoolean("mediaOnline"))continue;val file=store.get("attachments",m.getString("id"))?:continue;if(!file.optBoolean("complete"))continue
            val raw=session.readSavedBytes(m.getString("id"));require(Protocol.digest(raw)==m.getString("hash"));var missing:List<Int>?=null;var rounds=0
            while(rounds++<350){if(!row.optBoolean("owned") && !gatewayAllowed(true))break;val chunks=missing?.take(6)?:emptyList();val mb=obj("v" to 1,"kind" to "COMMUNITY_MEDIA","profile" to chat.profile(),"issuedAt" to now().toString(),"reportId" to row.getString("id"),"chunks" to JSONArray(chunks.map{i->obj("index" to i,"data" to Protocol.b64(raw.copyOfRange(i*8192,minOf(raw.size,(i+1)*8192))))}));val req=signed(mb);reserve(req.toString().toByteArray().size+32768,true);val result=JSONObject(repository.api("/community/attachment",req,responseLimit=32768));if(result.optBoolean("invalidHash"))error("Public media hash failed. Retry after reviewing the source.");if(result.getBoolean("complete")){store.get("community",row.getString("id"))!!.let{it.put("mediaOnline",true);store.put("community",row.getString("id"),it)};break};missing=result.getJSONArray("missing").let{a->(0 until a.length()).map{a.getInt(it)}};require(missing.all{it in 0 until (raw.size+8191)/8192} && missing.distinct().size==missing.size)}
        };onChange();runCatching{announce()}
    }}
}

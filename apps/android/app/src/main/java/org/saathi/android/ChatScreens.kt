package org.saathi.android

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.json.JSONObject
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

fun chatPreview(message:JSONObject?):String {
    if(message==null)return "Start a conversation"
    val payload=message.getJSONObject("payload")
    val format=message.optJSONObject("envelope")?.optJSONObject("body")?.optString("format")
    return payload.optString("text").ifBlank {
        payload.optJSONObject("reference")?.optString("title") ?: when(format) {
            "PHOTO" -> "Photo"
            "VIDEO" -> "Video"
            "VOICE" -> "Voice note"
            "FILE" -> "Attachment"
            else -> "Message"
        }
    }
}

private fun admissionState(conversation:JSONObject):String?=when(conversation.optString("joinStatus")){
    "REJECTED"->"The owner declined your join request."
    "EXPIRED"->"Your join request expired. Ask for a new invitation."
    else->null
}
fun chatStatus(message:JSONObject)=when {
    message.optBoolean("attention")->"Needs attention"
    message.has("readAt")->"Read"
    message.has("deliveredAt")->"Delivered"
    message.has("sentNearby")->"Sent nearby · waiting for receipt"
    message.optBoolean("serverSaved")->"Uploaded · waiting for receipt"
    else->"Saved · Waiting for connection"
}
/** First letter for people; a lock for private (invite-only) groups and # for open ones, as on iPhone. */
@Composable private fun Avatar(name:String,channel:Boolean=false,locked:Boolean=false,size:Dp=44.dp) {
    Surface(Modifier.size(size),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment=Alignment.Center) {if(channel)Icon(if(locked)Icons.Outlined.Lock else Icons.Outlined.Tag,if(locked)"Private group" else null,Modifier.size(22.dp)) else Text(name.take(1).uppercase(),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
    }
}
@Composable fun ChatsScreen(vm:SaathiViewModel,state:AppState,open:(String)->Unit,nearby:()->Unit,create:()->Unit,modifier:Modifier) {
    if(!BuildConfig.CHAT_ENABLED){Column(modifier.padding(20.dp)){EmptyState("Chat is being tested","Private chats are available in development and QA builds while security review is pending.",Icons.Outlined.ChatBubbleOutline)};return}
    var search by rememberSaveable {mutableStateOf("")};var chatMenu by remember{mutableStateOf<String?>(null)};var deletingChat by remember{mutableStateOf<JSONObject?>(null)}
    val ordered=state.conversations.filter{state.listed(it)}.sortedByDescending { c->state.shownMessages(c.getString("id")).lastOrNull()?.body()?.getString("createdAt")?: "" }
    val matches={c:JSONObject->c.getString("title").contains(search,true) || state.shownMessages(c.getString("id")).any {chatPreview(it).contains(search,true)}}
    val pinned=state.pinnedChats
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item {Row(verticalAlignment=Alignment.CenterVertically){Text("Chats",Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium);IconButton(onClick=create){Icon(Icons.Outlined.Add,"New group")};IconButton(onClick={vm.syncChats()},enabled=!state.busy){Icon(Icons.Outlined.Sync,"Check chat delivery")}}}
        item {OutlinedTextField(search,{search=it.take(100)},Modifier.fillMaxWidth(),label={Text("Search saved chats")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true)}
        // Pinned chats stay on top, most recently pinned first.
        for((label,conversations) in listOf("Pinned" to ordered.filter{it.getString("id") in pinned}.sortedByDescending{pinned[it.getString("id")]},
            "Direct messages" to ordered.filter{it.getString("type")=="DIRECT"&&it.getString("id") !in pinned},"Channels" to ordered.filter{it.getString("type")=="CHANNEL"&&it.getString("id") !in pinned}).map{(l,list)->l to list.filter(matches)}){
            if(conversations.isNotEmpty())item {Text(label,Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleMedium)}
            items(conversations,key={it.getString("id")}){c->
                val type=c.getString("type");val cid=c.getString("id")
                val messages=state.shownMessages(cid)
                val last=messages.lastOrNull()
                val unread=messages.count {!it.optBoolean("owned")&&!it.optBoolean("readLocally")}
                Column {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick={open(cid)},onLongClick={chatMenu=cid},onLongClickLabel="Chat options").padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Avatar(c.getString("title"),type=="CHANNEL",state.isPrivate(cid))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                            Text((if(type=="CHANNEL")"# " else "")+c.getString("title"),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(admissionState(c)?:if(c.optBoolean("pendingJoin"))"Waiting for the channel owner" else if(!c.optBoolean("joined"))"Left or removed · Saved history" else if(last!=null&&last.getString("id") in state.deletedForEveryone())"This message was deleted" else chatPreview(last),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(last?.optBoolean("owned")==true)Text(chatStatus(last),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)){
                            if(cid in pinned)Icon(Icons.Outlined.PushPin,"Pinned",Modifier.size(18.dp))
                            if(c.optBoolean("muted"))Icon(Icons.Outlined.NotificationsOff,"Muted",Modifier.size(18.dp))
                            if(unread>0)Badge {Text(unread.coerceAtMost(99).toString())}
                            last?.let {Text(timeLabel(it.getJSONObject("envelope").getJSONObject("body").getString("createdAt")),style=MaterialTheme.typography.labelSmall)}
                        }
                    };HorizontalDivider()
                    DropdownMenu(chatMenu==cid,{chatMenu=null}){
                        DropdownMenuItem(text={Text(if(cid in pinned)"Unpin chat" else "Pin chat")},leadingIcon={Icon(Icons.Outlined.PushPin,null)},onClick={chatMenu=null;vm.pinChat(cid)})
                        DropdownMenuItem(text={Text("Delete chat",color=MaterialTheme.colorScheme.error)},leadingIcon={Icon(Icons.Outlined.Delete,null,tint=MaterialTheme.colorScheme.error)},onClick={chatMenu=null;deletingChat=c})
                    }
                }
            }
        }
        if(ordered.isEmpty())item {EmptyState("People first. Conversations that stay.","Meet someone in Nearby to start a direct message, or create a channel for your group.",Icons.Outlined.ChatBubbleOutline);Button(onClick=nearby){Text("Find people nearby")}}
        if(search.isNotBlank() && ordered.none(matches))item {Text("No matching saved conversations. Search stays on this phone.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Text("Direct messages and invite-only channels are encrypted between participants. Open channels are readable by their members. Chatting does not verify a relief volunteer.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    deletingChat?.let{c->AlertDialog(onDismissRequest={deletingChat=null},title={Text("Delete this chat?")},text={Text(deleteChatText(c.getString("type")=="CHANNEL"))},
        confirmButton={TextButton(onClick={vm.deleteChat(c.getString("id"));deletingChat=null}){Text("Delete chat",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={deletingChat=null}){Text("Cancel")}})}
}
fun deleteChatText(channel:Boolean)="Deletes this chat's messages and downloaded files from this phone."+(if(channel)" You stay in the group." else "")+" New messages will bring it back."
@Composable fun SwarmFormation(connected:Boolean) {
    val context=LocalContext.current
    val reduced=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
    val progress by animateFloatAsState(if(connected)1f else 0f,animationSpec=tween(if(reduced)0 else 420,easing=FastOutSlowInEasing),label="Swarm formation")
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(44.dp)){
        val left=Offset(size.width*.32f,size.height/2);val right=Offset(size.width*.68f,size.height/2)
        drawCircle(color,6.dp.toPx(),left);drawCircle(color.copy(alpha=if(connected)1f else .35f),6.dp.toPx(),right)
        if(progress>0f)drawLine(color.copy(alpha=.6f),left,Offset(left.x+(right.x-left.x)*progress,left.y),2.dp.toPx())
    }
}
@Composable fun NearbyPeopleScreen(vm:SaathiViewModel,state:AppState,discover:(Boolean)->Unit,open:(String)->Unit,create:()->Unit,invite:()->Unit,connection:()->Unit,modifier:Modifier) {
    if(!BuildConfig.CHAT_ENABLED){Column(modifier.padding(20.dp)){EmptyState("Nearby connections","The communication preview is being tested. Existing nearby relief sharing remains available.",Icons.Outlined.Groups);Button(onClick=connection){Text("Connection options")}};return}
    val peer=state.chatPeer?.takeIf {!(ChatProtocol.participant(it) in state.chatBlocks)}
    var reportPerson by remember{mutableStateOf<String?>(null)}
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item {Heading("Nearby","Meet people around you. Keep your conversations when the connection changes.")}
        item {Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            SwarmFormation(peer!=null);Text(if(peer!=null)"You're in a Swarm" else "No nearby Swarm yet",style=MaterialTheme.typography.titleLarge)
            Text(if(peer!=null)"1 person reachable nearby" else "Enable Nearby to find compatible people and channels around you.",style=MaterialTheme.typography.bodyMedium)
            if(peer==null)Button(onClick={discover(false)},enabled=!state.busy&&vm.nearby.available){Text("Search nearby")}
            Text(if(peer!=null)"This connection may carry saved messages for shared channels." else "Your chosen display name appears to nearby devices while you search. Swarm phones connect automatically.",style=MaterialTheme.typography.bodySmall)
            Text("Android Nearby can connect directly with Wi-Fi and Bluetooth on. No internet, router or manual hotspot is needed for messages and saved media.",style=MaterialTheme.typography.bodySmall)
        }}}
        item {HotspotCard(vm,state)}
        item {Text("People",style=MaterialTheme.typography.titleLarge)}
        if(peer!=null)item {Column{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(peer.getJSONObject("body").getString("name"));Column(Modifier.weight(1f)){Text(peer.getJSONObject("body").getString("name"),style=MaterialTheme.typography.titleMedium);Text("Nearby participant",style=MaterialTheme.typography.bodySmall)};Button(onClick={vm.openChat(peer,open)},enabled=!state.busy){Text("Message")}};TextButton({reportPerson=ChatProtocol.participant(peer)}){Text("Report person")}}}
        items(state.peers.entries.toList(),key={it.key}){item->ListItem(headlineContent={Text(item.value)},supportingContent={Text("Connects automatically")},leadingContent={Icon(Icons.Outlined.PersonOutline,null)},trailingContent={TextButton(onClick={vm.connect(item.key)},enabled=!state.busy){Text("Connect")}})}
        if(peer==null && state.peers.isEmpty())item {Text(state.nearbyStatus,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {HorizontalDivider();Row(verticalAlignment=Alignment.CenterVertically){Text("Channels nearby",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);IconButton(onClick=create){Icon(Icons.Outlined.Add,"Create channel")}}}
        items(state.nearbyChannels,key={it.getString("id")}){channel->
            val conversation=state.conversations.firstOrNull {it.getString("id")==channel.getString("id")}
            ListItem(headlineContent={Text("# "+channel.getString("name"))},supportingContent={Text(if(conversation?.optBoolean("pendingJoin")==true)"Waiting for the owner" else "Open nearby channel")},leadingContent={Icon(Icons.Outlined.Tag,null)},trailingContent={TextButton(onClick={if(conversation?.optBoolean("joined")==true)open(channel.getString("id")) else vm.joinChannel(channel.getString("id"))},enabled=!state.busy&&conversation?.optBoolean("pendingJoin")!=true){Text(if(conversation?.optBoolean("joined")==true)"Open" else "Join")}})
        }
        if(state.nearbyChannels.isEmpty())item {Text("Open channels appear after you connect with a compatible participant. Invite-only channels stay hidden.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=create){Text("Create channel")};TextButton(onClick=invite){Icon(Icons.Outlined.QrCode2,null);Spacer(Modifier.width(6.dp));Text("Join with invite")}}}
        item {HorizontalDivider();TextButton(onClick=connection){Icon(Icons.Outlined.Link,null);Spacer(Modifier.width(8.dp));Text("Connection options and earlier messages")};Text("Local Wi-Fi pairing and the earlier browser exchange remain available here. Only confirmed people count as reachable.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    reportPerson?.let{person->ReportReason({reason->vm.reportPerson(person,when(reason){"HARASSMENT"->"ABUSE";"UNSAFE"->"SAFETY";else->reason});reportPerson=null},{reportPerson=null})}
}
private fun AppState.channelPolicy(id:String)=chatPolicies.firstOrNull{it.getJSONObject("body").getString("id")==id}
private fun AppState.isPrivate(id:String)=channelPolicy(id)?.getJSONObject("body")?.optString("visibility")=="INVITE"
private fun AppState.channelCapabilities(id:String)=channelPolicy(id)?.let{policy->chatProfile?.let{ChannelGovernance.capabilities(policy,ChatProtocol.participant(it))}}
private fun AppState.channelActions(id:String)=chatActions.filter{it.getJSONObject("envelope").getJSONObject("body").getString("channelId")==id && !it.optBoolean("rejected")}.sortedWith(compareBy<JSONObject>{it.getJSONObject("envelope").getJSONObject("body").getString("issuedAt")}.thenBy{it.getString("id")})
private fun AppState.moderationFlag(id:String,target:String,on:String,off:String,key:String):Boolean {
    val latest=channelActions(id).lastOrNull{it.getJSONObject("envelope").getJSONObject("body").let{b->b.getString("targetId")==target && b.getString("action") in listOf(on,off)}}
    return latest?.getJSONObject("envelope")?.getJSONObject("body")?.getString("action")?.let{it==on} ?: (channelPolicy(id)?.getJSONObject("body")?.optJSONObject("moderation")?.optJSONArray(key)?.strings()?.contains(target)==true)
}
private fun AppState.channelReports(id:String)=chatReportInbox.filter{report->report.getString("channelId")==id && !channelActions(id).any{a->val b=a.getJSONObject("envelope").getJSONObject("body");b.getString("action")=="REVIEW_REPORT" && b.getString("targetId")==report.getString("messageId") && b.getString("issuedAt")>=report.getString("createdAt")}}
private val actionLabels=mapOf("SET_ROLE" to "changed the role of","REMOVE" to "removed","BAN" to "banned","UNBAN" to "unbanned","APPROVE_JOIN" to "approved","REJECT_JOIN" to "declined",
    "HIDE_MESSAGE" to "removed a message","RESTORE_MESSAGE" to "restored a message","REVIEW_REPORT" to "reviewed a report","LOCK_THREAD" to "locked replies","UNLOCK_THREAD" to "unlocked replies")
private fun AppState.messageHidden(id:String,message:String)=moderationFlag(id,message,"HIDE_MESSAGE","RESTORE_MESSAGE","hiddenMessages")

private fun JSONObject.body()=getJSONObject("envelope").getJSONObject("body")
private fun JSONObject.authorId()=ChatProtocol.participant(body().getJSONObject("author"))
/** Ids deleted for everyone: a delete marker from the message's own author (or one whose original has not arrived). */
fun AppState.deletedForEveryone():Set<String>{
    val byId=chatMessages.associateBy{it.getString("id")}
    return chatMessages.mapNotNull{m->m.getJSONObject("payload").optString("deletes").ifEmpty{null}?.takeIf{t->byId[t]?.let{it.authorId()==m.authorId()}?:true}}.toSet()
}
/** Telegram-style threads: a reply belongs to the thread of the top-level message it (transitively) answers. */
fun threadRoot(m:JSONObject,byId:Map<String,JSONObject>):String?{
    m.body().optString("threadRootId").ifEmpty{null}?.let{return it}
    var target=m.getJSONObject("payload").optString("replyTo").ifEmpty{null}?:return null
    repeat(20){
        val t=byId[target]?:return target
        val up=t.body().optString("threadRootId").ifEmpty{null}?:t.getJSONObject("payload").optString("replyTo").ifEmpty{null}?:return target
        target=up
    }
    return target
}
/** Pinned messages still shown, oldest first: deleted or cleared ones drop out. Pins stay on this phone. */
fun AppState.pins(conversationId:String):List<JSONObject>{val gone=deletedForEveryone();val ids=chatPins[conversationId].orEmpty();return shownMessages(conversationId).filter{it.getString("id") in ids && it.getString("id") !in gone}}
/** Pin or unpin one message; a chat keeps at most 3 pins. */
fun togglePin(pinned:List<String>,id:String):List<String>{if(id in pinned)return pinned-id;require(pinned.size<3){"Up to 3 pinned messages. Unpin one first."};return pinned+id}
/** A deleted chat stays out of Chats until a new message arrives. */
fun AppState.listed(c:JSONObject)=!c.optBoolean("deletedLocally") || shownMessages(c.getString("id")).isNotEmpty()
/** What a conversation shows, oldest first: no delete markers, nothing deleted on this phone. */
fun AppState.shownMessages(conversationId:String)=chatMessages.filter{it.body().getString("conversationId")==conversationId && !it.getJSONObject("payload").has("deletes") && it.getString("id") !in chatDeleted}
    .sortedWith(compareBy<JSONObject>{it.body().getString("createdAt")}.thenBy{it.getString("id")})

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConversationScreen(vm:SaathiViewModel,state:AppState,id:String,call:(Boolean,Boolean)->Unit,record:()->Unit,createNeed:(JSONObject)->Unit,openNeed:(String)->Unit,modifier:Modifier,onVisibleMessages:suspend (List<String>)->Unit={vm.readChat(id,it)},back:(()->Unit)?=null) {
    val conversation=state.conversations.firstOrNull {it.getString("id")==id}
    if(conversation==null){EmptyState("Conversation unavailable","Return to Chats and try again.",Icons.Outlined.ChatBubbleOutline);return}
    val channel=conversation.getString("type")=="CHANNEL"
    var info by rememberSaveable(id){mutableStateOf(false)};var thread by rememberSaveable(id){mutableStateOf<String?>(null)}
    androidx.activity.compose.BackHandler(info){info=false}
    androidx.activity.compose.BackHandler(!info&&thread!=null){thread=null}
    if(info){if(channel)GroupInfoScreen(vm,state,id,{info=false},modifier) else ContactInfoScreen(vm,state,conversation,{info=false},modifier);return}
    var text by remember(id){mutableStateOf("")}; LaunchedEffect(id){val saved=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.repository.store.get("chat-drafts",id)?.optString("text")?:""};if(text.isEmpty())text=saved}
    var replyTo by remember(id){mutableStateOf<JSONObject?>(null)};var forwarding by remember{mutableStateOf<String?>(null)}
    var reportId by remember{mutableStateOf<String?>(null)};var helpMessage by remember{mutableStateOf<JSONObject?>(null)};var deleting by remember{mutableStateOf<JSONObject?>(null)}
    var exportId by rememberSaveable {mutableStateOf("")};var viewing by remember{mutableStateOf<String?>(null)}
    var highlight by remember{mutableStateOf<String?>(null)};var jumpTo by remember{mutableStateOf<String?>(null)}
    val transcript=key(id,thread){rememberLazyListState()};val uiScope=rememberCoroutineScope()
    var followLatest by remember(id){mutableStateOf(true)}
    val visibleMessages by remember(transcript){derivedStateOf{transcript.layoutInfo.visibleItemsInfo.mapNotNull{it.key as? String}}}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)vm.exportChatAttachment(exportId,uri)}
    val all=state.shownMessages(id);val gone=state.deletedForEveryone();val pins=state.pins(id);val pinnedIds=pins.map{it.getString("id")}.toSet();val byId=state.chatMessages.associateBy{it.getString("id")}
    val roots=all.associate{it.getString("id") to threadRoot(it,byId)};val replyCounts=roots.values.filterNotNull().groupingBy{it}.eachCount()
    // Admin-post groups keep replies inside each post's thread; free chats and DMs also show replies inline with a quote.
    val announce=channel&&state.channelPolicy(id)?.getJSONObject("body")?.optJSONObject("settings")?.optString("mode")=="ANNOUNCEMENT"
    val messages=if(thread==null)all.filter{!it.body().has("threadRootId")} else all.filter{it.getString("id")==thread||roots[it.getString("id")]==thread}
    val caps=if(channel)state.channelCapabilities(id)else null
    val canReplyThreads=conversation.optBoolean("joined")&&(!channel||caps?.optBoolean(if(announce)"canReplyInThreads" else "canPostTopLevel")==true)
    val canPost=if(thread!=null)canReplyThreads else conversation.optBoolean("joined") && (!channel || caps?.optBoolean("canPostTopLevel")==true)
    val threadRootForSend=if(announce)thread else null
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.attachChat(id,uri,threadRootForSend)}
    val callReady=!channel&&state.media&&state.chatPeer?.let {ChatProtocol.participant(it)}==conversation.optString("peerId")&&!(conversation.optString("peerId") in state.chatBlocks)
    val peerId=state.chatPeer?.let{ChatProtocol.participant(it)}
    val members=state.channelPolicy(id)?.getJSONObject("body")?.getJSONArray("members")?.objects()?.filter{it.isNull("removedAt")}.orEmpty()
    val here=peerId!=null && if(channel)members.any{ChatProtocol.participant(it.getJSONObject("profile"))==peerId} else peerId==conversation.optString("peerId")
    LaunchedEffect(id,transcript){
        snapshotFlow { Triple(transcript.isScrollInProgress,transcript.firstVisibleItemIndex,transcript.firstVisibleItemScrollOffset) }.distinctUntilChanged().collect{(scrolling,index,offset)->if(scrolling)followLatest=index==0&&offset==0}
    }
    LaunchedEffect(id,messages.lastOrNull()?.getString("id")){
        if(messages.isNotEmpty()&&(followLatest||messages.last().optBoolean("owned"))){transcript.scrollToItem(0);followLatest=true}
    }
    LaunchedEffect(id,visibleMessages){if(visibleMessages.isNotEmpty())onVisibleMessages(visibleMessages)}
    // Tapping a quote scrolls to the quoted message (opening its thread when it lives in one) and flashes it.
    fun jump(target:String){if(messages.none{it.getString("id")==target}&&all.any{it.getString("id")==target}){thread=roots[target]};jumpTo=target}
    LaunchedEffect(jumpTo,messages.size,thread){
        val target=jumpTo?:return@LaunchedEffect;val index=messages.asReversed().indexOfFirst{it.getString("id")==target}
        if(index<0){if(all.none{it.getString("id")==target}){vm.notice("The quoted message is not on this phone.");jumpTo=null};return@LaunchedEffect}
        followLatest=false;transcript.animateScrollToItem(index);highlight=target;jumpTo=null;kotlinx.coroutines.delay(1500);highlight=null
    }
    DisposableEffect(id){vm.enterConversation(id);onDispose{vm.cancelVoice();vm.leaveConversation(id)}}
    // Hold-to-talk: a voice clip that arrives while this chat is open plays by itself, like a walkie-talkie.
    val newestVoice=messages.lastOrNull{!it.optBoolean("owned")&&it.body().getString("format")=="VOICE"&&state.files.any{f->f.getString("id")==it.getJSONObject("payload").optJSONObject("attachment")?.optString("id")&&f.optBoolean("complete")}}?.getString("id")
    var heardVoice by remember(id){mutableStateOf(newestVoice)}
    LaunchedEffect(newestVoice){if(newestVoice!=null&&newestVoice!=heardVoice){heardVoice=newestVoice;if(!state.recording)vm.playVoice(newestVoice)}}
    Column(modifier){
        // Like WhatsApp (and iPhone): the chat's own bar replaces the app header, with back beside the picture; tap for info.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=4.dp,end=4.dp,top=2.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically){
            if(back!=null)IconButton(onClick=back){Icon(Icons.Outlined.ArrowBack,"Back")}
            Row(Modifier.weight(1f).clickable(onClickLabel=if(channel)"Group info" else "Contact info"){info=true}.semantics{contentDescription=if(channel)"Group info" else "Contact info"}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Avatar(conversation.getString("title"),channel,state.isPrivate(id),40.dp)
                Column(Modifier.weight(1f)){
                    Text(conversation.getString("title"),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        Box(Modifier.size(7.dp).background(if(here)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f),CircleShape))
                        Text(admissionState(conversation)?:if(conversation.optBoolean("pendingJoin"))"Waiting for the channel owner to add you" else if(!conversation.optBoolean("joined"))"Left or removed · Saved history" else if(here)(if(channel)"Connected to a member nearby · posts deliver now" else "Connected nearby · messages deliver now") else if(channel)"${members.size} member${if(members.size==1)"" else "s"} · posts travel when you meet a member" else "Saved on this phone · delivers when you meet",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            if(callReady){IconButton(onClick={call(false,false)},enabled=!state.calling&&state.walkieConversation==null){Icon(Icons.Outlined.Call,"Nearby voice call")};IconButton(onClick={call(true,false)},enabled=!state.calling&&state.walkieConversation==null){Icon(Icons.Outlined.Videocam,"Nearby video call")}}
        }
        HorizontalDivider()
        if(state.calling)CallPanel(vm,state)
        if(thread!=null)Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={thread=null}){Icon(Icons.Outlined.ArrowBack,"Back to chat")}
            Text("Thread · ${replyCounts[thread]?:0} ${if(replyCounts[thread]==1)"reply" else "replies"}",style=MaterialTheme.typography.titleMedium)
        }
        // Pinned messages: tap one to scroll to it and flash it, like a quote.
        if(pins.isNotEmpty())Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)).padding(horizontal=16.dp,vertical=4.dp)){
            pins.forEach{m->Row(Modifier.fillMaxWidth().clickable(onClickLabel="Show pinned message"){jump(m.getString("id"))}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Icon(Icons.Outlined.PushPin,"Pinned",Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary);Text(chatPreview(m),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }}
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).testTag("chat-transcript"),state=transcript,reverseLayout=true,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(messages.isEmpty())item {EmptyState(if(conversation.optBoolean("pendingJoin"))"Waiting for approval" else if(channel)"No posts yet" else "Say hello",if(conversation.optBoolean("pendingJoin"))"The group creator must add you before messages arrive." else "Messages are signed on this phone and delivered when you meet the other person or a member nearby.",Icons.Outlined.ChatBubbleOutline)}
            items(messages.asReversed(),key={it.getString("id")}){message->
                val messageId=message.getString("id");val owned=message.optBoolean("owned");val body=message.body();val payload=message.getJSONObject("payload")
                val deleted=messageId in gone;val hidden=!deleted&&channel&&state.messageHidden(id,messageId)
                var menu by remember{mutableStateOf(false)};val haptics=LocalHapticFeedback.current
                val attachment=payload.optJSONObject("attachment")?.takeIf{!deleted&&!hidden};val file=attachment?.let{a->state.files.firstOrNull{it.getString("id")==a.getString("id")}};val complete=file?.optBoolean("complete")==true
                Row(Modifier.fillMaxWidth().animateItem(fadeInSpec=tween(140),placementSpec=null,fadeOutSpec=null),horizontalArrangement=if(owned)Arrangement.End else Arrangement.Start){
                    Box(Modifier.testTag("message-$messageId")){
                        // Bubbles fit their content, with a tail corner on the sender's side; time and status sit bottom-right.
                        val format=body.getString("format");val shape=RoundedCornerShape(topStart=16.dp,topEnd=16.dp,bottomStart=if(owned)16.dp else 4.dp,bottomEnd=if(owned)4.dp else 16.dp)
                        // Same on iPhone (Palette.sent/received): green for sent, a neutral outlined card for received; text 7:1 or better.
                        val darkTheme=MaterialTheme.colorScheme.background.luminance()<.5f
                        val bubbleColor=when{
                            messageId==highlight->MaterialTheme.colorScheme.tertiaryContainer
                            owned->Color(if(darkTheme)0xff2b5544 else 0xffd9eadf)
                            else->Color(if(darkTheme)0xff26322d else 0xfffffefa)
                        }
                        val bubbleContentColor=if(messageId==highlight)MaterialTheme.colorScheme.onTertiaryContainer else if(owned)Color(if(darkTheme)0xfff3f6f1 else 0xff1d3a2f) else Color(if(darkTheme)0xffe6eee6 else 0xff243d35)
                        Surface(Modifier.widthIn(min=96.dp,max=300.dp).testTag(if(owned)"chat-bubble-sent-$messageId" else "chat-bubble-received-$messageId").combinedClickable(onClick={if(attachment!=null&&!complete)vm.syncChatAttachment(messageId) else if(attachment!=null&&format!="VOICE"&&format!="PHOTO")viewing=messageId},onLongClick={haptics.performHapticFeedback(HapticFeedbackType.LongPress);menu=true},onLongClickLabel="Message options"),shape=shape,color=bubbleColor,contentColor=bubbleContentColor,border=if(owned)null else BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                            Column(Modifier.width(IntrinsicSize.Max).padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                if(channel&&!owned)Text(body.getJSONObject("author").getJSONObject("body").getString("name"),style=MaterialTheme.typography.labelLarge,color=bubbleContentColor,fontWeight=FontWeight.SemiBold)
                                if(deleted||hidden)Text(if(deleted)"This message was deleted" else "Removed by a group admin",style=MaterialTheme.typography.bodyMedium,fontStyle=FontStyle.Italic,color=bubbleContentColor.copy(alpha=.78f))
                                else {
                                    if(payload.optBoolean("forwarded"))Text("Forwarded",style=MaterialTheme.typography.labelSmall,fontStyle=FontStyle.Italic,color=bubbleContentColor.copy(alpha=.78f))
                                    (payload.optString("replyTo").ifEmpty{null}?:body.optString("threadRootId").ifEmpty{null})?.takeIf{it!=thread}?.let{q->ReplyQuote(byId[q],q in gone){jump(q)}}
                                    // Hidden for the Oct 2026 build: the link from a shared need/update into the Needs and Updates tabs. The title stays as plain text.
                                    // payload.optJSONObject("reference")?.let {r->Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.VolunteerActivism,null);TextButton(onClick={if(r.getString("type")=="NEED")openNeed(r.getString("id")) else vm.notice("Find this public update in Updates and check its latest status.")}){Text(r.getString("title"))}}}
                                    payload.optJSONObject("reference")?.let {r->Text(r.getString("title"),style=MaterialTheme.typography.bodyLarge)}
                                    attachment?.let{a->
                                        val progress=state.transfers[a.getString("id")]
                                        when{
                                            // Photos: Aakash's verified preview (loading/unavailable states, tap to enlarge) in a fixed-width tile.
                                            complete&&format=="PHOTO"->Box(Modifier.width(240.dp)){PrivatePhoto(vm,messageId,true){haptics.performHapticFeedback(HapticFeedbackType.LongPress);menu=true}}
                                            complete&&format=="VOICE"->VoiceBubble(vm,messageId,bubbleContentColor)
                                            complete&&format=="VIDEO"->VideoTile(a.getLong("size"))
                                            else->Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                                                Surface(Modifier.size(40.dp),shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Box(contentAlignment=Alignment.Center){Icon(when(format){"PHOTO"->Icons.Outlined.Image;"VIDEO"->Icons.Outlined.Movie;"VOICE"->Icons.Outlined.Mic;else->Icons.Outlined.Description},null)}}
                                                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                                                    val kind=when(format){"PHOTO"->"Photo";"VIDEO"->"Video";"VOICE"->"Voice message";else->a.getString("name")}
                                                    Text(if(complete)kind else if(progress!=null)"$kind · ${(progress*100).toInt()}%" else if(owned)kind else "$kind · arrives when the sender is nearby",style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                                    Text(fileSize(a.getLong("size"))+if(complete&&format=="FILE")" · tap to open" else "",style=MaterialTheme.typography.labelSmall,color=bubbleContentColor.copy(alpha=.78f))
                                                    if(progress!=null&&!complete)LinearProgressIndicator({progress},Modifier.width(160.dp))
                                                }
                                            }
                                        }
                                    }
                                    if(payload.has("text"))Text(payload.getString("text"),style=MaterialTheme.typography.bodyLarge)
                                }
                                Text(java.time.format.DateTimeFormatter.ofPattern("h:mm a").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(body.getString("createdAt")))+if(owned)" · "+bubbleStatus(message) else "",
                                    Modifier.align(Alignment.End),style=MaterialTheme.typography.labelSmall,color=bubbleContentColor.copy(alpha=.78f))
                                // Telegram-style: replies open as a thread under the message they answer.
                                val count=replyCounts[messageId]?:0
                                if(thread==null&&!deleted&&(count>0||(announce&&canReplyThreads))){HorizontalDivider(color=bubbleContentColor.copy(alpha=.2f));Text(if(count>0)"$count ${if(count==1)"reply" else "replies"}" else "Reply",Modifier.fillMaxWidth().clickable{thread=messageId;replyTo=null;followLatest=true}.padding(vertical=2.dp),style=MaterialTheme.typography.labelLarge,color=bubbleContentColor,fontWeight=FontWeight.SemiBold)}
                            }
                        }
                        MessageMenu(menu,{menu=false},deleted=deleted||hidden,text=payload.optString("text").ifEmpty{null},canReply=if(thread==null&&announce)canReplyThreads else canPost,canForward=payload.has("text")||complete,canSave=complete,owned=owned,pinned=messageId in pinnedIds,
                            relief=payload.has("text")&&!owned,team=state.preparation!=null,
                            reply={if(thread==null&&announce){thread=roots[messageId]?:messageId};replyTo=message},forward={forwarding=messageId},save={exportId=messageId;exporter.launch(attachment!!.getString("name"))},report={reportId=messageId},delete={deleting=message},pin={vm.pinChatMessage(id,messageId)},
                            help={helpMessage=message},need={createNeed(message)})
                    }
                }
            }
        }
        if(transcript.firstVisibleItemIndex>0)TextButton(onClick={uiScope.launch{transcript.scrollToItem(0);followLatest=true}}){Text("Latest messages")}
        if(!channel && canPost)WalkieTalkieControl(vm,state,id,conversation.getString("title"),callReady)
        if(!canPost)Text(admissionState(conversation)?:if(conversation.optBoolean("pendingJoin"))"You can post after a group admin adds you." else if(channel && conversation.optBoolean("joined"))(if(announce&&canReplyThreads)"Only group admins post here. Tap Reply under a post to answer in its thread." else "Only group admins can post here.") else "Sending is unavailable. Saved history remains here.",Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else Surface(color=MaterialTheme.colorScheme.surface){Column{
            HorizontalDivider()
            if(state.recording)Text("Recording… release to send",Modifier.padding(start=16.dp,top=8.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.error)
            replyTo?.let{r->Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f)){ReplyQuote(r,false)};IconButton(onClick={replyTo=null}){Icon(Icons.Outlined.Close,"Cancel reply")}}}
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                FilledTonalIconButton(onClick={picker.launch(arrayOf("image/jpeg","image/png","image/webp","audio/mp4","audio/mpeg","video/mp4","video/webm","text/plain"))},enabled=!state.busy&&(!channel||caps?.optBoolean("canAttachMedia")==true)){Icon(Icons.Outlined.Image,"Send a photo or video")}
                OutlinedTextField(text,{text=it.take(4000);vm.saveChatComposer(id,text)},Modifier.weight(1f),placeholder={Text("Message")},maxLines=5,shape=RoundedCornerShape(20.dp))
                // Hold to talk, release to send: a short tap is ignored; the first hold asks for the microphone.
                if(text.isBlank()){
                    val context=LocalContext.current;val talkable=(!channel||caps?.optBoolean("canAttachMedia")==true)&&!state.calling&&state.walkieConversation==null
                    Box(Modifier.size(48.dp).background(if(state.recording)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,CircleShape)
                        .semantics{contentDescription="Hold to talk"}
                        .pointerInput(talkable){detectTapGestures(onPress={
                            if(!talkable)return@detectTapGestures
                            if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){record();return@detectTapGestures}
                            val started=System.currentTimeMillis();vm.startVoice()
                            val released=tryAwaitRelease()
                            vm.releaseVoice(id,released&&System.currentTimeMillis()-started>=500,threadRootForSend)
                        })},contentAlignment=Alignment.Center){Icon(Icons.Outlined.Mic,null,tint=if(state.recording)MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer)}
                }
                else FilledIconButton(onClick={vm.chatSend(id,text.trim(),replyTo?.getString("id")?:thread,threadRootForSend){text="";replyTo=null;vm.saveChatComposer(id,"")}},enabled=!state.busy){Icon(Icons.Outlined.ArrowUpward,"Send")}
            }
        }}
    }
    viewing?.let{messageId->MediaViewer(vm,messageId,byId[messageId]?.getJSONObject("payload")?.optJSONObject("attachment")?.optString("mime")?:"",{viewing=null})}
    forwarding?.let{messageId->ForwardPicker(state,{targets->vm.forwardChat(messageId,targets);forwarding=null},{forwarding=null})}
    deleting?.let{message->
        val mine=message.optBoolean("owned");val moderator=channel&&caps?.optBoolean("canModerate")==true
        val everyone=message.getString("id") !in gone && ((mine&&canPost)||(!mine&&moderator))
        AlertDialog(onDismissRequest={deleting=null},title={Text("Delete message?")},text={Text(if(everyone)"Delete for everyone removes it from every phone that receives the change. Copies already saved elsewhere can't be erased." else "This removes it from this phone only.")},
            confirmButton={Column(horizontalAlignment=Alignment.End){
                if(everyone)TextButton(onClick={if(mine)vm.deleteChatForEveryone(message.getString("id")) else vm.moderateChannel(id,"HIDE_MESSAGE",message.getString("id"));deleting=null}){Text("Delete for everyone",color=MaterialTheme.colorScheme.error)}
                TextButton(onClick={vm.deleteChatForMe(message.getString("id"));deleting=null}){Text("Delete for me",color=MaterialTheme.colorScheme.error)}
                TextButton(onClick={deleting=null}){Text("Cancel")}
            }})
    }
    reportId?.let{messageId->ReportReason({reason->vm.reportChat(messageId,when(reason){"HARASSMENT"->"ABUSE";"UNSAFE"->"SAFETY";else->reason});reportId=null},{reportId=null})}
    helpMessage?.let{message->HelpComposer(vm,state,null,{helpMessage=null},message.getJSONObject("payload").optString("text"))}
}
/** Short delivery status for a bubble (the chat list keeps the full wording). */
private fun bubbleStatus(m:JSONObject)=when{m.optBoolean("attention")->"Needs attention";m.has("readAt")->"Read";m.has("deliveredAt")->"Delivered";m.has("sentNearby")||m.optBoolean("serverSaved")->"Sent";else->"Waiting"}
/** The quoted message above a reply (or in the composer while replying); tapping it jumps to the original. */
@Composable private fun ReplyQuote(message:JSONObject?,deleted:Boolean,open:(()->Unit)?=null){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if(open!=null)Modifier.clickable(onClickLabel="Show the original message"){open()} else Modifier).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.6f),RoundedCornerShape(8.dp)).height(IntrinsicSize.Min)){
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary,RoundedCornerShape(topStart=8.dp,bottomStart=8.dp)))
        Column(Modifier.padding(horizontal=10.dp,vertical=6.dp)){
            Text(message?.let{if(it.optBoolean("owned"))"You" else it.body().getJSONObject("author").getJSONObject("body").getString("name")}?:"Earlier message",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Text(if(deleted)"This message was deleted" else message?.let{chatPreview(it)}?:"Not on this phone",style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
/** Long-press options, the same list and order as iPhone. */
@Composable private fun MessageMenu(open:Boolean,close:()->Unit,deleted:Boolean,text:String?,canReply:Boolean,canForward:Boolean,canSave:Boolean,owned:Boolean,pinned:Boolean,relief:Boolean,team:Boolean,
    reply:()->Unit,forward:()->Unit,save:()->Unit,report:()->Unit,delete:()->Unit,pin:()->Unit,help:()->Unit,need:()->Unit){
    val clipboard=LocalClipboardManager.current
    DropdownMenu(open,close){
        @Composable fun item(label:String,icon:ImageVector,danger:Boolean=false,run:()->Unit)=DropdownMenuItem(text={Text(label,color=if(danger)MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)},leadingIcon={Icon(icon,null,tint=if(danger)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)},onClick={close();run()})
        if(!deleted){
            if(canReply)item("Reply",Icons.Outlined.Reply,run=reply)
            if(text!=null)item("Copy",Icons.Outlined.ContentCopy){clipboard.setText(AnnotatedString(text))}
            if(canForward)item("Forward",Icons.Outlined.Shortcut,run=forward)
            if(canSave)item("Save",Icons.Outlined.Download,run=save)
            item(if(pinned)"Unpin" else "Pin",Icons.Outlined.PushPin,run=pin)
            // Hidden for the Oct 2026 build: help requests and needs from a chat message (Needs is not shown).
            // if(relief)item("Create help request",Icons.Outlined.VolunteerActivism,run=help)
            // if(relief&&team)item("Create need",Icons.Outlined.Inventory2,run=need)
            if(!owned)item("Report",Icons.Outlined.Flag,run=report)
        }
        item("Delete",Icons.Outlined.Delete,danger=true,run=delete)
    }
}
@Composable private fun ForwardPicker(state:AppState,send:(List<String>)->Unit,close:()->Unit){
    var chosen by remember{mutableStateOf(emptyList<String>())}
    AlertDialog(onDismissRequest=close,title={Text("Forward to")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){
        state.conversations.filter{it.optBoolean("joined")&&!it.optBoolean("pendingJoin")}.forEach{c->val cid=c.getString("id")
            Row(Modifier.fillMaxWidth().clickable{chosen=if(cid in chosen)chosen-cid else (chosen+cid).take(5)}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Avatar(c.getString("title"),c.getString("type")=="CHANNEL",state.isPrivate(cid));Text(c.getString("title"),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Checkbox(cid in chosen,null)
            }}
        Text("Up to 5 chats at once.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={TextButton(onClick={send(chosen)},enabled=chosen.isNotEmpty()){Text("Send")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable fun ChannelCreate(create:(String,String,String,String)->Unit,close:()->Unit,busy:Boolean){
    var name by rememberSaveable{mutableStateOf("")};var visibility by rememberSaveable{mutableStateOf("INVITE")}
    var type by rememberSaveable{mutableStateOf("FREE")};var approval by rememberSaveable{mutableStateOf(false)}
    AlertDialog(onDismissRequest=close,title={Text("New group")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(name,{name=it.take(48)},label={Text("Group name")},singleLine=true);listOf("INVITE" to "Invite only · encrypted","OPEN" to "Open nearby · people nearby can ask to join").forEach {(value,label)->Row(Modifier.fillMaxWidth().clickable{visibility=value},verticalAlignment=Alignment.CenterVertically){RadioButton(visibility==value,{visibility=value});Text(label)}};Text("Who can post",style=MaterialTheme.typography.titleSmall);GroupType.labels.forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{type=value},verticalAlignment=Alignment.CenterVertically){RadioButton(type==value,{type=value});Text(label)}};Row(verticalAlignment=Alignment.CenterVertically){Checkbox(approval,{approval=it});Text("Approve new members (also applies to join links)")};Text("You become the group creator: you sign who is in it, and you can make admins. Up to 200 members.",style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(onClick={create(name.trim(),visibility,type,if(visibility=="OPEN")if(approval)"APPROVAL_ONLY"else"OPEN" else if(approval)"INVITE_PLUS_APPROVAL"else"INVITE_AUTO")},enabled=!busy&&name.isNotBlank()){Text("Create group")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable fun JoinInvite(vm:SaathiViewModel,link:String="",accept:(String)->Unit,close:()->Unit,busy:Boolean){
    var value by remember(link){mutableStateOf(link)}
    var scanner by remember{mutableStateOf(false)};var reviewed by remember{mutableStateOf<JSONObject?>(null)};var error by remember{mutableStateOf<String?>(null)};var checking by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    val approval=reviewed?.getJSONObject("body")?.optString("kind")=="CHAT_ADMISSION"
    AlertDialog(onDismissRequest=close,title={Text(if(reviewed==null)"Join with invite"else"Review invitation")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(reviewed==null){Text("Paste a private-channel invitation or join link, or scan its QR.");OutlinedTextField(value,{value=it.take(700000);reviewed=null;error=null},label={Text("Paste CJP Swarm invitation")},maxLines=4);OutlinedButton(onClick={scanner=true}){Icon(Icons.Outlined.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text("Scan QR code")}}
        else {val b=reviewed!!.getJSONObject("body");val p=if(approval)b else b.getJSONObject("policy").getJSONObject("body");Text("# "+p.getString("name"),style=MaterialTheme.typography.titleLarge);Text(if(b.optString("recipientId")=="*")"Group join link · No history or keys before admission" else if(approval)"Approval required · No history or keys before admission"else"Recipient-bound invitation");Text("Invited by "+(b.optJSONObject("issuer")?:p.getJSONObject("owner")).getJSONObject("body").getString("name"));Text("Expires "+timeLabel(b.getString("expiresAt")));TextButton(onClick={reviewed=null}){Text("Change invitation")}}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={if(reviewed!=null)accept(value)else scope.launch{checking=true;error=null;try{reviewed=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.chat.decodeInvite(value)}}catch(_:Exception){error="This invitation could not be verified. Check its expiry and intended recipient, or ask for a new one."}finally{checking=false}}},enabled=value.isNotBlank()&&!busy&&!checking){Text(if(checking)"Checking…"else if(reviewed==null)"Review invitation"else if(approval)"Request to join"else"Join channel")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
    if(scanner)InviteScanner({value=it;reviewed=null;error=null;scanner=false},{scanner=false})
}
@Composable private fun InfoTopBar(title:String,back:()->Unit){
    Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=back){Icon(Icons.Outlined.ArrowBack,"Back")};Text(title,style=MaterialTheme.typography.titleMedium)}
}
private fun roleLabel(role:String)=when(role){"OWNER"->"Group creator";"ADMIN"->"Admin";"MODERATOR"->"Moderator";"READ_ONLY"->"Read only";else->"Member"}
/** Members, roles, join requests, bans and settings for one group: the same screen and rules as iPhone. */
@Composable fun GroupInfoScreen(vm:SaathiViewModel,state:AppState,id:String,back:()->Unit,modifier:Modifier){
    val policy=state.channelPolicy(id);val pb=policy?.getJSONObject("body")
    val me=state.chatProfile?.let{ChatProtocol.participant(it)}
    val owner=pb?.getJSONObject("owner")?.let{ChatProtocol.participant(it)}==me
    val caps=state.channelCapabilities(id)
    val members=pb?.getJSONArray("members")?.objects()?.filter{it.isNull("removedAt")}.orEmpty()
    var inviting by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf<Pair<String,String>?>(null)}
    val pending=policy!=null&&state.channelActions(id).any{r->val a=r.getJSONObject("envelope").getJSONObject("body");a.getString("action") in listOf("REMOVE","BAN","SET_ROLE","APPROVE_JOIN")&&a.getInt("version")==pb!!.getInt("version")&&pb.optJSONArray("appliedActions")?.strings()?.contains(a.getString("id"))!=true}
    Column(modifier){
        InfoTopBar("Group info",back)
        LazyColumn(contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(pb?.optString("name")?:"",true,pb?.optString("visibility")=="INVITE");Column{Text(pb?.optString("name")?:"Group",style=MaterialTheme.typography.titleLarge);Text((if(pb?.optString("visibility")=="INVITE")"Invite only · encrypted" else "Open nearby · member-readable")+" · ${members.size} member${if(members.size==1)"" else "s"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            if(pending)item{Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.secondaryContainer){Column(Modifier.fillMaxWidth().padding(16.dp)){Text("Membership is changing",style=MaterialTheme.typography.titleMedium);Text("An admin's change reaches the group creator's phone when you meet. Posting resumes with the new membership.",style=MaterialTheme.typography.bodySmall)}}}
            if(caps?.optBoolean("canInvite")==true)item{Button(onClick={inviting=true}){Icon(Icons.Outlined.PersonAdd,null);Spacer(Modifier.width(8.dp));Text("Add people")}}
            item{Text("Members",style=MaterialTheme.typography.titleLarge)}
            items(members,key={ChatProtocol.participant(it.getJSONObject("profile"))}){member->
                val person=member.getJSONObject("profile");val personId=ChatProtocol.participant(person);val name=person.getJSONObject("body").getString("name");val role=member.getString("role")
                Column{Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Avatar(name)
                    Column(Modifier.weight(1f)){Text(if(personId==me)"$name (you)" else name,style=MaterialTheme.typography.titleMedium);Text(roleLabel(role),style=MaterialTheme.typography.bodySmall,color=if(role in listOf("OWNER","ADMIN"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
                    // Managers act on everyone except the creator; only the creator acts on admins.
                    if(caps?.optBoolean("canManageMembers")==true&&personId!=me&&role!="OWNER"&&(role!="ADMIN"||owner))MemberMenu(vm,state,id,personId,name,owner,role){action->confirm=action to personId}
                };HorizontalDivider()}
            }
            if(caps?.optBoolean("canManageMembers")==true){
                val requests=state.chatJoinInbox.filter{it.getJSONObject("request").getJSONObject("body").getString("channelId")==id&&!it.optBoolean("resolved")&&Instant.parse(it.getJSONObject("request").getJSONObject("body").getString("expiresAt"))>Instant.now()}
                item{Text("Join requests · ${requests.size}",style=MaterialTheme.typography.titleLarge)}
                if(requests.isEmpty())item{Text("Requests appear when people ask to join near you or a member.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(requests){request->val person=request.getJSONObject("request").getJSONObject("body").getJSONObject("participant");val name=person.getJSONObject("body").getString("name")
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Avatar(name);Text(name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);OutlinedButton(onClick={vm.moderateChannel(id,"REJECT_JOIN",ChatProtocol.participant(person))},enabled=!state.busy){Text("Decline")};Button(onClick={vm.moderateChannel(id,"APPROVE_JOIN",ChatProtocol.participant(person))},enabled=!state.busy){Text("Approve")}}}
                val banned=pb?.optJSONArray("bannedIds")?.strings().orEmpty()
                if(banned.isNotEmpty()){item{Text("Banned",style=MaterialTheme.typography.titleLarge)};items(banned){personId->Row(verticalAlignment=Alignment.CenterVertically){Text(state.chatContacts.firstOrNull{it.getString("id")==personId}?.getJSONObject("profile")?.getJSONObject("body")?.getString("name")?:personId.take(12),Modifier.weight(1f));OutlinedButton(onClick={vm.moderateChannel(id,"UNBAN",personId)},enabled=!state.busy){Text("Unban")}}}}
            }
            if(owner&&pb!=null)item{
                val config=pb.optJSONObject("settings");val type=GroupType.of(config);val open=pb.getString("visibility")=="OPEN";val admission=config?.optString("admission")?:if(open)"OPEN" else "INVITE_AUTO"
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Settings",style=MaterialTheme.typography.titleLarge)
                    Text("Who can post",style=MaterialTheme.typography.titleSmall)
                    GroupType.labels.forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy&&type!=value){vm.configureChannel(id,value,admission)},verticalAlignment=Alignment.CenterVertically){RadioButton(type==value,{vm.configureChannel(id,value,admission)},enabled=!state.busy);Text(label)}}
                    ApprovalSwitch(admission,open,state.busy){vm.configureChannel(id,type,if(open)if(it)"APPROVAL_ONLY" else "OPEN" else if(it)"INVITE_PLUS_APPROVAL" else "INVITE_AUTO")}
                }
            }
            // Admins see the creator's choice; only the creator's phone signs settings.
            if(!owner&&pb!=null&&caps?.optBoolean("canManageMembers")==true)item{ApprovalSwitch(pb.optJSONObject("settings")?.optString("admission")?:"",pb.getString("visibility")=="OPEN",true,null)}
            // Moderators: reports to review (they arrive when online).
            if(caps?.optBoolean("canModerate")==true){
                val reports=state.channelReports(id)
                item{Text("Reports to review · ${reports.size}",style=MaterialTheme.typography.titleLarge)}
                if(reports.isEmpty())item{Text("Reports from members arrive when Swarm is online.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(reports,key={it.getString("id")}){r->val held=state.chatMessages.firstOrNull{it.getString("id")==r.getString("messageId")}
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Column(Modifier.weight(1f)){Text(r.getString("reason").lowercase().replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.titleMedium);Text(held?.let{chatPreview(it)}?:"Message not on this phone",style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        if(held!=null&&!state.messageHidden(id,r.getString("messageId")))OutlinedButton(onClick={vm.moderateChannel(id,"HIDE_MESSAGE",r.getString("messageId"))},enabled=!state.busy){Text("Remove")}
                        Button(onClick={vm.moderateChannel(id,"REVIEW_REPORT",r.getString("messageId"))},enabled=!state.busy&&held!=null){Text("Reviewed")}
                    }}
                /* Recent changes log removed at Rohan's request (Oct 2026); kept for restoring.
                val recent=state.chatActions.filter{it.getJSONObject("envelope").getJSONObject("body").getString("channelId")==id&&it.getJSONObject("envelope").getJSONObject("body").getString("action") !in listOf("REACT","UNREACT")}
                    .sortedByDescending{it.getJSONObject("envelope").getJSONObject("body").getString("issuedAt")}.take(12)
                if(recent.isNotEmpty()){item{Text("Recent changes",style=MaterialTheme.typography.titleLarge)}
                    items(recent,key={it.getString("id")}){row->val a=row.getJSONObject("envelope").getJSONObject("body")
                        val actor=a.getJSONObject("actor").getJSONObject("body").getString("name");val target=members.firstOrNull{ChatProtocol.participant(it.getJSONObject("profile"))==a.getString("targetId")}?.getJSONObject("profile")?.getJSONObject("body")?.getString("name")
                        val what=(actionLabels[a.getString("action")]?:a.getString("action").lowercase())+(if(a.getString("action") in ChannelGovernance.membershipActions&&target!=null)" $target" else "")+(if(a.getString("action")=="SET_ROLE")" → "+roleLabel(a.optString("role")) else "")
                        Text("$actor $what · "+timeLabel(a.getString("issuedAt"))+" · "+if(row.optBoolean("rejected"))"Not accepted" else if(row.optBoolean("serverSaved"))"Confirmed" else "Waiting to reach the group creator",style=MaterialTheme.typography.bodySmall)}}
                */
            }
            item{Text("Removed people can't rejoin on their own; only an admin can add them back. Changes reach other phones as people meet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(state.conversations.firstOrNull{it.getString("id")==id}?.optBoolean("joined")==true)item{TextButton(onClick={confirm=(if(owner)"DELETE" else "LEAVE") to ""}){Text(if(owner)"Delete group" else "Leave group",color=MaterialTheme.colorScheme.error)}}
        }
    }
    if(inviting)AddPeopleDialog(vm,state,id){inviting=false}
    confirm?.let{(action,person)->
        val name=members.firstOrNull{ChatProtocol.participant(it.getJSONObject("profile"))==person}?.getJSONObject("profile")?.getJSONObject("body")?.getString("name")?:"this person"
        AlertDialog(onDismissRequest={confirm=null},title={Text(when(action){"DELETE"->"Delete this group?";"LEAVE"->"Leave this group?";"BAN"->"Ban $name?";else->"Remove $name?"})},
            text={Text(when(action){"BAN"->"They can't rejoin, even with an invitation, until an admin unbans them.";"REMOVE"->"They stop receiving new posts. Only an admin can add them back.";else->"Copies already received stay on other people's phones. The change spreads as phones meet."})},
            confirmButton={TextButton(onClick={when(action){"DELETE"->{vm.deleteChannel(id);back()};"LEAVE"->{vm.leaveChat(id);back()};"BAN"->vm.moderateChannel(id,"BAN",person);else->vm.removeChatMember(id,person)};confirm=null}){Text(when(action){"DELETE"->"Delete";"LEAVE"->"Leave";"BAN"->"Ban";else->"Remove"},color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={confirm=null}){Text("Cancel")}})
    }
}
/** "Approve new members": on, join requests (including join links) wait for an admin; off, the first admin's phone to
 *  receive a join-link request admits it. Removed or banned people always need an admin. null [change] = read only. */
@Composable private fun ApprovalSwitch(admission:String,open:Boolean,busy:Boolean,change:((Boolean)->Unit)?){
    val on=admission in listOf("APPROVAL_ONLY","INVITE_PLUS_APPROVAL")
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Approve new members",style=MaterialTheme.typography.titleSmall)
        Text((if(open)"On: join requests wait for an admin." else "Applies to join links. On: an admin approves each person. Off: people with the link join automatically.")+" Removed or banned people always need an admin."+(if(change==null)" Only the group creator can change this." else ""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(on,{change?.invoke(it)},enabled=change!=null&&!busy)}
}
@Composable private fun MemberMenu(vm:SaathiViewModel,state:AppState,channel:String,person:String,name:String,owner:Boolean,role:String,destructive:(String)->Unit){
    var menu by remember{mutableStateOf(false)}
    Box{IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreHoriz,"Manage $name",tint=MaterialTheme.colorScheme.primary)};DropdownMenu(menu,{menu=false}){
        if(owner)DropdownMenuItem(text={Text(if(role=="ADMIN")"Remove admin" else "Make admin")},onClick={menu=false;vm.moderateChannel(channel,"SET_ROLE",person,if(role=="ADMIN")"MEMBER" else "ADMIN")},enabled=!state.busy)
        listOf("MODERATOR" to "Make moderator","MEMBER" to "Make member","READ_ONLY" to "Make read-only").filter{it.first!=role&&!(it.first=="MEMBER"&&role=="ADMIN")}.forEach{(next,label)->DropdownMenuItem(text={Text(label)},onClick={menu=false;vm.moderateChannel(channel,"SET_ROLE",person,next)},enabled=!state.busy)}
        DropdownMenuItem(text={Text("Remove from group",color=MaterialTheme.colorScheme.error)},onClick={menu=false;destructive("REMOVE")})
        DropdownMenuItem(text={Text("Ban",color=MaterialTheme.colorScheme.error)},onClick={menu=false;destructive("BAN")})
    }}
}
/** Choose a known person, then share their personal invitation as QR, link or nearby. */
@Composable private fun AddPeopleDialog(vm:SaathiViewModel,state:AppState,id:String,close:()->Unit){
    // The group's reusable join link shows as soon as the dialog opens (iPhone AddPeopleSheet); a person's invitation replaces it.
    var groupLink by remember{mutableStateOf<String?>(null)};var link by remember{mutableStateOf<String?>(null)};var invited by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(id){vm.createChannelJoinLink(id){groupLink=it}}
    val pb=state.channelPolicy(id)?.getJSONObject("body")
    val members=pb?.getJSONArray("members")?.objects()?.filter{it.isNull("removedAt")}?.map{ChatProtocol.participant(it.getJSONObject("profile"))}.orEmpty().toSet()
    val me=state.chatProfile?.let{ChatProtocol.participant(it)}
    AlertDialog(onDismissRequest=close,title={Text(if(link==null)"Add people" else "Invitation ready")},text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        val ready=link
        if(ready==null){
            Text("Group join link",style=MaterialTheme.typography.titleMedium)
            Text("Share this link or QR code. Many people can use it for up to 7 days. "+(if(pb?.optJSONObject("settings")?.optString("admission") in listOf("INVITE_AUTO","OPEN"))"People join automatically when an admin's phone receives their request." else "An admin approves each person."),style=MaterialTheme.typography.bodyMedium)
            groupLink?.let{InviteShare(vm,state,it,null)}?:LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("Or choose someone you've met nearby. They join with a personal invitation.",style=MaterialTheme.typography.bodyMedium)
            val people=state.chatContacts.filter{it.getString("id") !in members&&it.getString("id")!=me}
            if(people.isEmpty())Text("No one to add yet. Meet people in Nearby first; everyone you connect with appears here.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            people.forEach{person->val name=person.getJSONObject("profile").getJSONObject("body").getString("name")
                Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy){vm.invitePerson(id,person.getJSONObject("profile")){link=it;invited=person.getString("id")}}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(name);Text(name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Icon(Icons.Outlined.AddCircleOutline,null,tint=MaterialTheme.colorScheme.primary)}}
        }else{
            Text("Only this person's Swarm identity can use it. It expires within six hours.",style=MaterialTheme.typography.bodyMedium)
            InviteShare(vm,state,ready,invited)
        }
    }},confirmButton={TextButton(onClick=close){Text("Done")}})
}
/** QR code, share, copy and "Send nearby": the group link goes to whichever phone is connected; a personal invitation only to its person. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun InviteShare(vm:SaathiViewModel,state:AppState,text:String,person:String?){
    val clipboard=LocalClipboardManager.current;val context=LocalContext.current
    val qr=remember(text){runCatching{require(text.toByteArray().size<=1800);val matrix=MultiFormatWriter().encode(text,BarcodeFormat.QR_CODE,600,600);Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply{for(y in 0 until 600)for(x in 0 until 600)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}.getOrNull()}
    if(qr!=null)Image(qr.asImageBitmap(),"Invitation QR code",Modifier.fillMaxWidth().aspectRatio(1f)) else Text("This invitation is too large for a QR code. Share the link instead.",style=MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share Swarm invitation"))}){Text("Share link")}
        TextButton(onClick={clipboard.setText(AnnotatedString(text));vm.notice("Invitation copied.")}){Text("Copy")}
        if(state.chatPeer?.let{person==null||ChatProtocol.participant(it)==person}==true)Button(onClick={vm.sendNearbyInvite(text)}){Text("Send nearby")}
    }
}
/** A direct message's person: the same options on both phones. */
@Composable fun ContactInfoScreen(vm:SaathiViewModel,state:AppState,c:JSONObject,back:()->Unit,modifier:Modifier){
    val peer=c.getString("peerId");val blocked=peer in state.chatBlocks
    var reportPerson by remember{mutableStateOf(false)};var clearing by remember{mutableStateOf(false)}
    Column(modifier){
        InfoTopBar("Contact info",back)
        Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(c.getString("title"));Column{Text(c.getString("title"),style=MaterialTheme.typography.titleLarge);Text("Swarm identity "+peer.take(16).chunked(4).joinToString(" "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            Row(verticalAlignment=Alignment.CenterVertically){Text("Mute notifications",Modifier.weight(1f));Switch(c.optBoolean("muted"),{vm.muteChat(c.getString("id"))})}
            HorizontalDivider()
            TextButton(onClick={vm.blockChat(peer)}){Text(if(blocked)"Unblock "+c.getString("title") else "Block "+c.getString("title"),color=MaterialTheme.colorScheme.error)}
            TextButton(onClick={reportPerson=true}){Text("Report "+c.getString("title"),color=MaterialTheme.colorScheme.error)}
            TextButton(onClick={clearing=true}){Text("Clear chat",color=MaterialTheme.colorScheme.error)}
            Text("Blocking stops their messages and calls on this phone. Reports reach the team when Swarm is online.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(reportPerson)ReportReason({reason->vm.reportPerson(peer,when(reason){"HARASSMENT"->"ABUSE";"UNSAFE"->"SAFETY";else->reason});reportPerson=false},{reportPerson=false})
    if(clearing)AlertDialog(onDismissRequest={clearing=false},title={Text("Clear this chat?")},text={Text("Messages are removed from this phone only.")},confirmButton={TextButton(onClick={vm.clearChat(c.getString("id"));clearing=false}){Text("Clear",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={clearing=false}){Text("Cancel")}})
}
@Composable fun MoreScreen(vm:SaathiViewModel,state:AppState,team:()->Unit,saved:()->Unit,connection:()->Unit,modifier:Modifier){
    var name by rememberSaveable(state.chatProfile?.getJSONObject("body")?.optString("name")){mutableStateOf(state.chatProfile?.getJSONObject("body")?.optString("name")?:"")}
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
        // Hidden for the Oct 2026 build: relief work and saved information are not shown.
        // item {Heading("More","Your profile, relief work and saved information.")}
        item {Heading("More","Your profile and settings.")}
        if(BuildConfig.CHAT_ENABLED)item {Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Nearby profile",style=MaterialTheme.typography.titleLarge);OutlinedTextField(name,{name=it.take(32)},Modifier.fillMaxWidth(),label={Text("Display name")},singleLine=true);Button(onClick={vm.chatName(name)},enabled=name.isNotBlank()&&!state.busy){Text("Save name")};Text("Nearby devices see this name while you search and after you connect. It is not a verified volunteer identity; choose a name your group can recognize.",style=MaterialTheme.typography.bodySmall);state.chatProfile?.let {Text("Chat identity "+ChatProtocol.participant(it).chunked(8).joinToString(" "),style=MaterialTheme.typography.bodySmall)}}}
        item {HorizontalDivider();Text("Appearance",style=MaterialTheme.typography.titleLarge);listOf("SYSTEM" to "System","LIGHT" to "Light","DARK" to "Dark").forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{vm.preference("appearance",value)},verticalAlignment=Alignment.CenterVertically){RadioButton(state.preferences.optString("appearance","SYSTEM")==value,{vm.preference("appearance",value)});Text(label)}}}
        item {HorizontalDivider();Text("Privacy & Nearby",style=MaterialTheme.typography.titleLarge);Row(verticalAlignment=Alignment.CenterVertically){Text("Nearby visibility",Modifier.weight(1f));Switch(state.preferences.optBoolean("nearbyVisible",true),{vm.preference("nearbyVisible",it)})};Text("Searching runs while Swarm is open and stops in the background. Turning visibility off ends nearby discovery and its active connection.",style=MaterialTheme.typography.bodySmall)}
        // Hidden for the Oct 2026 build: relay of public community updates (Updates is not shown).
        // item {Text("Help Swarm send public updates online",style=MaterialTheme.typography.titleLarge);Text("Your device can carry authenticated reports for others. Choose when it may use your data.",style=MaterialTheme.typography.bodyMedium);listOf("OFF" to "Off","WIFI" to "Wi-Fi only","ANY" to "Wi-Fi + mobile data").forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{vm.preference("relay",value)},verticalAlignment=Alignment.CenterVertically){RadioButton(state.preferences.optString("relay","OFF")==value,{vm.preference("relay",value)});Text(label)}};Row(verticalAlignment=Alignment.CenterVertically){Text("Relay public media",Modifier.weight(1f));Switch(state.preferences.optBoolean("mediaRelay"),{vm.preference("mediaRelay",it)})}}
        item {
            Text("Storage & data",style=MaterialTheme.typography.titleLarge)
            // Daily data and battery limits for automatic online media (and the hidden public relay); the same on iPhone.
            var limit by remember(state.preferences.optInt("dailyLimitMB",2000)){mutableFloatStateOf(state.preferences.optInt("dailyLimitMB",2000).coerceIn(500,5000).toFloat())}
            var limitMenu by remember{mutableStateOf(false)}
            Text("Daily data limit · ${CommunityRelayPolicy.dailyLimitLabel(limit.toInt())}")
            Box {
                OutlinedButton(onClick={limitMenu=true}){Text("Choose allowance");Icon(Icons.Outlined.ArrowDropDown,null)}
                DropdownMenu(expanded=limitMenu,onDismissRequest={limitMenu=false}) {
                    CommunityRelayPolicy.dailyLimitPresetsMB.forEach{mb->DropdownMenuItem(text={Text(CommunityRelayPolicy.dailyLimitLabel(mb))},onClick={limitMenu=false;limit=mb.toFloat();vm.preference("dailyLimitMB",mb)})}
                }
            }
            Slider(limit,{limit=it},valueRange=500f..5000f,steps=8,onValueChangeFinished={val mb=(limit.toInt()/500*500).coerceIn(500,5000);limit=mb.toFloat();vm.preference("dailyLimitMB",mb)})
            var battery by remember(state.preferences.optInt("batteryMinimum",0)){mutableFloatStateOf(state.preferences.optInt("batteryMinimum",0).toFloat())}
            Text(if(battery.toInt()==0)"Use data at any battery level" else "Pause online media below ${battery.toInt()}% battery")
            Slider(battery,{battery=it},valueRange=0f..80f,onValueChangeFinished={vm.preference("batteryMinimum",battery.toInt())})
            Text("Data used today · "+state.relayReservedBytes/1_000_000+" MB",style=MaterialTheme.typography.bodySmall)
            Text("Covers photos, voice and videos sent or fetched over the internet. Nearby transfers are free and not limited.",style=MaterialTheme.typography.bodySmall)
            Text("Saved media · "+(state.files.sumOf{it.optLong("size")}/1048576)+" MB",style=MaterialTheme.typography.bodySmall)
            // Hidden for the Oct 2026 build: relay allowance and public media caches (relay of public updates is not shown).
            // Text("Relay allowance reserved today · "+state.relayReservedBytes/1_000_000+" MB",style=MaterialTheme.typography.bodySmall)
            // Text("The allowance includes request overhead; retries count. Bluetooth carries text only. Public media waits for a Wi-Fi-capable route.",style=MaterialTheme.typography.bodySmall)
            // TextButton({vm.clearSafeMedia()},enabled=!state.busy){Text("Clear safe public media caches")}
        }
        // Hidden for the Oct 2026 build: Team sign in / My relief team and Saved relief work. Connection options stays.
        // item {HorizontalDivider();TextButton(onClick=team){Icon(Icons.Outlined.VerifiedUser,null);Spacer(Modifier.width(8.dp));Text(if(state.preparation==null)"Team sign in" else "My relief team")};TextButton(onClick=saved){Icon(Icons.Outlined.Inventory2,null);Spacer(Modifier.width(8.dp));Text("Saved relief work and earlier messages")};TextButton(onClick=connection){Icon(Icons.Outlined.Link,null);Spacer(Modifier.width(8.dp));Text("Connection options")}}
        // Hidden for the Oct 2026 build: Connection options (kept in code; calls and local Wi-Fi pairing stay reachable from chats).
        // item {HorizontalDivider();TextButton(onClick=connection){Icon(Icons.Outlined.Link,null);Spacer(Modifier.width(8.dp));Text("Connection options")}}
        item {HorizontalDivider();Text(BuildConfig.BRAND_DISPLAY,style=MaterialTheme.typography.headlineMedium);Text(BuildConfig.BRAND_BYLINE);Text("Connect nearby. Coordinate together.");Text("Developed by Cockroach Janta Party",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall);Text("Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.ENVIRONMENT}",style=MaterialTheme.typography.bodySmall);val aware=LocalContext.current.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE);Text(if(aware)"Wi-Fi Aware · supported: iPhones can find this phone directly once the iPhone app gains Wi-Fi Aware" else "Wi-Fi Aware · not supported: iPhones find this phone one way, or both ways on a shared hotspot",style=MaterialTheme.typography.bodySmall);Text("Chat is an experimental addition. It has not received an independent cryptographic review. Native 1:1 local Wi-Fi calls passed the earlier two-device checks; huddles remain disabled.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

/** Two-way discovery for mixed groups: phones on one hotspot (no internet needed) find each other over Wi-Fi. */
@Composable private fun HotspotCard(vm:SaathiViewModel,state:AppState) {
    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Swarm hotspot",style=MaterialTheme.typography.titleLarge)
        val network=state.hotspot
        if(network==null){
            Text("Phones on one Swarm hotspot find each other in both directions, including iPhones, and send photos and videos at Wi-Fi speed. No internet is needed.",style=MaterialTheme.typography.bodyMedium)
            Button(onClick={vm.startHotspot()},enabled=!state.busy){Text("Start Swarm hotspot")}
        }else{
            Text("Others join by pointing their camera at this code. On iPhone, use the Camera app and tap Join. Keep Swarm open here to keep the hotspot on.",style=MaterialTheme.typography.bodyMedium)
            val qr=remember(network){runCatching{val matrix=MultiFormatWriter().encode(network.qr,BarcodeFormat.QR_CODE,600,600);Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply{for(y in 0 until 600)for(x in 0 until 600)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}.getOrNull()}
            if(qr!=null)Image(qr.asImageBitmap(),"Wi-Fi QR code for the Swarm hotspot",Modifier.fillMaxWidth().aspectRatio(1f))
            Text("Network · "+network.ssid,style=MaterialTheme.typography.titleMedium)
            Text("Password · "+network.password,style=MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick={vm.stopHotspot()}){Text("Stop hotspot")}
        }
    }}
}

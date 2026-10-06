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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    return payload.optString("text").ifBlank { payload.optJSONObject("reference")?.optString("title") ?: payload.optJSONObject("attachment")?.optString("name") ?: "Message" }
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
    message.has("sentNearby")->"Sent nearby"
    message.optBoolean("serverSaved")->"Sent"
    else->"Saved · Waiting for connection"
}
@Composable private fun Avatar(name:String,channel:Boolean=false) {
    Surface(Modifier.size(44.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment=Alignment.Center) {if(channel)Icon(Icons.Outlined.Tag,null,Modifier.size(22.dp)) else Text(name.take(1).uppercase(),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
    }
}
@Composable fun ChatsScreen(vm:SaathiViewModel,state:AppState,open:(String)->Unit,nearby:()->Unit,create:()->Unit,modifier:Modifier) {
    if(!BuildConfig.CHAT_ENABLED){Column(modifier.padding(20.dp)){EmptyState("Chat is being tested","Private chats are available in development and QA builds while security review is pending.",Icons.Outlined.ChatBubbleOutline)};return}
    var search by rememberSaveable {mutableStateOf("")}
    val ordered=state.conversations.sortedByDescending { c->state.chatMessages.filter {it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==c.getString("id")}.maxOfOrNull {it.getJSONObject("envelope").getJSONObject("body").getString("createdAt")}?: "" }
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item {Row(verticalAlignment=Alignment.CenterVertically){Text("Chats",Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium);IconButton(onClick=create){Icon(Icons.Outlined.Add,"Create channel")};IconButton(onClick={vm.syncChats()},enabled=!state.busy){Icon(Icons.Outlined.Sync,"Check chat delivery")}}}
        item {OutlinedTextField(search,{search=it.take(100)},Modifier.fillMaxWidth(),label={Text("Search saved chats")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true)}
        for(type in listOf("DIRECT","CHANNEL")){
            val conversations=ordered.filter {c->c.getString("type")==type && (c.getString("title").contains(search,true) || state.chatMessages.any {m->m.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==c.getString("id")&&chatPreview(m).contains(search,true)})}
            if(conversations.isNotEmpty())item {Text(if(type=="DIRECT")"Direct messages" else "Channels",Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleMedium)}
            items(conversations,key={it.getString("id")}){c->
                val messages=state.chatMessages.filter {it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==c.getString("id")}
                val last=messages.maxByOrNull {it.getJSONObject("envelope").getJSONObject("body").getString("createdAt")}
                val unread=messages.count {!it.optBoolean("owned")&&!it.optBoolean("readLocally")}
                Column {
                    Row(Modifier.fillMaxWidth().clickable {open(c.getString("id"))}.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Avatar(c.getString("title"),type=="CHANNEL")
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                            Text((if(type=="CHANNEL")"# " else "")+c.getString("title"),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(admissionState(c)?:if(c.optBoolean("pendingJoin"))"Waiting for the channel owner" else if(!c.optBoolean("joined"))"Left or removed · Saved history" else chatPreview(last),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(last?.optBoolean("owned")==true)Text(chatStatus(last),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)){
                            if(c.optBoolean("muted"))Icon(Icons.Outlined.NotificationsOff,"Muted",Modifier.size(18.dp))
                            if(unread>0)Badge {Text(unread.coerceAtMost(99).toString())}
                            last?.let {Text(timeLabel(it.getJSONObject("envelope").getJSONObject("body").getString("createdAt")),style=MaterialTheme.typography.labelSmall)}
                        }
                    };HorizontalDivider()
                }
            }
        }
        if(ordered.isEmpty())item {EmptyState("People first. Conversations that stay.","Meet someone in Nearby to start a direct message, or create a channel for your group.",Icons.Outlined.ChatBubbleOutline);Button(onClick=nearby){Text("Find people nearby")}}
        if(search.isNotBlank() && ordered.none {c->c.getString("title").contains(search,true) || state.chatMessages.any {m->m.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==c.getString("id")&&chatPreview(m).contains(search,true)}})item {Text("No matching saved conversations. Search stays on this phone.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Text("Direct messages and invite-only channels are encrypted between participants. Open channels are readable by their members. Chatting does not verify a relief volunteer.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
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
            if(peer==null)Button(onClick={discover(false)},enabled=!state.busy&&vm.nearby.available){Text("Enable Nearby for one minute")}
            Text(if(peer!=null)"This connection may carry saved messages for shared channels." else "Your temporary name is visible during the search. Compare the code before connecting.",style=MaterialTheme.typography.bodySmall)
        }}}
        item {Text("People",style=MaterialTheme.typography.titleLarge)}
        if(peer!=null)item {Column{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(peer.getJSONObject("body").getString("name"));Column(Modifier.weight(1f)){Text(peer.getJSONObject("body").getString("name"),style=MaterialTheme.typography.titleMedium);Text("Nearby participant",style=MaterialTheme.typography.bodySmall)};Button(onClick={vm.openChat(peer,open)},enabled=!state.busy){Text("Message")}};TextButton({reportPerson=ChatProtocol.participant(peer)}){Text("Report person")}}}
        items(state.peers.entries.toList(),key={it.key}){item->ListItem(headlineContent={Text(item.value)},supportingContent={Text("Compare a code to meet this person")},leadingContent={Icon(Icons.Outlined.PersonOutline,null)},trailingContent={TextButton(onClick={vm.connect(item.key)},enabled=!state.busy){Text("Connect")}})}
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
private fun AppState.channelCapabilities(id:String)=channelPolicy(id)?.let{policy->chatProfile?.let{ChannelGovernance.capabilities(policy,ChatProtocol.participant(it))}}
private fun AppState.channelActions(id:String)=chatActions.filter{it.getJSONObject("envelope").getJSONObject("body").getString("channelId")==id && !it.optBoolean("rejected")}.sortedWith(compareBy<JSONObject>{it.getJSONObject("envelope").getJSONObject("body").getString("issuedAt")}.thenBy{it.getString("id")})
private fun AppState.moderationFlag(id:String,target:String,on:String,off:String,key:String):Boolean {
    val latest=channelActions(id).lastOrNull{it.getJSONObject("envelope").getJSONObject("body").let{b->b.getString("targetId")==target && b.getString("action") in listOf(on,off)}}
    return latest?.getJSONObject("envelope")?.getJSONObject("body")?.getString("action")?.let{it==on} ?: (channelPolicy(id)?.getJSONObject("body")?.optJSONObject("moderation")?.optJSONArray(key)?.strings()?.contains(target)==true)
}
private fun AppState.threadLocked(id:String,root:String)=moderationFlag(id,root,"LOCK_THREAD","UNLOCK_THREAD","lockedThreads")
private fun AppState.messageHidden(id:String,message:String)=moderationFlag(id,message,"HIDE_MESSAGE","RESTORE_MESSAGE","hiddenMessages")
private fun AppState.channelReports(id:String)=chatReportInbox.filter{report->report.getString("channelId")==id && !channelActions(id).any{a->val b=a.getJSONObject("envelope").getJSONObject("body");b.getString("action")=="REVIEW_REPORT" && b.getString("targetId")==report.getString("messageId") && b.getString("issuedAt")>=report.getString("createdAt")}}

@Composable fun ConversationScreen(vm:SaathiViewModel,state:AppState,id:String,call:(Boolean,Boolean)->Unit,record:()->Unit,createNeed:(JSONObject)->Unit,openNeed:(String)->Unit,modifier:Modifier,onVisibleMessages:suspend (List<String>)->Unit={vm.readChat(id,it)}) {
    val conversation=state.conversations.firstOrNull {it.getString("id")==id}
    if(conversation==null){EmptyState("Conversation unavailable","Return to Chats and try again.",Icons.Outlined.ChatBubbleOutline);return}
    var thread by rememberSaveable(id){mutableStateOf<String?>(null)}
    androidx.activity.compose.BackHandler(thread!=null){thread=null}
    val draftKey=if(thread==null)id else "$id:$thread"
    var text by remember(draftKey){mutableStateOf("")}; LaunchedEffect(draftKey){val saved=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.repository.store.get("chat-drafts",draftKey)?.optString("text")?:""};if(text.isEmpty())text=saved};var settings by remember {mutableStateOf(false)};var search by remember(id){mutableStateOf("")};var exportId by rememberSaveable {mutableStateOf("")}
    var mentionPicker by remember {mutableStateOf(false)};var mentions by remember(id){mutableStateOf(emptyList<String>())}
    var reportId by remember{mutableStateOf<String?>(null)};var helpMessage by remember{mutableStateOf<JSONObject?>(null)}
    var searching by remember(id){mutableStateOf(false)}
    val transcript=key(id,thread){rememberLazyListState()};val uiScope=rememberCoroutineScope()
    var followLatest by remember(id){mutableStateOf(true)}
    val visibleMessages by remember(transcript){derivedStateOf{transcript.layoutInfo.visibleItemsInfo.mapNotNull{it.key as? String}}}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)vm.exportChatAttachment(exportId,uri)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.attachChat(id,uri,thread)}
    val allMessages=state.chatMessages.filter{it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==id}
    val messages=allMessages.filter {val b=it.getJSONObject("envelope").getJSONObject("body");(if(thread==null)!b.has("threadRootId") else b.optString("threadRootId")==thread || b.getString("id")==thread) && chatPreview(it).contains(search,true)}.sortedWith(compareBy<JSONObject>{it.getJSONObject("envelope").getJSONObject("body").getString("createdAt")}.thenBy{it.getString("id")})
    val channel=conversation.getString("type")=="CHANNEL"
    val caps=if(channel)state.channelCapabilities(id)else null
    val locked=thread?.let{state.threadLocked(id,it)}==true
    val canPost=conversation.optBoolean("joined") && (!channel || caps?.optBoolean(if(thread==null)"canPostTopLevel" else "canReplyInThreads")==true) && !locked
    val callReady=!channel&&state.media&&state.chatPeer?.let {ChatProtocol.participant(it)}==conversation.optString("peerId")&&!(conversation.optString("peerId") in state.chatBlocks)
    LaunchedEffect(id,transcript){
        snapshotFlow { Triple(transcript.isScrollInProgress,transcript.firstVisibleItemIndex,transcript.firstVisibleItemScrollOffset) }.distinctUntilChanged().collect{(scrolling,index,offset)->if(scrolling)followLatest=index==0&&offset==0}
    }
    LaunchedEffect(id,search,messages.lastOrNull()?.getString("id")){
        if(messages.isNotEmpty()&&(followLatest||messages.last().optBoolean("owned"))){transcript.scrollToItem(0);followLatest=true}
    }
    LaunchedEffect(id,visibleMessages){if(visibleMessages.isNotEmpty())onVisibleMessages(visibleMessages)}
    DisposableEffect(id){onDispose{vm.cancelVoice()}}
    Column(modifier){
        if(thread!=null)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={thread=null}){Icon(Icons.Outlined.ArrowBack,null);Text("Channel")};Text("Thread replies",style=MaterialTheme.typography.titleMedium)}
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Avatar(conversation.getString("title"),channel)
                Text(conversation.getString("title"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Row(verticalAlignment=Alignment.CenterVertically){
                Text(if(callReady)"Nearby · Calls available" else if(conversation.optBoolean("pendingJoin"))"Waiting for channel owner" else if(!conversation.optBoolean("joined"))"Saved history · Sending unavailable" else if(state.reachable)"Connected · Same conversation" else "Saved here · Nearby when available",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick={searching=!searching;if(!searching)search=""}){Icon(if(searching)Icons.Outlined.Close else Icons.Outlined.Search,if(searching)"Close conversation search" else "Search this conversation")}
                IconButton(onClick={settings=true}){Icon(Icons.Outlined.MoreVert,"Conversation settings")}
            }
        }
        if(callReady)Row(Modifier.padding(horizontal=20.dp)){IconButton(onClick={call(false,false)},enabled=!state.calling){Icon(Icons.Outlined.Call,"Nearby voice call")};IconButton(onClick={call(true,false)},enabled=!state.calling){Icon(Icons.Outlined.Videocam,"Nearby video call")}}
        if(state.calling)CallPanel(vm,state)
        if(searching)OutlinedTextField(search,{search=it.take(100)},Modifier.fillMaxWidth().padding(horizontal=20.dp),label={Text("Search this saved conversation")},singleLine=true)
        HorizontalDivider(Modifier.padding(top=12.dp))
        LazyColumn(Modifier.weight(1f).testTag("chat-transcript"),state=transcript,reverseLayout=true,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(messages.isEmpty())item {EmptyState(if(search.isNotBlank())"No saved matches" else if(conversation.optBoolean("pendingJoin"))"Waiting for approval" else "Start with a message",if(search.isNotBlank())"Search covers messages held on this phone." else if(conversation.optBoolean("pendingJoin"))"The channel owner must admit you before messages become available." else "Messages save on this phone first. Their delivery status changes only when confirmed.",Icons.Outlined.ChatBubbleOutline)}
            items(messages.asReversed(),key={it.getString("id")}){message->
                val owned=message.optBoolean("owned");val body=message.getJSONObject("envelope").getJSONObject("body");val payload=message.getJSONObject("payload")
                Row(Modifier.fillMaxWidth().animateItem(fadeInSpec=tween(140),placementSpec=null,fadeOutSpec=null),horizontalArrangement=if(owned)Arrangement.End else Arrangement.Start){
                    Surface(Modifier.widthIn(max=520.dp).fillMaxWidth(.88f),shape=RoundedCornerShape(14.dp),color=if(owned)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant){
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            if(channel)Text(body.getJSONObject("author").getJSONObject("body").getString("name"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                            if(state.messageHidden(id,message.getString("id")))Text("Hidden by a channel moderator",style=MaterialTheme.typography.bodyMedium)
                            else if(payload.has("text"))Text(payload.getString("text"),style=MaterialTheme.typography.bodyLarge)
                            if(!state.messageHidden(id,message.getString("id")))payload.optJSONObject("reference")?.let {r->Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.VolunteerActivism,null);TextButton(onClick={if(r.getString("type")=="NEED")openNeed(r.getString("id")) else vm.notice("Find this public update in Updates and check its latest status.")}){Text(r.getString("title"))}}}
                            payload.optJSONObject("attachment")?.takeIf{!state.messageHidden(id,message.getString("id"))}?.let {a->
                                val file=state.files.firstOrNull {it.getString("id")==a.getString("id")};val complete=file?.optBoolean("complete")==true
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){Icon(when(body.getString("format")){"PHOTO"->Icons.Outlined.Image;"VIDEO"->Icons.Outlined.Movie;"VOICE"->Icons.Outlined.Mic;else->Icons.Outlined.AttachFile},null);Column(Modifier.weight(1f)){Text(a.getString("name"));Text(fileSize(a.getLong("size"))+if(complete)" · Saved privately" else " · Waiting for connection",style=MaterialTheme.typography.bodySmall)}}
                                if(complete&&body.getString("format")=="PHOTO")PrivatePhoto(vm,message.getString("id"))
                                if(complete){if(body.getString("format")=="VOICE")VoicePlayback(vm,message.getString("id"));TextButton(onClick={exportId=message.getString("id");exporter.launch(a.getString("name"))},enabled=!state.busy){Text("Export verified attachment")};if(state.confirmed)TextButton(onClick={vm.shareChatAttachment(message.getString("id"))},enabled=!state.busy){Text("Offer nearby")}}
                                else Text("Receive nearby, or check the encrypted server copy when connected.",style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={vm.syncChatAttachment(message.getString("id"))}){Text(if(complete)"Sync encrypted attachment" else "Download or resume")}
                            }
                            Text(timeLabel(body.getString("createdAt"))+if(owned)" · "+chatStatus(message) else "",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(channel && thread==null){val replies=allMessages.filter{it.getJSONObject("envelope").getJSONObject("body").optString("threadRootId")==message.getString("id")};val unread=replies.count{!it.optBoolean("owned")&&!it.optBoolean("readLocally")};TextButton(onClick={thread=message.getString("id");search="";followLatest=true}){Text("${replies.size} replies"+if(unread>0)" · $unread unread"else"")}}
                            if(channel && caps?.optBoolean("canReact")==true){val reactions=state.channelActions(id).filter{it.getJSONObject("envelope").getJSONObject("body").let{a->a.getString("targetId")==message.getString("id")&&a.getString("action") in listOf("REACT","UNREACT")}}.groupBy{ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("actor"))};val mine=reactions[ChatProtocol.participant(state.chatProfile!!)]?.last()?.getJSONObject("envelope")?.getJSONObject("body")?.getString("action")=="REACT";val count=reactions.values.count{it.last().getJSONObject("envelope").getJSONObject("body").getString("action")=="REACT"};TextButton(onClick={vm.moderateChannel(id,if(mine)"UNREACT"else"REACT",message.getString("id"),reaction="THANKS")},enabled=!state.busy){Text((if(mine)"Remove thanks"else"Thanks")+if(count>0)" · $count"else"")}}
                            if(channel && caps?.optBoolean("canModerate")==true)FlowRow{if(!body.has("threadRootId"))TextButton(onClick={vm.moderateChannel(id,if(state.threadLocked(id,message.getString("id")))"UNLOCK_THREAD"else"LOCK_THREAD",message.getString("id"))},enabled=!state.busy){Text(if(state.threadLocked(id,message.getString("id")))"Unlock thread"else"Lock thread")};TextButton(onClick={vm.moderateChannel(id,if(state.messageHidden(id,message.getString("id")))"RESTORE_MESSAGE"else"HIDE_MESSAGE",message.getString("id"))},enabled=!state.busy){Text(if(state.messageHidden(id,message.getString("id")))"Restore"else"Hide")}}
                            if(!owned)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={reportId=message.getString("id")}){Text("Report")};if(payload.has("text") && !state.messageHidden(id,message.getString("id")))TextButton(onClick={helpMessage=message}){Text("Create Help Request")};if(state.preparation!=null&&payload.has("text"))TextButton(onClick={createNeed(message)}){Text("Create need from message")}}
                        }
                    }
                }
            }
        }
        if(transcript.firstVisibleItemIndex>0)TextButton(onClick={uiScope.launch{transcript.scrollToItem(0);followLatest=true}}){Text("Latest messages")}
        if(state.recording)Surface(color=MaterialTheme.colorScheme.errorContainer){FlowRow(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("Recording · Microphone on",Modifier.padding(12.dp));TextButton(onClick={vm.cancelVoice()}){Text("Discard")};Button(onClick={vm.sendVoice(id,thread)},enabled=!state.busy){Text("Send voice note")}}}
        else if(!canPost)Text(admissionState(conversation)?:if(locked)"Thread locked by a moderator. Earlier replies remain available." else if(conversation.optBoolean("pendingJoin"))"Request pending approval. Messages and keys arrive only after admission." else if(channel && conversation.optBoolean("joined"))"Your role can read this timeline. Open an allowed thread to reply." else "Sending is unavailable. Saved history remains here.",Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodyMedium)
        else Column {
          if(mentions.isNotEmpty())Text("Mentioning ${mentions.size} member${if(mentions.size==1)"" else "s"}",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelSmall)
          Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={picker.launch(arrayOf("image/jpeg","image/png","image/webp","audio/mp4","audio/mpeg","video/mp4","video/webm","text/plain"))},enabled=!state.busy&&canPost&&(!channel||caps?.optBoolean("canAttachMedia")==true)){Icon(Icons.Outlined.AttachFile,"Attach private media")}
            OutlinedTextField(text,{text=it.take(4000);vm.saveChatComposer(draftKey,text)},Modifier.weight(1f),placeholder={Text(if(thread!=null)"Reply in thread"else if(channel)"Message #"+conversation.getString("title") else "Message")},maxLines=4,enabled=canPost)
            if(channel)IconButton(onClick={mentionPicker=true},enabled=conversation.optBoolean("joined")){Icon(Icons.Outlined.AlternateEmail,"Mention channel members")}
            if(text.isBlank())IconButton(onClick=record,enabled=!state.busy&&canPost&&(!channel||caps?.optBoolean("canAttachMedia")==true)&&!state.calling){Icon(Icons.Outlined.Mic,"Record a voice note")}
            else IconButton(onClick={vm.chatSend(id,text.trim(),mentions,thread){text="";mentions=emptyList();vm.saveChatComposer(draftKey,"")}},enabled=!state.busy&&canPost){Icon(Icons.Outlined.Send,"Send message")}
          }
        }
    }
    if(settings)ConversationSettings(vm,state,conversation,{settings=false})
    reportId?.let{messageId->ReportReason({reason->vm.reportChat(messageId,when(reason){"HARASSMENT"->"ABUSE";"UNSAFE"->"SAFETY";else->reason});reportId=null},{reportId=null})}
    helpMessage?.let{message->HelpComposer(vm,state,null,{helpMessage=null},message.getJSONObject("payload").optString("text"))}
    if(mentionPicker){val policy=state.chatPolicies.firstOrNull{it.getJSONObject("body").getString("id")==id};AlertDialog(onDismissRequest={mentionPicker=false},title={Text("Mention members")},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())){policy?.getJSONObject("body")?.getJSONArray("members")?.objects()?.filter{it.isNull("removedAt")&&ChatProtocol.participant(it.getJSONObject("profile"))!=state.chatProfile?.let{p->ChatProtocol.participant(p)}}?.forEach{member->val person=member.getJSONObject("profile");val personId=ChatProtocol.participant(person);Row(verticalAlignment=Alignment.CenterVertically){Checkbox(personId in mentions,{selected->mentions=if(selected)(mentions+personId).distinct().take(8)else mentions-personId});Text(person.getJSONObject("body").getString("name"))}}}},confirmButton={TextButton(onClick={mentionPicker=false}){Text("Done")}})}
}
@Composable fun ChannelCreate(create:(String,String,String,String)->Unit,close:()->Unit,busy:Boolean){
    var name by rememberSaveable{mutableStateOf("")};var visibility by rememberSaveable{mutableStateOf("OPEN")}
    var mode by rememberSaveable{mutableStateOf("DISCUSSION")};var approval by rememberSaveable{mutableStateOf(true)}
    AlertDialog(onDismissRequest=close,title={Text("Create channel")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(name,{name=it.take(48)},label={Text("Channel name")},singleLine=true);listOf("OPEN" to "Open nearby","INVITE" to "Private · Invitation required").forEach {(value,label)->Row(Modifier.fillMaxWidth().clickable{visibility=value},verticalAlignment=Alignment.CenterVertically){RadioButton(visibility==value,{visibility=value});Text(label)}};Row(verticalAlignment=Alignment.CenterVertically){Switch(mode=="ANNOUNCEMENT",{mode=if(it)"ANNOUNCEMENT"else"DISCUSSION"});Text("Announcements with thread replies",Modifier.padding(start=8.dp))};Row(verticalAlignment=Alignment.CenterVertically){Checkbox(approval,{approval=it});Text("Approve requests before admission")};Text("Up to 16 members. Admins can review requests; the owner distributes fresh private keys. Independent private-group security review remains pending.",style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(onClick={create(name.trim(),visibility,mode,if(visibility=="OPEN")if(approval)"APPROVAL_ONLY"else"OPEN" else if(approval)"INVITE_PLUS_APPROVAL"else"INVITE_AUTO")},enabled=!busy&&name.isNotBlank()){Text("Create")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable fun JoinInvite(vm:SaathiViewModel,link:String="",accept:(String)->Unit,close:()->Unit,busy:Boolean){
    var value by remember(link){mutableStateOf(link)}
    var scanner by remember{mutableStateOf(false)};var reviewed by remember{mutableStateOf<JSONObject?>(null)};var error by remember{mutableStateOf<String?>(null)};var checking by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    val approval=reviewed?.getJSONObject("body")?.optString("kind")=="CHAT_ADMISSION"
    AlertDialog(onDismissRequest=close,title={Text(if(reviewed==null)"Join with invite"else"Review invitation")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(reviewed==null){Text("Use the signed invitation made for your chat identity.");OutlinedTextField(value,{value=it.take(44000);reviewed=null;error=null},label={Text("Paste CJP Swarm invitation")},maxLines=4);OutlinedButton(onClick={scanner=true}){Icon(Icons.Outlined.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text("Scan QR code")}}
        else {val b=reviewed!!.getJSONObject("body");val p=if(approval)b else b.getJSONObject("policy").getJSONObject("body");Text("# "+p.getString("name"),style=MaterialTheme.typography.titleLarge);Text(if(approval)"Approval required · No history or keys before admission"else"Recipient-bound invitation");Text("Invited by "+(b.optJSONObject("issuer")?:p.getJSONObject("owner")).getJSONObject("body").getString("name"));Text("Expires "+timeLabel(b.getString("expiresAt")));TextButton(onClick={reviewed=null}){Text("Change invitation")}}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={if(reviewed!=null)accept(value)else scope.launch{checking=true;error=null;try{reviewed=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.chat.decodeInvite(value)}}catch(_:Exception){error="This invitation could not be verified. Check its expiry and intended recipient, or ask for a new one."}finally{checking=false}}},enabled=value.isNotBlank()&&!busy&&!checking){Text(if(checking)"Checking…"else if(reviewed==null)"Review invitation"else if(approval)"Request to join"else"Join channel")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
    if(scanner)InviteScanner({value=it;reviewed=null;error=null;scanner=false},{scanner=false})
}
@Composable private fun ConversationSettings(vm:SaathiViewModel,state:AppState,c:JSONObject,close:()->Unit){
    val id=c.getString("id");val channel=c.getString("type")=="CHANNEL"
    val policy=state.let {state.chatPolicies.firstOrNull {it.getJSONObject("body").getString("id")==id}}
    var invitation by remember{mutableStateOf<String?>(null)};var confirm by remember{mutableStateOf<String?>(null)};var reportPerson by remember{mutableStateOf(false)}
    val clipboard=LocalClipboardManager.current;val context=LocalContext.current
    val owner=policy?.getJSONObject("body")?.getJSONObject("owner")?.let {ChatProtocol.participant(it)}==state.chatProfile?.let {ChatProtocol.participant(it)}
    val capabilities=state.channelCapabilities(id)
    AlertDialog(onDismissRequest=close,title={Text(if(channel)"Channel settings" else "Conversation settings")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(c.getString("title"),style=MaterialTheme.typography.titleLarge)
        if(!channel)Text("Voice and video calls are available when this person shares a compatible local Wi-Fi connection. Call buttons appear when that connection is ready.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={vm.muteChat(id)}){Text(if(c.optBoolean("muted"))"Unmute notifications" else "Mute notifications")}
        if(!channel)TextButton(onClick={vm.blockChat(c.getString("peerId"))}){Text(if((c.getString("peerId") in state.chatBlocks))"Unblock person" else "Block person")}
        if(!channel)TextButton({reportPerson=true}){Text("Report person")}
        if(!channel || !c.optBoolean("joined"))TextButton(onClick={confirm="CLEAR"}){Text("Clear local message copies")}
        if(channel && policy!=null){
            Text(if(policy.getJSONObject("body").getString("visibility")=="INVITE")"Invite only · Encrypted content" else "Open nearby · Member-readable content",style=MaterialTheme.typography.bodySmall)
            Text("Members",style=MaterialTheme.typography.titleMedium)
            policy.getJSONObject("body").getJSONArray("members").objects().filter {it.isNull("removedAt")}.forEach {member->val person=member.getJSONObject("profile");val personId=ChatProtocol.participant(person);Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(person.getJSONObject("body").getString("name"));Text(member.getString("role").lowercase().replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.bodySmall)};if(capabilities?.optBoolean("canManageMembers")==true && member.getString("role")!="OWNER" && (owner||member.getString("role")!="ADMIN"))ChannelMemberMenu(vm,state,id,personId,owner,{confirm=personId})}}
            if(capabilities?.optBoolean("canManageMembers")==true){val requests=state.chatJoinInbox.filter{it.getJSONObject("request").getJSONObject("body").getString("channelId")==id && !it.optBoolean("resolved") && java.time.Instant.parse(it.getJSONObject("request").getJSONObject("body").getString("expiresAt"))>java.time.Instant.now()};Text("Join requests · ${requests.size}",style=MaterialTheme.typography.titleMedium);requests.forEach{request->val person=request.getJSONObject("request").getJSONObject("body").getJSONObject("participant");Text(person.getJSONObject("body").getString("name"));FlowRow{TextButton(onClick={vm.moderateChannel(id,"APPROVE_JOIN",ChatProtocol.participant(person))},enabled=!state.busy){Text("Approve")};TextButton(onClick={vm.moderateChannel(id,"REJECT_JOIN",ChatProtocol.participant(person))},enabled=!state.busy){Text("Reject")}}}}
            if(capabilities?.optBoolean("canModerate")==true){val reports=state.channelReports(id);Text("Reports for review · ${reports.size}",style=MaterialTheme.typography.titleMedium);Text("Reports arrive when online. Review actions can be shared nearby. Private message text stays in this conversation.",style=MaterialTheme.typography.bodySmall);reports.forEach{r->Text(r.getString("reason").lowercase().replaceFirstChar{it.uppercase()});TextButton({vm.moderateChannel(id,"REVIEW_REPORT",r.getString("messageId"))},enabled=!state.busy && state.chatMessages.any{it.getString("id")==r.getString("messageId")}){Text("Mark reviewed")}}}
            if(owner){val config=policy.getJSONObject("body").optJSONObject("settings");val mode=config?.optString("mode")?:"DISCUSSION";val admission=config?.optString("admission")?:if(policy.getJSONObject("body").getString("visibility")=="OPEN")"OPEN"else"INVITE_AUTO";Row(verticalAlignment=Alignment.CenterVertically){Text("Announcement timeline",Modifier.weight(1f));Switch(mode=="ANNOUNCEMENT",{vm.configureChannel(id,if(it)"ANNOUNCEMENT"else"DISCUSSION",admission)},enabled=!state.busy)};Row(verticalAlignment=Alignment.CenterVertically){Text("Approval before admission",Modifier.weight(1f));Switch(admission in listOf("APPROVAL_ONLY","INVITE_PLUS_APPROVAL"),{vm.configureChannel(id,mode,if(policy.getJSONObject("body").getString("visibility")=="OPEN")if(it)"APPROVAL_ONLY"else"OPEN" else if(it)"INVITE_PLUS_APPROVAL"else"INVITE_AUTO")},enabled=!state.busy)}}
            if(capabilities?.optBoolean("canManageMembers")==true)policy.getJSONObject("body").optJSONArray("bannedIds")?.strings()?.forEach{personId->Row(verticalAlignment=Alignment.CenterVertically){Text("Banned identity "+personId.take(12),Modifier.weight(1f));TextButton(onClick={vm.moderateChannel(id,"UNBAN",personId)},enabled=!state.busy){Text("Unban")}}}
            if(capabilities?.optBoolean("canInvite")==true){Text("Invite a known person",style=MaterialTheme.typography.titleMedium);state.chatContacts.filter {it.getString("id")!=ChatProtocol.participant(state.chatProfile!!)}.forEach {person->TextButton(onClick={vm.invitePerson(id,person.getJSONObject("profile")){invitation=it}},enabled=!state.busy){Text(person.getJSONObject("profile").getJSONObject("body").getString("name"))}}}
            if(invitation!=null){
                val link=invitation!!
                Text("Recipient-only invitation · Expires within six hours",style=MaterialTheme.typography.bodySmall)
                val qr=remember(link){runCatching{require(link.toByteArray().size<=1800);val matrix=MultiFormatWriter().encode(link,BarcodeFormat.QR_CODE,600,600);Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply{for(y in 0 until 600)for(x in 0 until 600)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}.getOrNull()}
                if(qr!=null)Image(qr.asImageBitmap(),"Invitation QR for the selected person",Modifier.fillMaxWidth().aspectRatio(1f))
                else Text("This channel’s invitation is too large for a dependable QR. Share the link instead.",style=MaterialTheme.typography.bodySmall)
                FlowRow{TextButton(onClick={clipboard.setText(AnnotatedString(link));vm.notice("Recipient-only invitation copied.")}){Text("Copy link")};TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,link)},"Share Swarm invitation"))}){Text("Share link")};if(state.chatPeer!=null)TextButton(onClick={vm.sendNearbyInvite(link)}){Text("Send nearby")}}
            }
            Text("Huddles and group video are not available yet.",style=MaterialTheme.typography.bodySmall)
            if(c.optBoolean("joined"))TextButton(onClick={confirm=if(owner)"DELETE" else "LEAVE"}){Text(if(owner)"Delete channel" else "Leave channel")}
            Text("Removing someone stops future authorized delivery after members receive the new policy. It cannot erase earlier copies; disconnected members may keep old access until the six-hour policy expires.",style=MaterialTheme.typography.bodySmall)
            if(capabilities?.optBoolean("canModerate")==true){Text("Moderation actions",style=MaterialTheme.typography.titleMedium);state.chatActions.filter{it.getJSONObject("envelope").getJSONObject("body").getString("channelId")==id}.takeLast(12).forEach{row->val a=row.getJSONObject("envelope").getJSONObject("body");Text(a.getString("action").lowercase().replace('_',' ')+" · "+if(row.optBoolean("rejected"))"Not accepted: "+row.optString("rejection") else if(row.optBoolean("serverSaved"))"Confirmed online" else "Saved here · Confirmation pending",style=MaterialTheme.typography.bodySmall)}}
        }
    }},confirmButton={TextButton(onClick=close){Text("Done")}})
    if(reportPerson)ReportReason({reason->vm.reportPerson(c.getString("peerId"),when(reason){"HARASSMENT"->"ABUSE";"UNSAFE"->"SAFETY";else->reason});reportPerson=false},{reportPerson=false})
    confirm?.let {action->AlertDialog(onDismissRequest={confirm=null},title={Text(when(action){"CLEAR"->"Clear local copies?";"DELETE"->"Delete this channel?";"LEAVE"->"Leave this channel?";else->"Remove this member?"})},text={Text(if(action=="CLEAR")"These message copies will be removed from this phone. Synchronized history may return when you reconnect. Copies held elsewhere are unaffected." else "Saved copies already received remain on their holders’ devices. A new membership version takes effect as devices reconnect.")},confirmButton={TextButton(onClick={when(action){"CLEAR"->vm.clearChat(id);"DELETE"->vm.deleteChannel(id);"LEAVE"->vm.leaveChat(id);else->vm.removeChatMember(id,action)};confirm=null}){Text("Confirm")}},dismissButton={TextButton(onClick={confirm=null}){Text("Cancel")}})}
}
@Composable private fun ChannelMemberMenu(vm:SaathiViewModel,state:AppState,channel:String,person:String,owner:Boolean,remove:()->Unit){
    var menu by remember{mutableStateOf(false)};var ban by remember{mutableStateOf(false)}
    Box{IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"Manage channel member")};DropdownMenu(menu,{menu=false}){
        DropdownMenuItem(text={Text("Remove")},onClick={menu=false;remove()})
        DropdownMenuItem(text={Text("Ban identity")},onClick={menu=false;ban=true})
        (if(owner)listOf("ADMIN","MODERATOR","MEMBER","READ_ONLY")else listOf("MODERATOR","MEMBER","READ_ONLY")).forEach{role->DropdownMenuItem(text={Text("Role: "+role.lowercase().replace('_',' '))},onClick={menu=false;vm.moderateChannel(channel,"SET_ROLE",person,role)},enabled=!state.busy)}
    }}
    if(ban)AlertDialog(onDismissRequest={ban=false},title={Text("Ban this identity?")},text={Text("Renaming will not bypass this ban. Shared copies already held cannot be erased. Offline confirmation may remain pending.")},confirmButton={TextButton(onClick={ban=false;vm.moderateChannel(channel,"BAN",person)}){Text("Ban identity")}},dismissButton={TextButton(onClick={ban=false}){Text("Cancel")}})
}
@Composable fun MoreScreen(vm:SaathiViewModel,state:AppState,team:()->Unit,saved:()->Unit,connection:()->Unit,modifier:Modifier){
    var name by rememberSaveable(state.chatProfile?.getJSONObject("body")?.optString("name")){mutableStateOf(state.chatProfile?.getJSONObject("body")?.optString("name")?:"")}
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
        item {Heading("More","Your profile, relief work and saved information.")}
        if(BuildConfig.CHAT_ENABLED)item {Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Nearby profile",style=MaterialTheme.typography.titleLarge);OutlinedTextField(name,{name=it.take(32)},Modifier.fillMaxWidth(),label={Text("Display name")},singleLine=true);Button(onClick={vm.chatName(name)},enabled=name.isNotBlank()&&!state.busy){Text("Save name")};Text("People see this chosen name after a confirmed connection. It is not a verified volunteer identity.",style=MaterialTheme.typography.bodySmall);state.chatProfile?.let {Text("Chat identity "+ChatProtocol.participant(it).chunked(8).joinToString(" "),style=MaterialTheme.typography.bodySmall)}}}
        item {HorizontalDivider();Text("Appearance",style=MaterialTheme.typography.titleLarge);listOf("SYSTEM" to "System","LIGHT" to "Light","DARK" to "Dark").forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{vm.preference("appearance",value)},verticalAlignment=Alignment.CenterVertically){RadioButton(state.preferences.optString("appearance","SYSTEM")==value,{vm.preference("appearance",value)});Text(label)}}}
        item {HorizontalDivider();Text("Privacy & Nearby",style=MaterialTheme.typography.titleLarge);Row(verticalAlignment=Alignment.CenterVertically){Text("Nearby visibility",Modifier.weight(1f));Switch(state.preferences.optBoolean("nearbyVisible",true),{vm.preference("nearbyVisible",it)})};Text("Searching remains a one-minute, foreground action. Turning visibility off ends nearby discovery and its active connection.",style=MaterialTheme.typography.bodySmall)}
        item {Text("Help Swarm send public updates online",style=MaterialTheme.typography.titleLarge);Text("Your device can carry authenticated reports for others. Choose when it may use your data.",style=MaterialTheme.typography.bodyMedium);listOf("OFF" to "Off","WIFI" to "Wi-Fi only","ANY" to "Wi-Fi + mobile data").forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{vm.preference("relay",value)},verticalAlignment=Alignment.CenterVertically){RadioButton(state.preferences.optString("relay","OFF")==value,{vm.preference("relay",value)});Text(label)}};Row(verticalAlignment=Alignment.CenterVertically){Text("Relay public media",Modifier.weight(1f));Switch(state.preferences.optBoolean("mediaRelay"),{vm.preference("mediaRelay",it)})}}
        item {Text("Storage & data",style=MaterialTheme.typography.titleLarge);var limit by remember(state.preferences.optInt("dailyLimitMiB",50)){mutableFloatStateOf(state.preferences.optInt("dailyLimitMiB",50).toFloat())};Text("Daily relay limit · ${limit.toInt()} MB");Slider(limit,{limit=it},valueRange=1f..500f,onValueChangeFinished={vm.preference("dailyLimitMiB",limit.toInt())});var battery by remember(state.preferences.optInt("batteryMinimum",20)){mutableFloatStateOf(state.preferences.optInt("batteryMinimum",20).toFloat())};Text("Pause relay below ${battery.toInt()}% battery");Slider(battery,{battery=it},valueRange=10f..80f,onValueChangeFinished={vm.preference("batteryMinimum",battery.toInt())});Text("Saved media · "+(state.files.sumOf{it.optLong("size")}/1048576)+" MB",style=MaterialTheme.typography.bodySmall);Text("Relay allowance reserved today · "+state.relayReservedBytes/1048576+" MB",style=MaterialTheme.typography.bodySmall);Text("The allowance includes response limits and request overhead; retries count. Private files and media waiting to upload are kept.",style=MaterialTheme.typography.bodySmall);TextButton({vm.clearSafeMedia()},enabled=!state.busy){Text("Clear safe public media caches")}}
        item {HorizontalDivider();TextButton(onClick=team){Icon(Icons.Outlined.VerifiedUser,null);Spacer(Modifier.width(8.dp));Text(if(state.preparation==null)"Team sign in" else "My relief team")};TextButton(onClick=saved){Icon(Icons.Outlined.Inventory2,null);Spacer(Modifier.width(8.dp));Text("Saved relief work and earlier messages")};TextButton(onClick=connection){Icon(Icons.Outlined.Link,null);Spacer(Modifier.width(8.dp));Text("Connection options")}}
        item {HorizontalDivider();Text(BuildConfig.BRAND_DISPLAY,style=MaterialTheme.typography.headlineMedium);Text(BuildConfig.BRAND_BYLINE);Text("Connect nearby. Coordinate together.");Text("Developed by Cockroach Janta Party",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall);Text("Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.ENVIRONMENT}",style=MaterialTheme.typography.bodySmall);Text("Chat is an experimental addition. It has not received an independent cryptographic review. Native 1:1 local Wi-Fi calls passed the earlier two-device checks; huddles remain disabled.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

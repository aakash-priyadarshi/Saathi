package org.saathi.android

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

private fun statementStatus(row:JSONObject):String {
    row.optString("rejectedReason").takeIf{it.isNotBlank()}?.let{return "Not accepted: $it"}
    return when(row.optJSONObject("receipt")?.getJSONObject("body")?.getString("status")){"PUBLISHED"->"Published";"ACCEPTED"->"Received online · Awaiting review";"INVALIDATED"->"Withdrawn or removed";"REJECTED"->"Not published";else->if(row.has("sharedAt"))"Shared with nearby Swarm"else"Saved on your phone"}
}
@Composable fun NeedsHub(vm:SaathiViewModel,state:AppState,open:(JSONObject)->Unit,wide:Boolean,modifier:Modifier,official:(String)->Unit){
    var help by rememberSaveable{mutableStateOf(false)}
    Column(modifier){Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!help,{help=false},label={Text("Official Needs")});FilterChip(help,{help=true},label={Text("Nearby Help")})};if(help)HelpScreen(vm,state,Modifier.weight(1f),official)else NeedsScreen(state,vm,open,wide,Modifier.weight(1f))}
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun HelpScreen(vm:SaathiViewModel,state:AppState,modifier:Modifier,official:(String)->Unit){
    var edit by remember{mutableStateOf<JSONObject?>(null)};var create by rememberSaveable{mutableStateOf(false)};var flag by remember{mutableStateOf<String?>(null)}
    var area by rememberSaveable{mutableStateOf("")}
    LazyColumn(modifier,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Heading("Nearby Help","Temporary help from people nearby. These requests are unverified and separate from official donation needs.");Button({create=true},enabled=!state.busy){Icon(Icons.Outlined.Add,null);Text("Request Help")};OutlinedTextField(area,{area=it.take(80)},label={Text("Public area to check online")},modifier=Modifier.fillMaxWidth());TextButton({vm.checkHelpArea(area.trim())},enabled=!state.busy && CommunityProtocol.publicText(area.trim(),80)){Text("Check requests in this area")}}
        items(state.localHelp,key={it.getString("id")}){row->val b=row.getJSONObject("envelope").getJSONObject("body");val h=b.getJSONObject("payload").getJSONObject("help");val id=b.getString("objectId");val status=if(row.optBoolean("expired"))"EXPIRED"else h.getString("status");val active=status in listOf("OPEN","RESPONDER_ASSIGNED")
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("${h.getString("category").replace('_',' ')} · ${h.getInt("quantity")}",style=MaterialTheme.typography.titleMedium);Text(h.getString("details"));Text("${h.getString("area")} · ${b.getJSONObject("author").getJSONObject("body").getString("name")}",style=MaterialTheme.typography.bodySmall);Text("${status.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}} · Updated ${timeLabel(b.getString("createdAt"))}",style=MaterialTheme.typography.bodySmall);Text(statementStatus(row),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    if(row.optBoolean("owned") && active){TextButton({edit=row;create=true},enabled=!state.busy){Text("Update request")};TextButton({vm.helpStatus(id,"RESOLVED")},enabled=!state.busy){Text("Resolved")};TextButton({vm.helpStatus(id,"CANCELLED")},enabled=!state.busy){Text("Cancel request")}}
                    if(!row.optBoolean("owned") && status=="OPEN")TextButton({vm.offerHelp(id)},enabled=!state.busy && vm.community.offers(id).none{ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author"))==ChatProtocol.participant(vm.chat.profile())}){Text("I can help")}
                    if(!row.optBoolean("owned")){TextButton({vm.blockChat(ChatProtocol.participant(b.getJSONObject("author")))},enabled=!state.busy){Text("Block person")};TextButton({flag=row.getString("id")}){Text("Report")}}
                    if(state.account?.optString("role")=="ADMIN")TextButton({vm.hideHelp(id)},enabled=!state.busy){Text("Remove request")}
                }
                if(row.optBoolean("owned") && status=="OPEN")vm.community.offers(id).filter{it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getString("requestHash")==row.getString("hash")}.forEach{offer->val author=offer.getJSONObject("envelope").getJSONObject("body").getJSONObject("author");TextButton({vm.helpStatus(id,"RESPONDER_ASSIGNED",ChatProtocol.participant(author))},enabled=!state.busy){Text("Accept help from ${author.getJSONObject("body").getString("name")}")}}
                if(status=="RESPONDER_ASSIGNED")Text("A responder is assigned. The requester confirms resolution.",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
        if(state.localHelp.isEmpty())item{EmptyState("No nearby help requests yet","Create a request or connect nearby to receive temporary requests.",Icons.Outlined.VolunteerActivism)}
        if(state.preparation!=null){val groups=state.localHelp.filter{!it.optBoolean("expired") && it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getString("status")=="OPEN"}.groupBy{val h=it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help");h.getString("category") to h.getString("area")};groups.filterValues{it.size>=2}.forEach{(key,rows)->item{Text("${rows.size} nearby people requested ${key.first.lowercase().replace('_',' ')} near ${key.second}");TextButton({vm.aggregateHelp(key.first,key.second,rows,official)},enabled=!state.busy){Text("Review an Official Need")}}}}
    }
    if(create)HelpComposer(vm,state,edit,{create=false;edit=null})
    flag?.let{id->ReportReason({reason->vm.flagStatement(id,reason);flag=null},{flag=null})}
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun HelpComposer(vm:SaathiViewModel,state:AppState,existing:JSONObject?,done:()->Unit,seed:String=""){
    val original=existing?.getJSONObject("envelope")?.getJSONObject("body")?.getJSONObject("payload")?.getJSONObject("help")
    var category by rememberSaveable{mutableStateOf(original?.getString("category")?:"WATER")};var audience by rememberSaveable{mutableStateOf(original?.getString("audience")?:"MYSELF")};var quantity by rememberSaveable{mutableStateOf(original?.getInt("quantity")?.toString()?:"1")};var details by rememberSaveable{mutableStateOf(original?.getString("details")?:seed.take(400))};var area by rememberSaveable{mutableStateOf(original?.getString("area")?:"")};var priority by rememberSaveable{mutableStateOf(original?.getString("priority")?:"NORMAL")}
    AlertDialog(onDismissRequest=done,title={Text(if(existing==null)"Request nearby help"else"Update your request")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Use a public landmark. Remove names, contact details, precise coordinates and home addresses before sharing.",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){CommunityProtocol.categories.forEach{value->FilterChip(category==value,{category=value},label={Text(value.lowercase().replace('_',' '))})}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(audience=="MYSELF",{audience="MYSELF"},label={Text("Myself")});FilterChip(audience=="GROUP",{audience="GROUP"},label={Text("My group")})}
        OutlinedTextField(quantity,{quantity=it.take(4)},label={Text("Quantity")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(details,{details=it.take(400)},label={Text("Details")},modifier=Modifier.fillMaxWidth());OutlinedTextField(area,{area=it.take(80)},label={Text("Approximate public area")},modifier=Modifier.fillMaxWidth());FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("NORMAL","IMPORTANT","URGENT").forEach{p->FilterChip(priority==p,{priority=p},label={Text(p.lowercase())})}};if(priority=="URGENT")Text("Choose urgent only for immediate safety or medical assistance.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({val h=obj("category" to category,"audience" to audience,"quantity" to quantity.toInt(),"details" to details.trim(),"area" to area.trim(),"priority" to priority,"status" to (original?.getString("status")?:"OPEN"),"responderId" to original?.optString("responderId")?.takeIf{it!="null" && it.isNotBlank()});vm.helpRequest(h,existing?.getJSONObject("envelope")?.getJSONObject("body")?.getString("objectId"),done)},enabled=!state.busy && quantity.toIntOrNull() in 1..1000 && CommunityProtocol.publicText(details.trim(),400) && CommunityProtocol.publicText(area.trim(),80)){Text("Save and share")}},dismissButton={TextButton(done){Text("Cancel")}})
}
@Composable fun ReportReason(choose:(String)->Unit,done:()->Unit){AlertDialog(onDismissRequest=done,title={Text("Report for review")},text={Column{listOf("SPAM" to "Spam","HARASSMENT" to "Harassment","UNSAFE" to "Unsafe content","OTHER" to "Other").forEach{(value,label)->TextButton({choose(value)},Modifier.fillMaxWidth()){Text(label)}};Text("A report requests review; it does not remove copies from other phones.",style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(done){Text("Cancel")}})}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun UpdatesHub(vm:SaathiViewModel,state:AppState,modifier:Modifier,verified:()->Unit,discuss:(JSONObject)->Unit){
    var compose by rememberSaveable{mutableStateOf(false)};var flag by remember{mutableStateOf<String?>(null)};var viewMedia by remember{mutableStateOf<JSONObject?>(null)};val context=LocalContext.current
    Column(modifier){FlowRow(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({compose=true},enabled=!state.busy && BuildConfig.CHAT_ENABLED){Icon(Icons.Outlined.Add,null);Text("Create Update")};if(state.preparation!=null)TextButton(verified){Text("Verified volunteer update")}}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item{Heading("Updates","Chronological field reports. Participant reports describe what people saw and are unverified.");Freshness(state.savedAt)}
            val local=state.participantReports;val remote=state.posts.filter{p->vm.community.publicPostVisible(p.getString("id")) && !vm.chat.blocked(p.optString("participantId")) && local.none{it.getString("id")==p.getString("id")}};val combined=(local.map{it.getJSONObject("envelope").getJSONObject("body").getString("createdAt") to it}+remote.map{it.getString("createdAt") to it}).sortedByDescending{it.first}
            items(combined,key={it.second.getString("id")}){(_,row)->val participant=row.has("envelope");val b=if(participant)row.getJSONObject("envelope").getJSONObject("body")else row;val payload=if(participant)b.getJSONObject("payload")else row;var reveal by rememberSaveable(row.getString("id")){mutableStateOf(!payload.optBoolean("contentWarning"))}
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(participant || row.optString("verificationState")=="PARTICIPANT")"Participant report · Unverified"else"Verified volunteer update",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text(payload.getString("caption"),style=MaterialTheme.typography.bodyLarge);Text(if(participant)"${b.getJSONObject("author").getJSONObject("body").getString("name")} · ${payload.getString("area")}"else"${row.getJSONObject("author").getString("displayName")} · ${row.getJSONObject("reliefPoint").getString("publicLocation")}",style=MaterialTheme.typography.bodySmall)
                    Text("Created ${timeLabel(b.getString("createdAt"))}",style=MaterialTheme.typography.bodySmall);if(Instant.parse(b.getString("createdAt"))<Instant.now().minusSeconds(86400))Text("Conditions may have changed since this report.",style=MaterialTheme.typography.bodySmall)
                    if(participant){Text(statementStatus(row),style=MaterialTheme.typography.labelMedium);row.optJSONObject("receipt")?.getJSONObject("body")?.let{r->Text("Received online ${timeLabel(r.optString("receivedAt",r.getString("recordedAt")))}",style=MaterialTheme.typography.bodySmall);r.optString("publishedAt").takeIf{it.isNotBlank()}?.let{Text("Published ${timeLabel(it)}",style=MaterialTheme.typography.bodySmall)}}
                        if(payload.optBoolean("contentWarning") && !reveal)TextButton({reveal=true}){Text("Content warning · View media")}
                        if(reveal && row.has("thumbnail")){val bitmap=remember(row.optString("thumbnail")){runCatching{val bytes=Protocol.decode(row.getString("thumbnail"));BitmapFactory.decodeByteArray(bytes,0,bytes.size)}.getOrNull()};bitmap?.let{Image(it.asImageBitmap(),"Prepared report media preview",Modifier.fillMaxWidth().heightIn(max=240.dp))}}
                        val media=payload.optJSONObject("media");if(media!=null)Text(if(row.optBoolean("mediaOnline"))"Media reached CJP Swarm online"else if(state.files.firstOrNull{it.getString("id")==media.getString("id")}?.optBoolean("complete")==true)"Media saved on this phone"else"Media waiting for a nearby transfer",style=MaterialTheme.typography.bodySmall)
                        FlowRow{if(media!=null && reveal){TextButton({viewMedia=row},enabled=state.files.any{it.getString("id")==media.getString("id") && it.optBoolean("complete")}){Text(if(media.getString("mime")=="video/mp4")"Play saved video"else"View saved photo")};TextButton({vm.shareReportMedia(row.getString("id"))},enabled=!state.busy && state.confirmed){Text("Share media nearby")}};if(row.optBoolean("owned"))TextButton({vm.withdrawReport(row.getString("id"))},enabled=!state.busy){Text("Withdraw") }else{TextButton({flag=row.getString("id")}){Text("Report")};TextButton({vm.blockChat(ChatProtocol.participant(b.getJSONObject("author")))},enabled=!state.busy){Text("Block person")}}}
                    }else{row.optString("receivedAt").takeIf{it.isNotBlank()}?.let{Text("Received online ${timeLabel(it)}",style=MaterialTheme.typography.bodySmall)};if(payload.optBoolean("contentWarning") && !reveal)TextButton({reveal=true}){Text("Content warning · View media")};if(reveal)row.optJSONArray("media")?.objects()?.forEach{m->TextButton({runCatching{val raw=m.getString("url");val location=if(raw.startsWith("/api/v1/public/media/"))vm.repository.configuration!!.getJSONObject("body").getJSONArray("apiEndpoints").getString(0).trimEnd('/')+raw else raw;require(location.startsWith("https://") || BuildConfig.ENVIRONMENT=="development" && location.startsWith("http://127.0.0.1:"));context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(location)))}.onFailure{vm.notice("Published media could not be opened. Try again when connected.")}}){Text(if(m.getString("mimeType").startsWith("video/"))"View published video"else"View published photo")}};TextButton({discuss(row)}){Text("Discuss in a channel")}}
                    HorizontalDivider()
                }
            }
            if(combined.isEmpty())item{EmptyState("No saved field reports","Create an update now. It can be shared nearby and reach public review later.",Icons.Outlined.Feed)}
        }
    }
    if(compose)UpdateComposer(vm,state){compose=false};flag?.let{id->ReportReason({reason->vm.flagStatement(id,reason);flag=null},{flag=null})}
    viewMedia?.let{ReportMediaDialog(vm,it){viewMedia=null}}
}
@Composable private fun ReportMediaDialog(vm:SaathiViewModel,row:JSONObject,done:()->Unit){
    val context=LocalContext.current;var prepared by remember{mutableStateOf<FieldDerivative?>(null)};var videoFile by remember{mutableStateOf<File?>(null)};var error by remember{mutableStateOf<String?>(null)};var player by remember{mutableStateOf<android.widget.VideoView?>(null)}
    LaunchedEffect(row.getString("id")){try{val report=vm.community.reports().firstOrNull{it.getString("id")==row.getString("id")}?:error("This report was withdrawn or expired.");val m=report.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("media");val safe=withContext(Dispatchers.IO){val folder=File(context.cacheDir,"field-processing").apply{mkdirs()};val input=File.createTempFile("view-source-",if(m.getString("mime")=="video/mp4")".mp4"else".jpg",folder);try{val bytes=vm.session.readSavedBytes(m.getString("id"));require(Protocol.digest(bytes)==m.getString("hash") && bytes.size==m.getInt("size"));input.writeBytes(bytes);FieldMedia.prepare(context,Uri.fromFile(input),m.getString("mime")=="video/mp4")}finally{input.delete()}};if(safe.mime=="video/mp4")videoFile=withContext(Dispatchers.IO){File.createTempFile("view-safe-",".mp4",File(context.cacheDir,"field-processing")).apply{writeBytes(safe.bytes)}};prepared=safe}catch(e:Exception){error=e.message?:"This saved media could not be opened."}}
    DisposableEffect(Unit){onDispose{player?.stopPlayback();videoFile?.delete()}}
    AlertDialog(onDismissRequest=done,title={Text("Saved report media")},text={Column{error?.let{Text(it,color=MaterialTheme.colorScheme.error)};if(prepared==null && error==null)Text("Preparing saved media…");prepared?.let{safe->if(safe.mime=="image/jpeg"){val bitmap=remember(safe){BitmapFactory.decodeByteArray(safe.bytes,0,safe.bytes.size)};bitmap?.let{Image(it.asImageBitmap(),"Saved participant report photo",Modifier.fillMaxWidth().heightIn(max=400.dp))}}else videoFile?.let{file->androidx.compose.ui.viewinterop.AndroidView(factory={android.widget.VideoView(it).apply{player=this;setVideoPath(file.path);setOnPreparedListener{pause();seekTo(1)};setMediaController(android.widget.MediaController(it))}},modifier=Modifier.fillMaxWidth().height(280.dp));Text("Tap the video for playback controls. Audio plays only when you start it.",style=MaterialTheme.typography.bodySmall)}}}},confirmButton={TextButton(done){Text("Close")}})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun UpdateComposer(vm:SaathiViewModel,state:AppState,done:()->Unit){
    val context=LocalContext.current;var caption by rememberSaveable{mutableStateOf("")};var area by rememberSaveable{mutableStateOf("")};var warning by rememberSaveable{mutableStateOf(false)};var uri by rememberSaveable{mutableStateOf<String?>(null)};var video by rememberSaveable{mutableStateOf(false)};var capture by rememberSaveable{mutableStateOf<String?>(null)};var requestCamera by remember{mutableStateOf<()->Unit>({})}
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){selected->if(selected!=null){runCatching{context.contentResolver.takePersistableUriPermission(selected,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)};uri=selected.toString();video=context.contentResolver.getType(selected)?.startsWith("video/")==true}}
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->if(ok){uri=capture;video=false}}
    val cameraVideo=rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()){ok->if(ok){uri=capture;video=true}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)requestCamera()else vm.notice("Camera permission was denied. You can choose media from the gallery.")}
    fun camera(isVideo:Boolean){val launch={val directory=File(context.cacheDir,"field-capture").apply{mkdirs();listFiles()?.forEach{it.delete()}};val file=File.createTempFile("capture-",if(isVideo)".mp4"else".jpg",directory);val target=FileProvider.getUriForFile(context,BuildConfig.APPLICATION_ID+".files",file);capture=target.toString();if(isVideo)cameraVideo.launch(target)else photo.launch(target)};requestCamera=launch;if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)launch()else permission.launch(Manifest.permission.CAMERA)}
    AlertDialog(onDismissRequest={if(!state.busy)done()},title={Text("Create participant update")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Describe what you saw. Participant reports are unverified. Public publication requires review. Remove personal details; selected media is processed on your phone before sharing.",style=MaterialTheme.typography.bodySmall);OutlinedTextField(caption,{caption=it.take(2000)},label={Text("What is happening?")},modifier=Modifier.fillMaxWidth());OutlinedTextField(area,{area=it.take(80)},label={Text("Public area or landmark")},modifier=Modifier.fillMaxWidth());FlowRow{TextButton({gallery.launch(arrayOf("image/*","video/mp4"))},enabled=!state.busy){Text("Gallery")};TextButton({camera(false)},enabled=!state.busy){Text("Camera photo")};TextButton({camera(true)},enabled=!state.busy){Text("Camera video")}}
        uri?.let{Text(if(video)"Video selected · Up to 60 seconds, 1080p and 16 MB"else"Photo selected · Metadata will be removed",style=MaterialTheme.typography.bodySmall);TextButton({uri=null}){Text("Remove media")}};Row{Checkbox(warning,{warning=it});Text("Content warning for graphic imagery",Modifier.padding(top=12.dp))}
        if(state.busy)Text(if(video)"Optimising video…"else"Preparing update…",style=MaterialTheme.typography.labelMedium)
    }},confirmButton={TextButton({vm.participantReport(caption.trim(),area.trim(),warning,uri?.let{Uri.parse(it)},video){File(context.cacheDir,"field-capture").listFiles()?.forEach{it.delete()};done()}},enabled=!state.busy && CommunityProtocol.publicText(caption.trim(),2000) && CommunityProtocol.publicText(area.trim(),80)){Text("Publish Update")}},dismissButton={TextButton(done,enabled=!state.busy){Text("Cancel")}})
}

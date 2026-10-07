package org.saathi.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var model: SaathiViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val fixtureScope=if(BuildConfig.DEBUG&&BuildConfig.ENVIRONMENT=="development")intent.getStringExtra("swarmUiFixture")?.takeIf{it.matches(Regex("test-ui-[a-z0-9-]{1,48}"))}else null
        setContent {
            val vm: SaathiViewModel = if(fixtureScope==null)viewModel() else viewModel(factory=object:androidx.lifecycle.ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=SaathiViewModel(application,fixtureScope,startServices=false) as T
            })
            val state by vm.state.collectAsStateWithLifecycle()
            model = vm
            SaathiTheme(state.preferences.optString("appearance","SYSTEM")) {SwarmStartup(savedInstanceState==null && fixtureScope==null){SaathiApp(vm)}}
        }
    }
    override fun onStop() { model?.foregroundLost(); super.onStop() }
    override fun onStart() { super.onStart(); model?.foregroundActive() }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);model?.receiveInvite(intent.dataString)}
}

// Hidden for the Oct 2026 build: Updates and Needs tabs (only Chats, Nearby and More are shown). Restore the full list below to bring them back.
// private val tabs = listOf(Triple("Updates", Icons.Outlined.Feed, "Public field updates"),Triple("Chats",Icons.Outlined.ChatBubbleOutline,"Conversations"),Triple("Nearby",Icons.Outlined.Groups,"Nearby people and channels"),Triple("Needs",Icons.Outlined.VolunteerActivism,"Relief needs"),Triple("More",Icons.Outlined.MoreHoriz,"Profile and saved work"))
private val tabs = listOf(Triple("Chats",Icons.Outlined.ChatBubbleOutline,"Conversations"),Triple("Nearby",Icons.Outlined.Groups,"Nearby people and channels"),Triple("More",Icons.Outlined.MoreHoriz,"Profile and settings"))
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SaathiApp(vm: SaathiViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Chats") }; var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var conversation by rememberSaveable{mutableStateOf<String?>(null)};var createChannel by rememberSaveable{mutableStateOf(false)};var invite by rememberSaveable{mutableStateOf(false)}
    var form by rememberSaveable { mutableStateOf<String?>(null) }; var logout by remember { mutableStateOf(false) }
    LaunchedEffect(state.needsEnabled) {
        if (!state.needsEnabled) {
            if (page == "Needs") page = "Chats"
            detail = null
            val draftId = form?.takeIf { it.startsWith("draft:") }?.substringAfter(':')
            val draftType = draftId?.let { vm.repository.store.get("drafts", it)?.optString("draftType") }
            if (form == "request" || form?.startsWith("update:") == true || draftType == "request") form = null
        }
    }
    // Hidden for the Oct 2026 build: the Needs tab is not in `tabs`, so the needsEnabled filter is not needed.
    // val visibleTabs = if (state.needsEnabled) tabs else tabs.filterNot { it.first == "Needs" }
    val visibleTabs = tabs
    val openChat:(String)->Unit={conversation=it;page="Chats";detail=null;form=null}
    var pendingPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) pendingPermission?.invoke() else vm.notice("Permission was not granted. Saved work remains available. You can allow access in Android settings when ready.")
        pendingPermission = null
    }
    // Ask once for message notifications (Android 13+).
    val notifyContext = LocalContext.current
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(notifyContext, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) { pendingPermission = {}; permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) } }
    val askNearby: (Boolean) -> Unit = { advertise ->
        pendingPermission = { vm.scan(advertise,page=="Nearby"&&!advertise) }
        val needed = buildList { if (Build.VERSION.SDK_INT >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES) else addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        permissions.launch(needed.toTypedArray())
    }
    val askBle: (Boolean) -> Unit = { advertise ->
        pendingPermission = { vm.scanBle(advertise) }
        val needed = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        permissions.launch(needed.toTypedArray())
    }
    val askCall: (Boolean, Boolean) -> Unit = { video, incoming -> pendingPermission = { vm.call(video, incoming) }; permissions.launch((if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))) }
    val askRecord:()->Unit={pendingPermission={vm.notice("Microphone allowed. Hold the mic to talk.")};permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))}
    val navigateBack: () -> Unit = { if (form != null) form = null else if (detail != null) detail = null else if(conversation!=null)conversation=null else page = "Chats" }
    BackHandler(page != "Chats" || detail != null || form != null || conversation!=null, onBack = navigateBack)
    val context=LocalContext.current
    LaunchedEffect(Unit){vm.receiveInvite((context as? MainActivity)?.intent?.dataString)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val largeNavigationText = LocalDensity.current.fontScale >= 1.5f
        val inConversation = conversation != null && form == null && detail == null
        Row {
            if (wide) NavigationRail(Modifier.fillMaxHeight().width(104.dp).statusBarsPadding(), containerColor = MaterialTheme.colorScheme.surface) {
                Image(painterResource(R.drawable.swarm_mark), BuildConfig.BRAND_NAME, Modifier.padding(vertical = 24.dp).size(40.dp).clip(RoundedCornerShape(8.dp)))
                visibleTabs.forEach { (name, icon, description) -> NavigationRailItem(page == name, { page = name; detail = null; form = null;conversation=null }, { Icon(icon, description) }, label = { Text(name) }) }
            }
            Scaffold(modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background,
                // The SWARM by CJP lockup, 40 dp so the bar keeps its height; a conversation shows its own bar instead (like WhatsApp).
                topBar = { if (!inConversation) TopAppBar(title = {
                    Image(painterResource(R.drawable.swarm_header), "${BuildConfig.BRAND_DISPLAY} ${BuildConfig.BRAND_BYLINE}", Modifier.height(40.dp).clip(RoundedCornerShape(8.dp)))
                }, navigationIcon = { if (detail != null || form != null || conversation!=null || page in listOf("Team","Saved","Connection")) IconButton(onClick = navigateBack) { Icon(Icons.Outlined.ArrowBack, "Back") } },
                    // Hidden for the Oct 2026 build: Team sign in / My team top-bar action.
                    // actions = { if(!inConversation) TextButton(onClick = { page = "Team"; detail = null; form = null;conversation=null }) { Text(if (state.preparation == null) "Team sign in" else "My team") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
                bottomBar = { if (!wide && !inConversation) NavigationBar(modifier=Modifier.heightIn(min=if(largeNavigationText)112.dp else 80.dp),containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) { visibleTabs.forEach { (name, icon, description) -> NavigationBarItem(page == name, { page = name; detail = null; form = null;conversation=null }, { Icon(icon, "$name · $description") }, label = { Text(name, maxLines=if(largeNavigationText)2 else 1, overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center) }) } } }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    val environmentLabel = when (BuildConfig.ENVIRONMENT) {
                        "development" -> "DEVELOPMENT · Local services"
                        "staging" -> "STAGING · QA services"
                        else -> null
                    }
                    if (!inConversation) environmentLabel?.let { label -> Surface(color = MaterialTheme.colorScheme.errorContainer) { Text(label, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer) } }
                    if(!inConversation) ConnectionStatus(state)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { notice -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text(notice, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall); IconButton(onClick = { vm.notice(null) }) { Icon(Icons.Outlined.Close, "Dismiss message") } } } }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        val content = Modifier.widthIn(max = 1000.dp).fillMaxSize()
                        when {
                            form != null -> DraftForm(vm, form!!, { form = null }, content)
                            detail != null -> RequestDetail(vm, JSONObject(detail!!), { form = "update:" + detail!! }, content,{need->vm.discuss(obj("type" to "NEED","id" to need.getString("publicId"),"title" to need.getString("title")),openChat)})
                            conversation!=null -> ConversationScreen(vm,state,conversation!!,askCall,askRecord,{message->if(state.needsEnabled)vm.needFromChat(message){form=it}else vm.notice("Public relief needs are temporarily paused.")},{id->if(!state.needsEnabled)vm.notice("Public relief needs are temporarily paused.")else state.requests.plus(state.completed).firstOrNull{it.optString("publicId")==id}?.let{detail=it.toString()}?:vm.notice("Refresh Needs to check this reference’s latest public status.")},content,back=navigateBack)
                            // Hidden for the Oct 2026 build: Needs and Updates pages.
                            // page == "Needs" && state.needsEnabled -> NeedsHub(vm,state,{detail=it.toString()},wide,content,{form=it})
                            // page == "Needs" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Notice("Relief needs are paused", "Connect to the internet later to check when this feature is available again.", Icons.Outlined.VolunteerActivism) }
                            // page == "Updates" -> UpdatesHub(vm,state,content,{page="Team";form="field"},{post->vm.discuss(obj("type" to "UPDATE","id" to post.getString("id"),"title" to post.getString("caption").take(120)),openChat)})
                            page == "Chats" -> ChatsScreen(vm,state,openChat,{page="Nearby"},{createChannel=true},content)
                            page == "Nearby" -> NearbyPeopleScreen(vm,state,askNearby,openChat,{createChannel=true},{invite=true},{page="Connection"},content)
                            page == "Connection" -> NearbyScreen(vm, state, askNearby, askBle, askCall, content)
                            // Hidden for the Oct 2026 build: there is no team sign-in, so More has no route into Team or Saved.
                            // page == "More" -> MoreScreen(vm,state,{page="Team"},{page="Saved"},{page="Connection"},content)
                            page == "More" -> MoreScreen(vm,state,{},{},{page="Connection"},content)
                            // Hidden for the Oct 2026 build: Saved and Team pages. Any other page falls back to Chats.
                            // page == "Saved" -> SavedScreen(vm, state, { form = it }, content)
                            // else -> TeamScreen(vm, state, { form = it }, { logout = true }, content)
                            else -> ChatsScreen(vm,state,openChat,{page="Nearby"},{createChannel=true},content)
                        }
                    }
                }
            }
        }
    }
    if(createChannel)ChannelCreate({name,visibility,mode,admission->vm.createChannel(name,visibility,mode,admission){createChannel=false;openChat(it)}},{createChannel=false},state.busy)
    if(invite || state.incomingInvite!=null)JoinInvite(vm,state.incomingInvite?:"",{link->vm.joinInvite(link){invite=false;vm.dismissInvite();openChat(it)}},{invite=false;vm.dismissInvite()},state.busy)
    state.pairCode?.let { code -> AlertDialog(onDismissRequest = { vm.confirmPair(false) }, title = { Text("Compare both device codes") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(code, style = MaterialTheme.typography.headlineMedium); Text("Accept only when the other device shows this same code. A connection does not verify volunteer identity.") } }, confirmButton = { TextButton(onClick = { vm.confirmPair(true) }) { Text("Codes match") } }, dismissButton = { TextButton(onClick = { vm.confirmPair(false) }) { Text("Decline") } }) }
    if (state.localCode.isNotBlank() && !state.confirmed) AlertDialog(onDismissRequest = { vm.disconnect() }, title = { Text("Compare both device codes") }, text = { Column { Text(state.localCode, style = MaterialTheme.typography.headlineMedium); Text("Confirm this same code with the other person before sharing.") } }, confirmButton = { TextButton(onClick = { vm.confirmLocal() }) { Text("Codes match") } }, dismissButton = { TextButton(onClick = { vm.disconnect() }) { Text("Decline") } })
    state.fileOffer?.let { offer -> AlertDialog(onDismissRequest = { vm.declineFile() }, title = { Text("Receive a nearby file?") }, text = { Text("${offer.getString("name")} · ${fileSize(offer.getLong("size"))}\n\nOnly accept files from someone you trust. Received files stay private on this phone.") }, confirmButton = { TextButton(onClick = { vm.acceptFile() }) { Text("Receive") } }, dismissButton = { TextButton(onClick = { vm.declineFile() }) { Text("Decline") } }) }
    state.incomingCall?.let { video -> AlertDialog(onDismissRequest = { vm.hangup() }, title = { Text(if (video) "Nearby video call" else "Nearby voice call") }, text = { Text("The connected person would like to call. Microphone${if (video) " and camera" else ""} access starts only when you accept.") }, confirmButton = { TextButton(onClick = { askCall(video, true) }) { Text("Accept") } }, dismissButton = { TextButton(onClick = { vm.hangup() }) { Text("Decline") } }) }
    // Hidden for the Oct 2026 build: sign-out dialog (there is no team sign-in; only the hidden Team page opened it).
    // if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("Sign out and clear private work?") }, text = { Text("Drafts, messages, events, attachments and this phone’s signing identity will be cleared. Synchronize or share pending work first. Other carriers may retain events already shared.") }, confirmButton = { TextButton(onClick = { logout = false; vm.logout(false) }) { Text("Sign out and clear") } }, dismissButton = { TextButton(onClick = { logout = false }) { Text("Keep my work") } })
}

@Composable internal fun ConnectionStatus(state:AppState) {
    Surface(color=MaterialTheme.colorScheme.primaryContainer){Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Icon(if(state.reachable)Icons.Outlined.CloudDone else if(state.confirmed)Icons.Outlined.WifiTethering else Icons.Outlined.CloudOff,null,Modifier.size(18.dp))
        Text(if(state.reachable)"Connected · Saved work can sync" else if(state.confirmed)"Connected nearby · Saved work remains on this phone" else "Waiting for connection · Saved information still works",style=MaterialTheme.typography.bodySmall)
    }}
}

@Composable fun NeedsScreen(state: AppState, vm: SaathiViewModel, open: (JSONObject) -> Unit, wide: Boolean, modifier: Modifier) {
    var completed by rememberSaveable { mutableStateOf(false) }; var search by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("All") }
    val source = if (completed) state.completed else state.requests
    val needs = source.filter { (category == "All" || it.optString("category") == category.uppercase()) && (it.optString("title") + it.optJSONObject("reliefPoint")?.optString("publicLocation")).contains(search, true) }
    LazyVerticalGrid(columns = GridCells.Fixed(if (wide) 2 else 1), modifier = modifier, contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val invitation = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp)
                Text("A little help.", style = invitation)
                Text("Right where it’s needed.", style = invitation.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(if (completed) "Help that has arrived" else "What’s needed now", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = { vm.refresh() }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "Refresh relief needs") } }
            OutlinedTextField(search, { search = it }, label = { Text("Search needs or locations") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!completed, { completed = false }, label = { Text("Active needs") })
                FilterChip(completed, { completed = true }, label = { Text("Completed") })
                VerticalDivider(Modifier.height(24.dp))
                listOf("All", "Water", "Food", "Medical", "Hygiene", "Clothing", "Power", "Shelter", "Other").forEach { item -> FilterChip(category == item, { category = item }, label = { Text(item) }) }
            }
            Freshness(state.savedAt)
        } }
        items(needs, key = { it.getString("publicId") }) { need -> NeedCard(need) { open(need) } }
        if (needs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(if (source.isEmpty()) "No saved needs yet" else "No matching needs", if (source.isEmpty()) "Connect to Swarm and refresh to save verified relief needs on this phone." else "Try another search or category.", Icons.Outlined.VolunteerActivism) }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("Find a verified need, give what you can, and see your help arrive. Together, we look after each other.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item(span = { GridItemSpan(maxLineSpan) }) { Notice("Real needs. Verified teams.", "Check the request ID and its current status before arranging a delivery.", Icons.Outlined.VerifiedUser) }
    }
}
@Composable fun NeedCard(need: JSONObject, open: () -> Unit) {
    val completed = need.getString("status") == "COMPLETED"
    OutlinedCard(onClick = open, shape = RoundedCornerShape(12.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) { Text(need.getString("title"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(need.getInt(if (completed) "receivedQuantity" else "remainingQuantity").toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("${need.getString("unit")} ${if (completed) "received" else "still needed"}", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Text(need.getJSONObject("organization").getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(if (completed) "View impact" else "View need", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(need.getString("category"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); if (completed) Text("Help has arrived", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) else if (need.optString("priority") != "NORMAL") Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.errorContainer) { Text(if (need.optString("priority") == "URGENT") "Urgent need" else "High priority", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer) } }
            Text(need.getString("description"), maxLines = 3, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (need.getInt(if (completed) "receivedQuantity" else "committedQuantity").toFloat() / need.getInt("requestedQuantity").coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer)
            Text("${need.getInt(if (completed) "receivedQuantity" else "committedQuantity")} of ${need.getInt("requestedQuantity")} ${if (completed) "received" else "committed"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp)); Text(need.getJSONObject("reliefPoint").getString("publicLocation"), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable private fun FieldScreen(state: AppState, publish: () -> Unit, modifier: Modifier,discuss:(JSONObject)->Unit={}) {
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Heading("Updates", "Public field reports from the people on the ground."); Freshness(state.savedAt); if (state.preparation != null) TextButton(publish) { Icon(Icons.Outlined.Add, null); Text("Write a field update") } }
        items(state.posts, key = { it.getString("id") }) { post -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(timeLabel(post.getString("createdAt")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(post.getString("caption"), style = MaterialTheme.typography.bodyLarge); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(18.dp)); Text(post.getJSONObject("author").getString("displayName"), style = MaterialTheme.typography.bodySmall) }; Text(post.getJSONObject("reliefPoint").getString("publicLocation"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if(BuildConfig.CHAT_ENABLED)TextButton(onClick={discuss(post)}) { Text("Discuss in a channel") }; HorizontalDivider() } }
        if (state.posts.isEmpty()) item { EmptyState("No saved field updates", "Approved public updates will appear after a refresh.", Icons.Outlined.Feed) }
    }
}
@Composable fun Heading(title: String, text: String) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun EmptyState(title: String, text: String, icon: ImageVector) { Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleMedium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun Notice(title: String, text: String, icon: ImageVector) { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(text, style = MaterialTheme.typography.bodySmall) } } }
@Composable fun Freshness(saved: String?) { Text(if (saved == null) "No saved snapshot yet" else "Saved ${timeLabel(saved)} · Check with Swarm for the latest status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
fun timeLabel(value: String) = runCatching { DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("at an unknown time")
fun fileSize(bytes: Long) = when {
    bytes < 1024 -> "< 1 KB"
    bytes < 1048576 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

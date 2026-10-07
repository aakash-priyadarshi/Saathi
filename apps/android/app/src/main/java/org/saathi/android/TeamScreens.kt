package org.saathi.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

@Composable fun TeamScreen(vm: SaathiViewModel, state: AppState, form: (String) -> Unit, logout: () -> Unit, modifier: Modifier) {
    var email by rememberSaveable { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
    LaunchedEffect(state.account) { state.account?.optString("email")?.let { email = it } }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading(if (state.preparation == null) "Volunteer sign in" else "My relief team", "Verified teams keep Swarm’s needs accurate and help deliveries reach the right place.") }
        if (!state.authenticated) item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Notice(if (state.account == null) "Prepare before heading out" else "Sign in again", if (state.account == null) "Sign in while connected to prepare this phone for signed offline requests and field updates." else "Your saved work and signing identity stay on this phone. Sign in to the same account to reconnect.", Icons.Outlined.VerifiedUser)
                OutlinedTextField(email, { email = it.take(254) }, label = { Text("Email address") }, readOnly = state.account != null, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it.take(256) }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Authenticator code, if required") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.login(email.trim(), password, code); password = ""; code = "" }, enabled = email.isNotBlank() && password.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.account == null) "Sign in and prepare this phone" else "Sign in again and keep saved work") }
                Text("Public relief information is available without an account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.preparation != null) {
            item {
                val prepared = state.preparation
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Notice("Prepared for ${prepared.getJSONObject("user").getString("displayName")}", "Last prepared ${timeLabel(prepared.getString("preparedAt"))}. Offline work stays pending until Swarm checks your current authority.", Icons.Outlined.CheckCircle)
                    if (state.needsEnabled) Button(onClick = { form("request") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Create a relief request") }
                    OutlinedButton(onClick = { form("field") }, modifier = Modifier.fillMaxWidth()) { Text("Write a field update") }
                    TextButton(onClick = { vm.action { vm.repository.prepare(); vm.loadDashboard(); vm.notice("Offline preparation refreshed.") } }, enabled = !state.busy) { Text("Refresh preparation and deliveries") }
                }
            }
            if (state.needsEnabled) {
                item { Text("My requests", style = MaterialTheme.typography.titleLarge) }
                items(state.dashboard?.optJSONArray("requests")?.objects() ?: emptyList(), key = { it.getString("publicId") }) { need -> OutlinedCard(onClick = { form("update:" + need.toString()) }) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(need.getString("title"), style = MaterialTheme.typography.titleMedium); Text("${need.getString("publicId")} · ${need.getString("status")}", style = MaterialTheme.typography.bodySmall); Text("Edit a signed update", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) } } }
            }
            item { Text("Incoming deliveries", style = MaterialTheme.typography.titleLarge) }
            val deliveries = state.dashboard?.optJSONArray("deliveries")?.objects() ?: emptyList()
            items(deliveries, key = { it.getString("id") }) { delivery -> DeliveryCard(vm, delivery) }
            if (deliveries.isEmpty()) item { Text(if (state.dashboard == null) "Connect to refresh incoming deliveries." else "No incoming deliveries right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Notice("Lost phone or device change?", "A coordinator can suspend your account or revoke a prepared phone. Revocation is checked by Swarm when events arrive. Offline peers cannot prove current volunteer authority.", Icons.Outlined.Security) }
        }
        if (state.account != null) {
            item { Heading("Prepared phones", "Only revoke a phone you no longer use. Its pending updates may be rejected after revocation."); TextButton(onClick = { vm.listDevices() }, enabled = !state.busy) { Text("Review prepared phones") } }
            items(state.devices, key = { it.getString("id") }) { device -> PreparedPhone(vm, device, state.busy) }
            if (state.preparation == null) item { Notice("Signed in · Preparation needs attention", "Review old prepared phones if your account has reached its limit, then try preparing again.", Icons.Outlined.Security); Button(onClick = { vm.action { vm.repository.prepare(); vm.loadDashboard(); vm.notice("This phone is ready for offline publishing.") } }, enabled = !state.busy) { Text("Prepare this phone again") } }
            item { TextButton(onClick = logout, enabled = !state.busy) { Text("Sign out and review cleanup") } }
        }
    }
}
@Composable private fun PreparedPhone(vm: SaathiViewModel, device: JSONObject, busy: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    val current = vm.repository.store.get("account", "device")?.optString("id") == device.getString("id")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (current) "This phone" else "Phone prepared ${timeLabel(device.getString("createdAt"))}", style = MaterialTheme.typography.titleMedium)
        Text(if (device.isNull("revokedAt")) "Active · ${device.getString("id").take(8)}" else "Revoked", style = MaterialTheme.typography.bodySmall)
        if (device.isNull("revokedAt")) TextButton(onClick = { confirm = true }, enabled = !busy) { Text("Revoke this prepared phone") }
        HorizontalDivider()
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Revoke this phone?") }, text = { Text("Swarm will reject its new signed updates. ${if (current) "Prepare this phone again to rotate its identity." else "This is intended for a lost or retired phone."}") }, confirmButton = { TextButton(onClick = { confirm = false; vm.revokeDevice(device.getString("id")) }) { Text("Revoke phone") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep active") } })
}
@Composable private fun DeliveryCard(vm: SaathiViewModel, delivery: JSONObject) {
    var received by rememberSaveable(delivery.getString("id")) { mutableStateOf("") }; var confirmation by remember { mutableStateOf(false) }
    OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(delivery.getJSONObject("request").getString("title"), style = MaterialTheme.typography.titleMedium)
        Text("${delivery.getInt("quantity")} committed · ${delivery.getInt("receivedQuantity")} received\n${delivery.getString("status")}")
        val id = delivery.getString("id"); val status = delivery.getString("status")
        if (status == "ORDER_PLACED") Button(onClick = { confirmation = true }) { Text("Confirm delivery details") }
        if (status in listOf("VOLUNTEER_CONFIRMED", "IN_TRANSIT")) {
            OutlinedTextField(received, { received = it.filter(Char::isDigit).take(7) }, label = { Text("Quantity actually received") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { confirmation = true }, enabled = (received.toIntOrNull() ?: 0) in 1..(delivery.getInt("quantity") - delivery.getInt("receivedQuantity"))) { Text("Record received quantity") }
        }
        if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text(if (status == "ORDER_PLACED") "Confirm this delivery?" else "Confirm the quantity received?") }, text = { Text("This changes Swarm’s central delivery record. Only confirm details you have checked in person. An internet connection is required.") }, confirmButton = { TextButton(onClick = {
            confirmation = false
            vm.action {
                val body = if (status == "ORDER_PLACED") obj() else obj("quantity" to (received.toIntOrNull() ?: 0), "version" to delivery.getJSONObject("request").getInt("version"))
                val step = if (status == "ORDER_PLACED") "confirm" else "receive"
                vm.repository.write("$step/$id/${delivery.getInt("receivedQuantity")}/${delivery.getJSONObject("request").getInt("version")}", "/volunteer/deliveries/$id/$step", body, true); vm.loadDashboard(); vm.notice("Delivery confirmed by Swarm.")
            }
        }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirmation = false }) { Text("Cancel") } })
    } }
}
@Composable fun DraftForm(vm: SaathiViewModel, route: String, done: () -> Unit, modifier: Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val initial = remember(route) { if (route.startsWith("draft:")) vm.repository.store.get("drafts", route.substringAfter(':')) else null }
    val editing = remember(route) { if (route.startsWith("update:")) JSONObject(route.substringAfter(':')) else initial?.optJSONObject("editing") }
    val field = route == "field" || initial?.optString("draftType") == "field"
    val id = rememberSaveable(route) { initial?.getString("id") ?: UUID.randomUUID().toString() }
    var title by rememberSaveable(id) { mutableStateOf(initial?.optString("title") ?: editing?.optString("title") ?: "") }
    var description by rememberSaveable(id) { mutableStateOf(initial?.optString("description") ?: editing?.optString("description") ?: "") }
    var quantity by rememberSaveable(id) { mutableStateOf(initial?.optString("quantity") ?: editing?.optString("requestedQuantity") ?: "") }
    var unit by rememberSaveable(id) { mutableStateOf(initial?.optString("unit") ?: "bottles") }
    var category by rememberSaveable(id) { mutableStateOf(initial?.optString("category") ?: "WATER") }
    var priority by rememberSaveable(id) { mutableStateOf(initial?.optString("priority") ?: editing?.optString("priority") ?: "NORMAL") }
    var hours by rememberSaveable(id) { mutableStateOf(initial?.optString("hours") ?: "24") }
    val points = vm.repository.preparation?.getJSONArray("points")?.objects() ?: emptyList()
    var pointId by rememberSaveable(id) { mutableStateOf(initial?.optString("pointId") ?: points.firstOrNull()?.getString("id") ?: "") }
    var saved by remember { mutableStateOf(false) }
    // Originals stay on this phone until the signed text is accepted, then upload directly (never carried).
    var media by rememberSaveable(id) { mutableStateOf(initial?.optJSONArray("mediaUris")?.strings()?.joinToString("\n") ?: "") }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val chosen = uris.take(4).filter { uri ->
            val size = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { it.moveToFirst(); it.getLong(0) } ?: 0
            (size in 1..FieldMedia.MAX_BYTES).also { ok -> if (!ok) vm.notice("Each photo or video must be 250 MB or less.") }
        }
        chosen.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        media = chosen.joinToString("\n"); saved = false
    }
    fun draft() = obj("id" to id, "draftType" to if (field) "field" else "request", "title" to title, "description" to description, "quantity" to quantity, "unit" to unit, "category" to category, "priority" to priority, "hours" to hours, "pointId" to pointId).apply { if (field && media.isNotBlank()) put("mediaUris", JSONArray(media.split("\n"))); if (editing != null) put("editing", editing); if(initial?.optBoolean("fromChat")==true)put("fromChat",true) }
    fun save() { vm.action { vm.repository.store.put("drafts", id, draft()); saved = true; vm.notice("Draft saved on this phone.") } }
    val valid = if (field) description.trim().length in 5..4000 else title.trim().length in 3..100 && description.trim().length in 5..2000 && (quantity.toIntOrNull() ?: 0) in 1..1000000 && unit.trim().length in 1..30 && (hours.toIntOrNull() ?: 0) in 1..720
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading(if (field) "Write from the field" else if (editing != null) "Update a relief need" else "Create a relief need", "Save a draft anytime. Signing keeps your original authorship when someone carries this update nearby.") }
        if(initial?.optBoolean("fromChat")==true)item {Notice("Review before making this public","This draft copies a private message. Remove personal details, verify the need and choose its quantity and relief point before signing. Chat membership gives no relief publishing permission.",Icons.Outlined.VerifiedUser)}
        if (!field) item { OutlinedTextField(title, { title = it.take(100); saved = false }, label = { Text("What is needed?") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(description, { description = it.take(if (field) 4000 else 2000); saved = false }, label = { Text(if (field) "Field update" else "Description and delivery context") }, modifier = Modifier.fillMaxWidth(), minLines = 4) }
        if (field) item {
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (media.isBlank()) "Add photos or videos" else "${media.split("\n").size} selected · Change") }
            Text("Up to 4 files, 250 MB each, any video length. They upload after Swarm accepts the text and publish without approval. Check faces before sharing.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!field) {
            item { OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit).take(7); saved = false }, label = { Text("Total quantity needed") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true) }
            if (editing == null) {
                item { OutlinedTextField(unit, { unit = it.take(30); saved = false }, label = { Text("Unit, such as bottles or meals") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { Text("Category", style = MaterialTheme.typography.titleMedium); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("WATER", "FOOD", "MEDICAL", "HYGIENE", "CLOTHING", "POWER", "SHELTER", "OTHER").forEach { value -> FilterChip(category == value, { category = value; saved = false }, label = { Text(value.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                item { OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3); saved = false }, label = { Text("Needed within how many hours? (1–720)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true) }
            }
            item { Text("Priority", style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("NORMAL", "HIGH", "URGENT").forEach { value -> FilterChip(priority == value, { priority = value; saved = false }, label = { Text(value.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
        }
        if (editing == null) item { Text("Relief point", style = MaterialTheme.typography.titleMedium); if (points.isEmpty()) Text("Prepare this phone while signed in to select an approved relief point.", color = MaterialTheme.colorScheme.onSurfaceVariant); points.forEach { point -> Row { RadioButton(pointId == point.getString("id"), { pointId = point.getString("id"); saved = false }); Text("${point.getString("name")} · ${point.getString("publicLocation")}", Modifier.padding(top = 12.dp)) } } }
        item { OutlinedButton(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Draft saved on this phone" else "Save draft on this phone") } }
        item { Button(onClick = {
            val point = points.firstOrNull { it.getString("id") == pointId }
            val organization = if (editing != null) vm.repository.preparation?.getJSONArray("organizations")?.objects()?.firstOrNull { it.getString("name") == editing.getJSONObject("organization").getString("name") }?.getString("id") ?: "" else point?.getString("organizationId") ?: ""
            val payload = when {
                field -> obj("caption" to description.trim(), "reliefPointId" to pointId, "mediaIds" to JSONArray())
                editing != null -> obj("publicId" to editing.getString("publicId"), "changes" to obj("version" to editing.getInt("version"), "title" to title.trim(), "description" to description.trim(), "requestedQuantity" to quantity.toInt(), "priority" to priority))
                else -> obj("title" to title.trim(), "description" to description.trim(), "requestedQuantity" to quantity.toInt(), "unit" to unit.trim(), "category" to category, "priority" to priority, "deadline" to Instant.now().plusSeconds(hours.toLong() * 3600).toString(), "reliefPointId" to pointId)
            }
            vm.publish(id, draft(), if (field) "FIELD_PUBLISHED" else if (editing != null) "REQUEST_UPDATED" else "REQUEST_CREATED", payload, organization, done)
        }, enabled = valid && !state.busy && vm.repository.preparation != null && (pointId.isNotBlank() || editing != null), modifier = Modifier.fillMaxWidth()) { Text("Sign and save update") }; Text("This saves a pending signed update. Swarm checks your authority and conflicts before publication. Request changes require a current version.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun RequestDetail(vm: SaathiViewModel, need: JSONObject, edit: () -> Unit, modifier: Modifier,discuss:(JSONObject)->Unit={}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val intent = "reserve/${need.getString("publicId")}"
    val original = state.operations.firstOrNull { it.getString("id") == intent }
    var quantity by rememberSaveable(intent) { mutableStateOf(original?.getJSONObject("body")?.optString("quantity") ?: "") }; var email by rememberSaveable(intent) { mutableStateOf(original?.getJSONObject("body")?.optString("email") ?: "") }; var reservation by remember(intent) { mutableStateOf(original?.optJSONObject("response")) }
    var current by remember { mutableStateOf<JSONObject?>(null) }
    val request = current ?: need
    val status = request.getString("status")
    val verified = request.getJSONObject("organization").getBoolean("verified")
    val closed = status in listOf("DRAFT", "COMPLETED", "CANCELLED", "EXPIRED") || !runCatching { Instant.parse(request.getString("deadline")) > Instant.now() }.getOrDefault(false)
    val available = verified && !closed && request.getInt("remainingQuantity") > 0
    val stateLabel = when { !verified -> "Verification unavailable"; status == "COMPLETED" -> "Help has arrived"; status == "CANCELLED" -> "Request cancelled"; closed -> "Request closed"; request.getInt("remainingQuantity") == 0 -> "Fully committed · Delivery pending"; status == "PARTIALLY_RECEIVED" -> "Some supplies received · More help needed"; else -> "Open for contributions" }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading(request.getString("title"), request.getString("publicId")); Notice(stateLabel, request.getJSONObject("organization").getString("name"), Icons.Outlined.VerifiedUser) }
        if(BuildConfig.CHAT_ENABLED)item {TextButton(onClick={discuss(request)}){Icon(Icons.Outlined.ChatBubbleOutline,null);Spacer(Modifier.width(8.dp));Text("Open a separate discussion")};Text("Discussion does not change this verified need or confirm a delivery.",style=MaterialTheme.typography.bodySmall)}
        item { Text(request.getString("description")); Text(if (available) "${request.getInt("remainingQuantity")} ${request.getString("unit")} still needed" else "${request.getInt("receivedQuantity")} ${request.getString("unit")} received", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.headlineMedium); Text("${request.getInt("receivedQuantity")} received · ${request.getInt("committedQuantity")} committed", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        item { val point = request.getJSONObject("reliefPoint"); Heading(point.getString("name"), point.getString("publicLocation")); Text(point.optString("instructions"), Modifier.padding(top = 8.dp)); Text(point.optString("operatingHours"), style = MaterialTheme.typography.bodySmall) }
        item { Text("Needed by ${timeLabel(request.getString("deadline"))}", style = MaterialTheme.typography.bodySmall); Text("Last updated ${timeLabel(request.getString("updatedAt"))}", style = MaterialTheme.typography.bodySmall); OutlinedButton(onClick = { vm.action { current = JSONObject(vm.repository.api("/public/verify/${need.getString("publicId")}")); vm.notice("Current request checked with Swarm.") } }, enabled = !state.busy) { Text("Verify current status with Swarm") } }
        if (available) item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("I can help", style = MaterialTheme.typography.titleLarge)
                Text("Reserve only what you can provide. Swarm checks the latest available quantity before confirming. Delivery coordination requires a connection.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit).take(7) }, readOnly = original != null, label = { Text("Quantity you can provide") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it.take(254) }, readOnly = original != null, label = { Text("Email for updates (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (reservation == null) Button(onClick = { vm.action { reservation = vm.repository.write(intent, "/donations", original?.getJSONObject("body") ?: obj("publicId" to request.getString("publicId"), "quantity" to quantity.toInt()).apply { if (email.isNotBlank()) put("email", email.trim()) }); vm.notice("Reservation confirmed by Swarm. Keep your private tracking link.") } }, enabled = !state.busy && (original != null || (quantity.toIntOrNull() ?: 0) in 1..request.getInt("remainingQuantity"))) { Text(if (original == null) "Reserve with Swarm" else "Retry original reservation") }
            }
        }
        if (!available) item { Notice("Check before arranging supplies", if (!verified) "This team’s verification is unavailable. Check the current status with Swarm and choose another verified need." else if (status == "COMPLETED") "This need is fulfilled. View active needs to find where help is still needed." else if (closed) "This request is closed to new contributions. Choose an active verified need." else "All requested supplies are committed. Check the current status before arranging another delivery.", Icons.Outlined.Info) }
        reservation?.let { result -> item { DonationFollowUp(vm, result) } }
        if (!available && original != null && reservation == null) item { Text("An earlier reservation needs resolution. Retry its original details from Saved; do not create another reservation.", style = MaterialTheme.typography.bodySmall) }
        if (vm.repository.preparation != null) item { TextButton(onClick = edit) { Text("Edit a signed request update") } }
    }
}
@Composable fun DonationFollowUp(vm: SaathiViewModel, reservation: JSONObject) {
    val token = reservation.getString("trackingToken")
    val state by vm.state.collectAsStateWithLifecycle()
    val originalOrder = state.operations.firstOrNull { it.getString("id") == "order/$token" }
    var orderId by remember(token) { mutableStateOf(originalOrder?.getJSONObject("body")?.optString("externalOrderId") ?: "") }; var provider by remember(token) { mutableStateOf(originalOrder?.getJSONObject("body")?.optString("provider") ?: "Self delivery") }; var hours by remember(token) { mutableStateOf("1") }
    var tracking by remember { mutableStateOf<JSONObject?>(reservation.optJSONObject("tracking")) }
    suspend fun check() { tracking = JSONObject(vm.repository.api("/donations/tracking/$token")); vm.repository.store.put("donations", token, JSONObject(reservation.toString()).put("tracking", tracking)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Notice(tracking?.getString("status")?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Reserved with Swarm", "${reservation.getInt("quantity")} reserved · expires ${timeLabel(reservation.getString("expiresAt"))}. Your private contribution is saved on this phone.", Icons.Outlined.CheckCircle)
        TextButton(onClick = { vm.action { check(); vm.notice("Contribution checked with Swarm.") } }) { Text("Check current delivery status") }
        if ((tracking?.optString("status") ?: "RESERVED") == "RESERVED") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Self delivery", "Local shop", "Courier", "Zomato", "Swiggy", "Other").forEach { value -> FilterChip(provider == value, { provider = value }, label = { Text(value) }) } }
        OutlinedTextField(orderId, { orderId = it.take(100) }, readOnly = originalOrder != null, label = { Text("Order or delivery reference") }, modifier = Modifier.fillMaxWidth())
        if (originalOrder == null) OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3) }, label = { Text("Arriving within hours") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        else Text("Retrying the original delivery details and arrival time.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.action { vm.repository.write("order/$token", "/donations/tracking/$token/order", originalOrder?.getJSONObject("body") ?: obj("provider" to provider, "externalOrderId" to orderId, "eta" to Instant.now().plusSeconds(hours.toLong() * 3600).toString())); check(); vm.notice("Delivery details reached Swarm.") } }, enabled = !state.busy && orderId.isNotBlank() && (hours.toIntOrNull() ?: 0) > 0) { Text(if (originalOrder == null) "Send delivery details" else "Retry original delivery details") }
        TextButton(onClick = { vm.action { vm.repository.write("cancel/$token", "/donations/tracking/$token/cancel", obj()); check(); vm.notice("Reservation cancelled by Swarm.") } }, enabled = !state.busy) { Text("Cancel reservation") }
        }
    }
}

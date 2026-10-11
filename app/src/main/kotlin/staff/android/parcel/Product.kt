package staff.android.parcel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import org.json.JSONObject
import java.util.UUID
const val ABOUT="Parcel uploads an original 10,240-byte text document in 4,096-byte chunks. The service stores actual bytes and publishes only a complete verified object. This foreground lab does not schedule background work or access personal documents."
fun validateView(data:JSONObject){
    require(data.has("upload"))
    if (data.isNull("upload")) return
    val u = data.getJSONObject("upload")
    UUID.fromString(u.getString("id"))
    for (key in listOf("length", "offset")) require(u.get(key) is Int)
    require(u.getInt("length") in 1..65536 && u.getInt("offset") in 0..u.getInt("length"))
    require(u.getString("sha256").matches(Regex("[0-9a-f]{64}")))
    require(u.getString("status") in setOf("OPEN", "COMMITTED", "CANCELLED"))
}
@Composable fun Product(state: CaseState, model: CaseModel, elapsed: Long) {
    val source by model.uploadSource.collectAsState()
    val retrieved by model.retrieval.collectAsState()
    val automatic by model.automatic.collectAsState()
    val upload = state.data?.optJSONObject("upload")
    Text("YOUR TRANSFER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) { Text("TXT", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Field notes.txt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("10 KB · text document", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val offset = upload?.getInt("offset") ?: 0
            val length = upload?.getInt("length") ?: 10240
            val status = upload?.getString("status")
            Text(when { status == "COMMITTED" -> "Complete and verified"; status == "CANCELLED" -> "Upload cancelled"; state.pending != null -> "Waiting for confirmation"; automatic -> "Uploading your document"; upload == null -> "Ready to upload"; else -> "Upload paused" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { offset.toFloat() / length }, modifier = Modifier.fillMaxWidth())
            Text("$offset / $length bytes accepted", modifier = Modifier.testTag("offset"), style = MaterialTheme.typography.bodySmall)
            val owned = upload == null || source?.optString("uploadId") == upload.getString("id")
            if (!owned) Text("This transfer does not match the source saved on this device. Open Lab for details.")
            if (status == null || status == "OPEN") {
                if (automatic) OutlinedButton(onClick = model::pauseUpload, modifier = Modifier.fillMaxWidth().testTag("pause-upload")) { Text("Pause after current step") }
                else Button(onClick = model::uploadAll, enabled = ready(state) && state.problem == null && state.data != null && owned, modifier = Modifier.fillMaxWidth().testTag("upload-all")) { Text(if (upload == null) "Upload document" else "Resume upload") }
                if (upload != null && owned) TextButton(onClick = { model.pauseUpload(); model.send("cancel", JSONObject().put("uploadId", upload.getString("id"))) }, enabled = ready(state), modifier = Modifier.testTag("cancel")) { Text("Cancel upload") }
            }
            if (status == "COMMITTED" && owned) Button(onClick = model::retrieve, enabled = ready(state), modifier = Modifier.fillMaxWidth().testTag("retrieve")) { Text("Retrieve and verify copy") }
        }
    }
    Text("Your document stays on this device until the transfer is resolved. Only a complete, verified file becomes available to download.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.data == null) Text(if (state.busy) "Checking the transfer service…" else "Connect to the local transfer service, then refresh.")
    retrieved?.let { InfoCard("Retrieved copy", it) }
    Text("Original demo document · foreground transfer", style = MaterialTheme.typography.bodySmall)
}

@Composable fun ParcelLab(state: CaseState, model: CaseModel) {
    val source by model.uploadSource.collectAsState()
    val automatic by model.automatic.collectAsState()
    val upload = state.data?.optJSONObject("upload")
    Text("Manual protocol controls. Pause the ordinary transfer before advancing individual chunks.")
    if (automatic) OutlinedButton(onClick = model::pauseUpload) { Text("Pause automatic transfer") }
    if (upload == null) Button(onClick = model::createUpload, enabled = ready(state) && !automatic, modifier = Modifier.testTag("create")) { Text("Prepare upload") }
    else {
        Text("Upload ${upload.getString("id")}\n${upload.getInt("offset")} / ${upload.getInt("length")} bytes\n${upload.getString("sha256")}", style = MaterialTheme.typography.bodySmall)
        val owned = source?.optString("uploadId") == upload.getString("id")
        if (upload.getString("status") == "OPEN" && owned) {
            if (upload.getInt("offset") < upload.getInt("length")) Button(onClick = model::chunk, enabled = ready(state) && state.problem == null && !automatic, modifier = Modifier.testTag("chunk")) { Text("Send next chunk") }
            else Button(onClick = { model.send("complete", JSONObject().put("uploadId", upload.getString("id"))) }, enabled = ready(state) && !automatic, modifier = Modifier.testTag("complete")) { Text("Verify and publish") }
        }
        Text("Refresh probes the accepted offset. Resolve any saved request before continuing. Complete and cancelled uploads keep their durable identity.")
    }
}

package staff.android.parcel
import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class ProductJourneyTest {
 @get:Rule(order=0)val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_LOCAL_NETWORK)
 @get:Rule(order=1) val isolatedFiles = object : org.junit.rules.ExternalResource() {
     private val previous = mutableMapOf<String, ByteArray?>()
     override fun before() {
         for (name in listOf("upload-source.json", "upload-source.json.bak", "pending-command.json", "pending-command.json.bak", "received.txt")) {
             val file = File(context().filesDir, name)
             previous[name] = if (file.exists()) file.readBytes() else null
             file.delete()
         }
         UploadSource(context().filesDir).create() // A unique server upload per test; no global server reset.
         request("/__control", "{}")
     }
     override fun after() {
         for ((name, bytes) in previous) {
             val file = File(context().filesDir, name)
             if (bytes == null) file.delete() else file.writeBytes(bytes)
         }
         request("/__control", "{}")
     }
 }
 @get:Rule(order=2)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun completeCaseJourney(){try{
            waitTag("lab-tab");tap("lab-tab");waitTag("create");tap("create");waitTag("chunk");tap("chunk");waitReady()
            assertEquals(4096,current().getJSONObject("upload").getInt("offset"))
            request("/__control","{\"drop_reply\":true}");tap("lab-tab");tap("upload-all");waitTag("retry")
            assertEquals(8192,current().getJSONObject("upload").getInt("offset"))
            request("/__control","{}");tap("retry");waitReady()
            assertEquals(8192,current().getJSONObject("upload").getInt("offset"))
            tap("upload-all");waitTag("retrieve");tap("retrieve");waitReady()
            assertEquals(10240,File(context().filesDir,"received.txt").readBytes().size)
            assertEquals("COMMITTED",current().getJSONObject("upload").getString("status"))
 }finally{request("/__control","{}")}}
 @Test fun ordinaryUploadCreatesAndPauseRequiresExplicitResume() { try {
     waitTag("upload-all")
     // Execute admission and pause in the same main-thread turn, before any network callback.
     compose.runOnIdle {
         val model = androidx.lifecycle.ViewModelProvider(compose.activity)[CaseModel::class.java]
         model.uploadAll()
         assertEquals(true, model.automatic.value)
         model.pauseUpload()
         assertEquals(false, model.automatic.value)
         assertEquals(null, model.state.value.problem)
     }
     waitForPausedStep()
     compose.onNodeWithTag("upload-all").assertIsEnabled()
     assertEquals(true, JSONObject(File(context().filesDir,"upload-source.json").readText()).has("uploadId"))
     tap("upload-all"); waitTag("retrieve")
     assertEquals("COMMITTED", current().getJSONObject("upload").getString("status"))
     assertEquals(10240, current().getJSONObject("upload").getInt("offset"))
 } finally { request("/__control", "{}") } }
 @Test fun activityStopCallbackStopsAdmissionBeforeAnyReply() { try {
     waitTag("upload-all")
     // onStop is invoked synchronously in the same admission turn; no failure can mask it.
     compose.runOnIdle {
         val model = androidx.lifecycle.ViewModelProvider(compose.activity)[CaseModel::class.java]
         model.uploadAll()
         assertEquals(true, model.automatic.value)
         androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnStop(compose.activity)
         assertEquals(false, model.automatic.value)
         assertEquals(null, model.state.value.problem)
         androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnStart(compose.activity)
         androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnResume(compose.activity)
     }
     waitForPausedStep()
     compose.onNodeWithTag("upload-all").assertIsEnabled()
     tap("upload-all"); waitTag("retrieve")
     assertEquals("COMMITTED", current().getJSONObject("upload").getString("status"))
 } finally { request("/__control", "{}") } }
 private fun waitForPausedStep() {
     // Pause/onStop permit an admitted create to finish. Resume exists but is disabled meanwhile.
     waitReady()
     compose.runOnIdle {
         val model = androidx.lifecycle.ViewModelProvider(compose.activity)[CaseModel::class.java]
         assertEquals(false, model.automatic.value)
         assertEquals(null, model.state.value.pending)
         assertEquals(null, model.state.value.problem)
         model.state.value.data?.optJSONObject("upload")?.let { upload ->
             assertEquals(model.uploadSource.value!!.getString("uploadId"), upload.getString("id"))
             assertEquals("OPEN", upload.getString("status"))
             assertEquals(0, upload.getInt("offset")) // No chunk was admitted after pause/stop.
         }
     }
 }
 private fun waitTagWithoutReady(tag:String) { compose.waitUntil(45000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
 private fun context()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun current()=request("/view").getJSONObject("data")
 private fun command(action:String,args:JSONObject)=request("/commands",JSONObject().put("id",UUID.randomUUID().toString()).put("action",action).put("args",args).toString())
 private fun tap(tag:String){
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("refresh", "lab-tab")) node.performClick() else node.performScrollTo().performClick()
    }
 private fun waitTag(tag:String){compose.waitUntil(45000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};waitReady()}
 private fun waitReady(){compose.waitUntil(45000){compose.onAllNodesWithTag("busy").fetchSemanticsNodes().isEmpty()}}
 private fun request(path:String,body:String?=null):JSONObject{
  val connection=URL("http://10.0.2.2:8920$path").openConnection() as HttpURLConnection
  connection.connectTimeout=10000;connection.readTimeout=10000
  if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toByteArray())}}
  return try{JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
 }
}

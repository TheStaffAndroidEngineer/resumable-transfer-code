package staff.android.parcel

import android.app.Application
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CaseState(
    val data: JSONObject? = null,
    val busy: Boolean = true,
    val problem: String? = null,
    val pending: JSONObject? = null,
    val outcome: JSONObject? = null,
    val observedAt: Long = 0,
    val receivedElapsed: Long = 0,
    val storageBlocked: Boolean = false
)

class CaseApi {
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

    fun request(path: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url("http://10.0.2.2:8920$path")
        if (body != null)
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val out = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val bytes = ByteArray(4096)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    if (n > 262144 - out.size())
                        throw IOException("Response exceeds fixture contract")
                    out.write(bytes, 0, n)
                }
            }
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val result =
                JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString())
            if (!response.isSuccessful)
                throw IOException("Service ${response.code}: ${result.optString("error")}")
            require(result.getString("kind") == "parcel")
            return result
        }
    }
}

class CaseModel(application: Application) : AndroidViewModel(application) {
    private val api = CaseApi()
    private val source = UploadSource(application.filesDir)
    private val sourceMutable = MutableStateFlow<JSONObject?>(null)
    val uploadSource = sourceMutable.asStateFlow()
    private val retrievalMutable = MutableStateFlow<String?>(null)
    val retrieval = retrievalMutable.asStateFlow()

    fun retrieve() {
        if (!ready(state.value)) return
        val value = uploadSource.value ?: return
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client =
                        OkHttpClient.Builder()
                            .followRedirects(false)
                            .callTimeout(5, TimeUnit.SECONDS)
                            .build()
                    client
                        .newCall(
                            Request.Builder()
                                .url("http://10.0.2.2:8920/objects/${value.getString("uploadId")}")
                                .build())
                        .execute()
                        .use { response ->
                            require(response.code == 200)
                            val bytes =
                                response.body.byteStream().use { input ->
                                    val out = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(4096)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        require(n <= value.getInt("length") - out.size())
                                        out.write(buffer, 0, n)
                                    }
                                    out.toByteArray()
                                }
                            require(
                                bytes.size == value.getInt("length") &&
                                    UploadSource.sha(bytes) == value.getString("sha256"))
                            val target =
                                AtomicFile(
                                    File(getApplication<Application>().filesDir, "received.txt"))
                            val out = target.startWrite()
                            try {
                                out.write(bytes)
                                target.finishWrite(out)
                            } catch (e: Exception) {
                                target.failWrite(out)
                                throw e
                            }
                        }
                }
                retrievalMutable.value =
                    "Retrieved 10,240 bytes; whole-file digest matches the preserved source."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                retrievalMutable.value = "The complete object could not be retrieved and verified."
            } finally {
                mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun createUpload() {
        if (!ready(state.value)) return
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { source.read() ?: source.create() }
                sourceMutable.value = value
                mutable.value = state.value.copy(busy = false)
                send(
                    "create",
                    JSONObject()
                        .put("uploadId", value.getString("uploadId"))
                        .put("length", value.getInt("length"))
                        .put("sha256", value.getString("sha256")))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value =
                    state.value.copy(
                        busy = false, problem = "Source could not be preserved. Nothing was sent.")
            }
        }
    }

    fun chunk() {
        if (!ready(state.value)) return
        val row = state.value.data?.optJSONObject("upload") ?: return
        val value = uploadSource.value ?: return
        if (row.getString("id") != value.getString("uploadId")) return
        val bytes = android.util.Base64.decode(value.getString("data"), android.util.Base64.NO_WRAP)
        val offset = row.getInt("offset")
        if (offset >= bytes.size) return
        val part = bytes.copyOfRange(offset, minOf(offset + 4096, bytes.size))
        send(
            "chunk",
            JSONObject()
                .put("uploadId", row.getString("id"))
                .put("offset", offset)
                .put("data", android.util.Base64.encodeToString(part, android.util.Base64.NO_WRAP))
                .put("sha256", UploadSource.sha(part)))
    }

    private val journal = AtomicFile(File(application.filesDir, "pending-command.json"))
    private val mutable = MutableStateFlow(CaseState())
    val state = mutable.asStateFlow()

    private val automaticMutable = MutableStateFlow(false)
    val automatic = automaticMutable.asStateFlow()

    // This foreground coordinator only chooses the next existing durable operation.
    // It stops on uncertainty; retry never silently restarts the transfer loop.
    fun uploadAll() {
        if (!ready(state.value) || state.value.problem != null || state.value.data == null) return
        mutable.value = state.value.copy(outcome = null)
        automaticMutable.value = true
    }

    fun pauseUpload() { automaticMutable.value = false }

    init {
        viewModelScope.launch {
            combine(state, automatic) { snapshot, running -> snapshot to running }.collect { (snapshot, running) ->
                if (!running) return@collect
                if (snapshot.problem != null || snapshot.storageBlocked || snapshot.outcome?.optBoolean("accepted") == false) {
                    automaticMutable.value = false
                    return@collect
                }
                if (!ready(snapshot)) return@collect
                val row = snapshot.data?.optJSONObject("upload")
                if (row == null) {
                    createUpload()
                } else if (row.getString("status") != "OPEN") {
                    automaticMutable.value = false
                } else if (uploadSource.value?.optString("uploadId") != row.getString("id")) {
                    automaticMutable.value = false
                } else if (row.getInt("offset") < row.getInt("length")) {
                    chunk()
                } else {
                    send("complete", JSONObject().put("uploadId", row.getString("id")))
                }
            }
        }
    }

    // Work is serialized by the busy gate, including persistence and refresh.
    init {
        viewModelScope.launch {
            try {
                sourceMutable.value = withContext(Dispatchers.IO) { source.read() }
                val pending = withContext(Dispatchers.IO) { read() }
                mutable.value = state.value.copy(pending = pending)
                refreshInside()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false,
                        storageBlocked = true,
                        problem =
                            "Saved request could not be read. It is preserved for investigation.")
            }
        }
    }

    private fun read(): JSONObject? {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists())
            return null
        val wrapper = JSONObject(String(journal.readFully(), Charsets.UTF_8))
        require(wrapper.has("pending"))
        if (wrapper.isNull("pending")) return null
        val command = wrapper.getJSONObject("pending")
        require(UUID.fromString(command.getString("id")).toString() == command.getString("id"))
        require(command.getString("action").isNotBlank())
        command.getJSONObject("args")
        return command
    }

    private fun save(command: JSONObject?) {
        val output = journal.startWrite()
        try {
            output.write(
                JSONObject().put("pending", command ?: JSONObject.NULL).toString().toByteArray())
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            throw error
        }
    }

    fun refresh() {
        if (state.value.busy || state.value.storageBlocked) return
        mutable.value = state.value.copy(busy = true, problem = null)
        viewModelScope.launch { refreshInside() }
    }

    private suspend fun refreshInside() {
        try {
            val response =
                withContext(Dispatchers.IO) {
                    api.request(
                        uploadSource.value?.let { "/uploads/${it.getString("uploadId")}" }
                            ?: "/view")
                }
            val data = response.getJSONObject("data")
            validateView(data)
            data.optJSONObject("upload")?.let { row ->
                if (uploadSource.value != null) withContext(Dispatchers.IO) { source.observe(row) }
            }
            val time = response.get("observedAt")
            require(time is Long || time is Int)
            mutable.value =
                state.value.copy(
                    data = data,
                    observedAt = (time as Number).toLong(),
                    receivedElapsed = SystemClock.elapsedRealtime(),
                    busy = false,
                    problem = null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value =
                state.value.copy(
                    busy = false,
                    problem =
                        "Current state could not be checked. Any visible data is the previous observation.")
        }
    }

    fun send(action: String, args: JSONObject = JSONObject()) {
        if (state.value.busy || state.value.storageBlocked || state.value.pending != null) return
        val command =
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("action", action)
                .put("args", args)
        mutable.value = state.value.copy(busy = true, problem = null, outcome = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { save(command) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false, problem = "Request could not be saved. Nothing new was sent.")
                return@launch
            }
            mutable.value = state.value.copy(pending = command)
            deliver(command)
        }
    }

    fun retry() {
        val before = state.value
        if (before.busy || before.storageBlocked || before.pending == null) return
        mutable.value = before.copy(busy = true, problem = null)
        viewModelScope.launch { deliver(before.pending) }
    }

    private suspend fun deliver(command: JSONObject) {
        try {
            val receipt = withContext(Dispatchers.IO) { api.request("/commands", command) }
            require(receipt.getString("commandId") == command.getString("id"))
            val outcome = receipt.getJSONObject("outcome")
            require(outcome.get("accepted") is Boolean)
            if (!outcome.getBoolean("accepted")) {
                val reason = outcome.get("reason")
                require(reason is String && reason.isNotBlank())
            }
            withContext(Dispatchers.IO) { save(null) }
            mutable.value = state.value.copy(pending = null, outcome = outcome)
            // An idempotent receipt can be historical. Never publish receipt.data as current.
            refreshInside()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value =
                state.value.copy(
                    busy = false,
                    problem =
                        "This request is not acknowledged locally. Its identity is saved; retry the same request.")
        }
    }
}

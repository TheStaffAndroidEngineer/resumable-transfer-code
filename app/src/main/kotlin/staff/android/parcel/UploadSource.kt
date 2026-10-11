package staff.android.parcel

import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class UploadSource(directory: File) {
    private val file = AtomicFile(File(directory, "upload-source.json"))

    fun read(): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val value = JSONObject(String(file.readFully(), Charsets.UTF_8))
        UUID.fromString(value.getString("uploadId"))
        val bytes = Base64.decode(value.getString("data"), Base64.NO_WRAP)
        require(
            bytes.size == value.getInt("length") &&
                bytes.size in 1..65536 &&
                sha(bytes) == value.getString("sha256"))
        return value
    }

    fun create(): JSONObject {
        val bytes =
            ("Parcel field notes: preserve every accepted byte across a lost response.\n"
                    .repeat(160))
                .toByteArray()
                .copyOfRange(0, 10240)
        val value =
            JSONObject()
                .put("uploadId", UUID.randomUUID().toString())
                .put("length", bytes.size)
                .put("sha256", sha(bytes))
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val out = file.startWrite()
        try {
            out.write(value.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        return value
    }

    fun observe(row: JSONObject) {
        val source = read() ?: error("No saved source")
        require(
            row.getString("id") == source.getString("uploadId") &&
                row.getString("sha256") == source.getString("sha256") &&
                row.getInt("length") == source.getInt("length"))
        source.put("acceptedOffset", row.getInt("offset")).put("status", row.getString("status"))
        val out = file.startWrite()
        try {
            out.write(source.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        fun sha(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}

package be.alexander.papersnearme.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import be.alexander.papersnearme.BuildConfig
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads the files the nightly GitHub Action publishes. Model + vocab are fetched once,
 * manifest.json (a few KB) whenever it is older than [maxAgeMillis], and each field the
 * user picked when it is missing, or when it changed and we are on Wi-Fi (or forced).
 */
class IndexRepository(private val context: Context, private val baseUrl: String = BuildConfig.DATA_URL) {

    private val dir = File(context.filesDir, "index").apply { mkdirs() }
    private val fieldsDir = File(dir, "fields").apply { mkdirs() }
    val model = File(dir, "model.onnx")
    val vocab = File(dir, "vocab.txt")
    private val manifestFile = File(dir, "manifest.json")

    private val maxAgeMillis = 20L * 60 * 60 * 1000

    init {
        // Files from the first app version (one index for AI papers only).
        File(dir, "papers.json").delete()
        File(dir, "vectors.bin").delete()
    }

    fun ensureModel(onProgress: (String, Float?) -> Unit) {
        if (!model.exists()) {
            download("model.onnx", model) { done, total ->
                onProgress("Downloading the language model (once)…", fraction(done, total))
            }
        }
        if (!vocab.exists()) download("vocab.txt", vocab) { _, _ -> }
    }

    /** Fresh manifest if it is old or [force]; falls back to the saved one when offline. */
    fun manifest(force: Boolean): Manifest {
        val stale = !manifestFile.exists() || System.currentTimeMillis() - manifestFile.lastModified() > maxAgeMillis
        if (force || stale) {
            try {
                download("manifest.json", manifestFile) { _, _ -> }
            } catch (e: Exception) {
                if (!manifestFile.exists()) throw e
            }
        }
        return Manifest.parse(manifestFile.readText())
    }

    fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun papersFile(id: String) = File(fieldsDir, "papers-f$id.jsonl")
    fun vectorsFile(id: String) = File(fieldsDir, "vectors-f$id.bin")
    private fun shaFile(id: String) = File(fieldsDir, "f$id.sha256")

    fun hasField(id: String) = papersFile(id).exists() && vectorsFile(id).exists() && shaFile(id).exists()
    fun isCurrent(info: FieldInfo) = hasField(info.id) && shaFile(info.id).readText() == info.sha256

    /** Downloads one field, checks it against the manifest, then swaps it in. */
    fun downloadField(info: FieldInfo, onProgress: (Long, Long) -> Unit) {
        val gz = File(fieldsDir, info.papersFile + ".part")
        val vec = File(fieldsDir, info.vectorsFile + ".part")
        val jsonl = File(fieldsDir, "papers-f${info.id}.jsonl.part")
        try {
            download(info.papersFile, gz, commit = false) { done, _ -> onProgress(done, info.bytes) }
            val gzSize = gz.length()
            download(info.vectorsFile, vec, commit = false) { done, _ -> onProgress(gzSize + done, info.bytes) }

            val sha = MessageDigest.getInstance("SHA-256")
            for (f in listOf(gz, vec)) f.inputStream().use { it.digestInto(sha) }
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            check(hex == info.sha256) {
                "The paper index is being updated right now. Try again in a few minutes."
            }
            GZIPInputStream(gz.inputStream().buffered()).use { input ->
                jsonl.outputStream().use { input.copyTo(it) }
            }
            jsonl.renameOrCopy(papersFile(info.id))
            vec.renameOrCopy(vectorsFile(info.id))
            shaFile(info.id).writeText(info.sha256)
        } finally {
            gz.delete(); vec.delete(); jsonl.delete()
        }
    }

    fun deleteField(id: String) {
        papersFile(id).delete(); vectorsFile(id).delete(); shaFile(id).delete()
    }

    private fun download(name: String, target: File, commit: Boolean = true, onProgress: (Long, Long) -> Unit) {
        val tmp = if (commit) File(target.parentFile, target.name + ".part") else target
        var url = URL(baseUrl.trimEnd('/') + "/" + name)
        var conn: HttpURLConnection
        var redirects = 0
        // GitHub release assets redirect to a CDN on another host.
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect(); redirects++
                continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code for $name")
            break
        }
        val total = conn.contentLength.toLong()
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) { onProgress(done, total); lastReport = done }
                    }
                    onProgress(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
        if (commit) tmp.renameOrCopy(target)
    }

    private fun InputStream.digestInto(md: MessageDigest) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }

    private fun File.renameOrCopy(to: File) {
        if (!renameTo(to)) { copyTo(to, overwrite = true); delete() }
    }

    private fun fraction(done: Long, total: Long): Float? =
        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

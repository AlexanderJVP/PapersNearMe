package be.alexander.papersnearme.data

import be.alexander.papersnearme.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the files the nightly GitHub Action publishes. Model + vocab are fetched once;
 * the paper index is refreshed when it is older than [maxAgeMillis] or on request.
 */
class IndexRepository(filesDir: File, private val baseUrl: String = BuildConfig.DATA_URL) {

    private val dir = File(filesDir, "index").apply { mkdirs() }
    val model = File(dir, "model.onnx")
    val vocab = File(dir, "vocab.txt")
    val papers = File(dir, "papers.json")
    val vectors = File(dir, "vectors.bin")

    private val maxAgeMillis = 20L * 60 * 60 * 1000

    /** Makes sure all files are on the phone. Keeps the old index if a refresh fails. */
    fun sync(force: Boolean, onProgress: (String) -> Unit) {
        if (!model.exists()) { onProgress("Downloading the language model (~23 MB, once)…"); download(model) }
        if (!vocab.exists()) download(vocab)

        val stale = !papers.exists() || !vectors.exists() ||
            System.currentTimeMillis() - papers.lastModified() > maxAgeMillis
        if (force || stale) {
            onProgress("Downloading the latest papers…")
            try {
                // Download both before swapping so papers and vectors always match.
                val newPapers = download(papers, commit = false)
                val newVectors = download(vectors, commit = false)
                newPapers.renameOrCopy(papers)
                newVectors.renameOrCopy(vectors)
            } catch (e: Exception) {
                if (!papers.exists() || !vectors.exists()) throw e
            }
        }
    }

    private fun download(target: File, commit: Boolean = true): File {
        val tmp = File(target.parentFile, target.name + ".part")
        var url = URL(baseUrl.trimEnd('/') + "/" + target.name)
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
            if (code != 200) throw IllegalStateException("HTTP $code for ${target.name}")
            break
        }
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (commit) tmp.renameOrCopy(target)
        return tmp
    }

    private fun File.renameOrCopy(to: File) {
        if (!renameTo(to)) { copyTo(to, overwrite = true); delete() }
    }
}

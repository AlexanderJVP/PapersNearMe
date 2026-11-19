package be.alexander.papersnearme.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.PriorityQueue

/** One research field as published in manifest.json (e.g. "Social Sciences"). */
data class FieldInfo(
    val id: String,
    val name: String,
    val count: Int,
    val bytes: Long,
    val papersFile: String,
    val vectorsFile: String,
    val sha256: String,
)

data class Manifest(val updated: String, val since: String, val dim: Int, val fields: List<FieldInfo>) {
    companion object {
        fun parse(text: String): Manifest {
            val root = JSONObject(text)
            val arr = root.getJSONArray("fields")
            val fields = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                FieldInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    count = o.getInt("count"),
                    bytes = o.getLong("bytes"),
                    papersFile = o.getString("papers"),
                    vectorsFile = o.getString("vectors"),
                    sha256 = o.getString("sha256"),
                )
            }
            return Manifest(root.optString("updated"), root.optString("since"), root.getInt("dim"), fields)
        }
    }
}

data class LocalAuthor(val name: String, val institution: String, val city: String)

data class Paper(
    val id: String,
    val title: String,
    val year: Int,
    val url: String,
    val subfield: String,
    val authors: List<String>,
    val authorCount: Int,
    val localAuthors: List<LocalAuthor>,
    val abstract: String,
)

data class Match(val paper: Paper, val field: String, val score: Float)

/** Gives every city name a small int id, shared by all loaded fields. */
class CityTable {
    private val ids = HashMap<String, Int>()
    private val names = ArrayList<String>()
    @Synchronized fun id(name: String): Int = ids.getOrPut(name) { names.add(name); names.size - 1 }
    @Synchronized fun idOrNull(name: String): Int? = ids[name]
    @Synchronized fun name(id: Int): String = names[id]
}

/**
 * The papers of one field. Only the vectors, the cities and the byte offset of each
 * paper's line stay in memory; the paper itself is read from disk when it is shown.
 */
class FieldShard private constructor(
    val info: FieldInfo,
    private val file: File,
    private val offsets: LongArray,
    private val vectors: ByteArray,
    private val dim: Int,
    val paperCities: Array<IntArray>,
) {
    val size: Int get() = paperCities.size

    fun score(row: Int, query: FloatArray): Float {
        var dot = 0f
        val offset = row * dim
        for (i in 0 until dim) dot += query[i] * vectors[offset + i]
        return dot / 127f
    }

    fun paper(row: Int): Paper {
        val start = offsets[row]
        val bytes = ByteArray((offsets[row + 1] - start - 1).toInt())  // minus the '\n'
        RandomAccessFile(file, "r").use { it.seek(start); it.readFully(bytes) }
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val a = o.getJSONArray("a")
        val l = o.getJSONArray("l")
        return Paper(
            id = o.getString("id"),
            title = o.getString("t"),
            year = o.optInt("y"),
            url = o.getString("u"),
            subfield = o.optString("s"),
            authors = List(a.length()) { a.getString(it) },
            authorCount = o.optInt("n", a.length()),
            localAuthors = List(l.length()) { j ->
                val e = l.getJSONArray(j)
                LocalAuthor(e.getString(0), e.getString(1), e.getString(2))
            },
            abstract = o.optString("ab"),
        )
    }

    companion object {
        fun load(info: FieldInfo, jsonl: File, vectorsFile: File, dim: Int, cities: CityTable): FieldShard {
            val offsets = ArrayList<Long>(info.count + 1)
            val paperCities = ArrayList<IntArray>(info.count)
            var pos = 0L
            val line = ByteArrayOutputStream(2048)
            val buf = ByteArray(1 shl 16)
            FileInputStream(jsonl).use { input ->
                offsets.add(0L)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var start = 0
                    for (i in 0 until n) {
                        if (buf[i] != '\n'.code.toByte()) continue
                        line.write(buf, start, i - start)
                        val l = JSONObject(line.toString("UTF-8")).getJSONArray("l")
                        val ids = HashSet<Int>()
                        for (j in 0 until l.length()) ids += cities.id(l.getJSONArray(j).getString(2))
                        paperCities.add(ids.toIntArray())
                        offsets.add(pos + i + 1)
                        line.reset()
                        start = i + 1
                    }
                    line.write(buf, start, n - start)
                    pos += n
                }
            }
            val vectors = vectorsFile.readBytes()
            require(vectors.size == paperCities.size * dim) {
                "${info.name}: vectors has ${vectors.size} bytes, expected ${paperCities.size * dim}"
            }
            return FieldShard(info, jsonl, offsets.toLongArray(), vectors, dim, paperCities.toTypedArray())
        }
    }
}

data class Hit(val shard: FieldShard, val row: Int, val score: Float)

/** Top [limit] papers over all [shards], optionally only those with an author in [cityId]. */
fun searchShards(shards: Collection<FieldShard>, query: FloatArray, cityId: Int?, limit: Int): List<Hit> {
    val heap = PriorityQueue<Hit>(limit + 1, compareBy { it.score })
    for (shard in shards) {
        for (row in 0 until shard.size) {
            if (cityId != null && cityId !in shard.paperCities[row]) continue
            val s = shard.score(row, query)
            if (heap.size < limit) heap.add(Hit(shard, row, s))
            else if (s > heap.peek()!!.score) { heap.poll(); heap.add(Hit(shard, row, s)) }
        }
    }
    return heap.sortedByDescending { it.score }
}

package be.alexander.papersnearme.data

import org.json.JSONObject
import java.io.File

data class Institution(val name: String, val city: String)
data class LocalAuthor(val name: String, val institution: Int)

data class Paper(
    val id: String,
    val title: String,
    val year: Int,
    val url: String,
    val authors: List<String>,
    val authorCount: Int,
    val localAuthors: List<LocalAuthor>,
    val abstract: String,
)

data class Match(val paper: Paper, val score: Float)

/** Papers plus their int8 vectors, searched entirely on the phone. */
class PaperIndex(
    val updated: String,
    val dim: Int,
    val institutions: List<Institution>,
    val papers: List<Paper>,
    private val vectors: ByteArray,
) {
    /** Cities ordered by how many papers have a local author there. */
    val cities: List<String> by lazy {
        papers.flatMap { p -> p.localAuthors.map { institutions[it.institution].city }.distinct() }
            .filter { it != "Unknown" }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }

    fun search(query: FloatArray, city: String?, limit: Int = 30): List<Match> {
        val scored = ArrayList<Pair<Int, Float>>(papers.size)
        for (p in papers.indices) {
            if (city != null && papers[p].localAuthors.none { institutions[it.institution].city == city }) continue
            var dot = 0f
            val offset = p * dim
            for (i in 0 until dim) dot += query[i] * vectors[offset + i]
            scored += p to dot / 127f
        }
        return scored.sortedByDescending { it.second }.take(limit).map { Match(papers[it.first], it.second) }
    }

    companion object {
        fun load(json: File, vectorsFile: File): PaperIndex {
            val root = JSONObject(json.readText())
            val dim = root.getInt("dim")
            val inst = root.getJSONArray("institutions")
            val institutions = List(inst.length()) { i ->
                val o = inst.getJSONObject(i)
                Institution(o.getString("name"), o.optString("city", "Unknown"))
            }
            val arr = root.getJSONArray("papers")
            val papers = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val a = o.getJSONArray("a")
                val l = o.getJSONArray("l")
                Paper(
                    id = o.getString("id"),
                    title = o.getString("t"),
                    year = o.optInt("y"),
                    url = o.getString("u"),
                    authors = List(a.length()) { a.getString(it) },
                    authorCount = o.optInt("n", a.length()),
                    localAuthors = List(l.length()) { j ->
                        val pair = l.getJSONArray(j)
                        LocalAuthor(pair.getString(0), pair.getInt(1))
                    },
                    abstract = o.optString("ab"),
                )
            }
            val vectors = vectorsFile.readBytes()
            require(vectors.size == papers.size * dim) {
                "vectors.bin has ${vectors.size} bytes, expected ${papers.size * dim}"
            }
            return PaperIndex(root.optString("updated"), dim, institutions, papers, vectors)
        }
    }
}

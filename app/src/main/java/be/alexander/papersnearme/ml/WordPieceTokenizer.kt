package be.alexander.papersnearme.ml

import java.io.File
import java.text.Normalizer

/**
 * BERT "uncased" WordPiece tokenizer, matching all-MiniLM-L6-v2's tokenizer.json:
 * clean text -> lowercase -> strip accents -> split on whitespace and punctuation -> WordPiece.
 */
class WordPieceTokenizer(vocabFile: File, private val maxLen: Int = 256) {

    private val vocab: Map<String, Int> = HashMap<String, Int>(32_000).also { map ->
        vocabFile.useLines { lines -> lines.forEachIndexed { i, line -> map[line.trimEnd('\r')] = i } }
    }
    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")
    private val unk = vocab.getValue("[UNK]")
    private val accents = Regex("\\p{Mn}+")

    fun encode(text: String): LongArray {
        val ids = ArrayList<Long>()
        ids += cls.toLong()
        outer@ for (word in basicTokenize(text)) {
            for (id in wordPiece(word)) {
                if (ids.size >= maxLen - 1) break@outer
                ids += id.toLong()
            }
        }
        ids += sep.toLong()
        return ids.toLongArray()
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        for (ch in text) {
            when {
                Character.isWhitespace(ch) -> cleaned.append(' ')
                ch.code == 0 || ch.code == 0xFFFD || Character.isISOControl(ch) -> Unit
                isCjk(ch.code) -> cleaned.append(' ').append(ch).append(' ')
                else -> cleaned.append(ch)
            }
        }
        val normalized = Normalizer.normalize(cleaned.toString().lowercase(), Normalizer.Form.NFD)
            .replace(accents, "")

        val tokens = ArrayList<String>()
        for (chunk in normalized.split(' ')) {
            if (chunk.isEmpty()) continue
            val current = StringBuilder()
            for (ch in chunk) {
                if (isPunctuation(ch)) {
                    if (current.isNotEmpty()) { tokens += current.toString(); current.setLength(0) }
                    tokens += ch.toString()
                } else {
                    current.append(ch)
                }
            }
            if (current.isNotEmpty()) tokens += current.toString()
        }
        return tokens
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > 100) return listOf(unk)
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end--
            }
            if (found == null) return listOf(unk)
            pieces += found
            start = end
        }
        return pieces
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        val t = Character.getType(ch)
        return t == Character.CONNECTOR_PUNCTUATION.toInt() ||
            t == Character.DASH_PUNCTUATION.toInt() ||
            t == Character.START_PUNCTUATION.toInt() ||
            t == Character.END_PUNCTUATION.toInt() ||
            t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.OTHER_PUNCTUATION.toInt()
    }

    private fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF ||
            cp in 0x2F800..0x2FA1F || cp in 0x20000..0x2A6DF
}

package com.youramaryllis.holoscan

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Card(
    val id: String,
    val name: String,
    val set: String,
    val series: String,
    val number: String,
    val total: String,
    val rarity: String,
    val released: String,
    val artist: String,
)

data class Match(val card: Card, val score: Float)

/** Every card's reference embedding; nearest-neighbour search by dot product. */
class CardIndex(val cards: List<Card>, private val vectors: FloatArray, val dim: Int) {

    fun search(query: FloatArray, k: Int = 5): List<Match> {
        val top = IntArray(k) { -1 }
        val topScore = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (row in cards.indices) {
            var s = 0f
            val base = row * dim
            for (d in 0 until dim) s += vectors[base + d] * query[d]
            if (s <= topScore[k - 1]) continue
            var pos = k - 1
            while (pos > 0 && topScore[pos - 1] < s) {
                topScore[pos] = topScore[pos - 1]; top[pos] = top[pos - 1]; pos--
            }
            topScore[pos] = s; top[pos] = row
        }
        return top.indices.filter { top[it] >= 0 }.map { Match(cards[top[it]], topScore[it]) }
    }

    companion object {
        /** index.f16: float16 little-endian, cards.size x dim. */
        fun readVectors(stream: InputStream, count: Int, dim: Int): FloatArray {
            val bytes = stream.readBytes()
            require(bytes.size == count * dim * 2) { "index.f16 is ${bytes.size} bytes, expected ${count * dim * 2}" }
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(count * dim) { halfToFloat(shorts.get(it)) }
        }

        fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = (bits and 0x8000) shl 16
            val exp = (bits shr 10) and 0x1F
            val mant = bits and 0x3FF
            val f = when {
                exp == 0 && mant == 0 -> sign
                exp == 0 -> { // subnormal
                    var e = -14; var m = mant
                    while (m and 0x400 == 0) { m = m shl 1; e-- }
                    sign or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
                }
                exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
                else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
            }
            return Float.fromBits(f)
        }
    }
}

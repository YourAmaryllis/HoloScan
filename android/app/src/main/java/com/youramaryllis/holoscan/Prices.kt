package com.youramaryllis.holoscan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.round

/** Estimated prices for one card. Keys are TCGplayer variants / Cardmarket fields, as in prices.json. */
data class CardPrice(
    val usd: Map<String, Double>,   // TCGplayer market: "normal", "holofoil", "reverse-holofoil", ...
    val eur: Map<String, Double>,   // Cardmarket: "trend", "avg30", "trend-holo", ...
    val tcgdex: String? = null,                 // TCGdex card id, for a refresh
    val tcgplayer: Pair<Long, Long>? = null,    // TCGplayer (groupId, productId), for cards TCGdex doesn't price
    val updated: String? = null,                // set when refreshed in the app; else the book's date applies
) {
    /** Default estimate when the variant isn't known: the plain printing, else the cheapest listed. */
    val usdEstimate: Double?
        get() = usd["normal"] ?: usd["holofoil"] ?: usd.values.minOrNull()
}

data class PriceBook(val updated: String, val source: String, val cards: Map<String, CardPrice>) {
    operator fun get(cardId: String) = cards[cardId]

    /** This book with [newer]'s cards laid over it (prices from newer, ids kept from either). */
    fun overlay(newer: PriceBook): PriceBook = copy(cards = cards + newer.cards.mapValues { (id, p) ->
        val old = cards[id]
        p.copy(tcgdex = p.tcgdex ?: old?.tcgdex, tcgplayer = p.tcgplayer ?: old?.tcgplayer,
            usd = p.usd.ifEmpty { old?.usd.orEmpty() }, eur = p.eur.ifEmpty { old?.eur.orEmpty() })
    })
}

/**
 * Where prices come from. Today: a snapshot compiled into the APK
 * (ml/fetch_prices.py), plus an in-app refresh of the user's own cards
 * ([LivePriceSource], rate limited). Next: a Supabase backend that refreshes
 * prices centrally. Swap the implementation in [PriceSources]; callers only
 * see this interface.
 *
 * Called off the main thread; may block (file read or network).
 */
interface PriceSource {
    fun load(cardIds: Collection<String>? = null): PriceBook
}

/** prices.json bundled in assets. Ignores [cardIds]: the whole snapshot is local. */
class AssetPriceSource(private val context: Context) : PriceSource {
    override fun load(cardIds: Collection<String>?): PriceBook =
        PriceJson.parse(context.assets.open(FILE).bufferedReader().readText())

    companion object {
        const val FILE = "prices.json"
    }
}

/** prices.json format, shared by the bundled snapshot and the refreshed overlay. */
object PriceJson {
    fun parse(text: String): PriceBook {
        val root = JSONObject(text)
        val cards = root.getJSONObject("cards")
        val out = HashMap<String, CardPrice>(cards.length())
        for (id in cards.keys()) {
            val o = cards.getJSONObject(id)
            val tp = o.optJSONArray("tcgplayer")
            out[id] = CardPrice(
                o.optJSONObject("usd").toDoubles(), o.optJSONObject("eur").toDoubles(),
                tcgdex = o.optString("tcgdex").ifEmpty { null },
                tcgplayer = tp?.let { it.getLong(0) to it.getLong(1) },
                updated = o.optString("updated").ifEmpty { null },
            )
        }
        return PriceBook(root.optString("updated"), root.optString("source"), out)
    }

    fun write(book: PriceBook): String {
        val cards = JSONObject()
        for ((id, p) in book.cards) {
            cards.put(id, JSONObject().apply {
                if (p.usd.isNotEmpty()) put("usd", JSONObject(p.usd as Map<*, *>))
                if (p.eur.isNotEmpty()) put("eur", JSONObject(p.eur as Map<*, *>))
                p.tcgdex?.let { put("tcgdex", it) }
                p.tcgplayer?.let { put("tcgplayer", JSONArray(listOf(it.first, it.second))) }
                p.updated?.let { put("updated", it) }
            })
        }
        return JSONObject().put("updated", book.updated).put("source", book.source).put("cards", cards).toString()
    }

    private fun JSONObject?.toDoubles(): Map<String, Double> =
        this?.keys()?.asSequence()?.associateWith { getDouble(it) } ?: emptyMap()
}

/**
 * Fresh prices for a few cards (the user's collection), straight from the same
 * free sources fetch_prices.py uses: TCGdex per card, TCGCSV per TCGplayer group.
 * Needs [known] for each card's TCGdex id / TCGplayer product. Cards that fail keep
 * their old price. Kept polite: 4 requests at a time, and the app rate-limits
 * refreshes ([RefreshLimit]).
 */
class LivePriceSource(private val known: PriceBook, private val now: () -> String) : PriceSource {
    override fun load(cardIds: Collection<String>?): PriceBook {
        val ids = cardIds ?: emptyList()
        val stamp = now()
        val out = java.util.concurrent.ConcurrentHashMap<String, CardPrice>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            for (id in ids) {
                val tid = known[id]?.tcgdex ?: continue
                pool.execute {
                    runCatching { getJson("$TCGDEX/cards/$tid") }.getOrNull()?.optJSONObject("pricing")?.let { p ->
                        val price = compact(p)
                        if (price.usd.isNotEmpty() || price.eur.isNotEmpty()) out[id] = price.copy(tcgdex = tid, updated = stamp)
                    }
                }
            }
            // TCGplayer-only cards: one request per group, not per card.
            val byGroup = ids.mapNotNull { id -> known[id]?.tcgplayer?.let { id to it } }
                .filter { (id, _) -> known[id]?.tcgdex == null }.groupBy { it.second.first }
            for ((group, members) in byGroup) {
                pool.execute {
                    val results = runCatching { getJson("$TCGCSV/$group/prices").getJSONArray("results") }.getOrNull()
                        ?: return@execute
                    val byProduct = HashMap<Long, MutableMap<String, Double>>()
                    for (i in 0 until results.length()) {
                        val r = results.getJSONObject(i)
                        val v = r.optDouble("marketPrice").takeUnless { it.isNaN() || it == 0.0 }
                            ?: r.optDouble("midPrice").takeUnless { it.isNaN() || it == 0.0 } ?: continue
                        val variant = r.getString("subTypeName").lowercase().replace(" ", "-")
                        byProduct.getOrPut(r.getLong("productId")) { HashMap() }[variant] = round2(v)
                    }
                    for ((id, ref) in members) {
                        byProduct[ref.second]?.let { out[id] = CardPrice(it, emptyMap(), tcgplayer = ref, updated = stamp) }
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.MINUTES)
        }
        return PriceBook(stamp, "TCGdex / TCGCSV, refreshed in the app", out)
    }

    /** Same fields as fetch_prices.compact(): TCGplayer market (else mid) per variant, Cardmarket EUR. */
    private fun compact(pricing: JSONObject): CardPrice {
        val usd = HashMap<String, Double>()
        pricing.optJSONObject("tcgplayer")?.let { tp ->
            for (variant in tp.keys()) {
                val p = tp.optJSONObject(variant) ?: continue
                val v = p.optDouble("marketPrice").takeUnless { it.isNaN() || it == 0.0 }
                    ?: p.optDouble("midPrice").takeUnless { it.isNaN() || it == 0.0 } ?: continue
                usd[variant] = round2(v)
            }
        }
        val eur = HashMap<String, Double>()
        pricing.optJSONObject("cardmarket")?.let { cm ->
            for (f in EUR_FIELDS) for (key in listOf(f, "$f-holo")) {
                val v = cm.optDouble(key)
                if (!v.isNaN() && v != 0.0) eur[key] = round2(v)
            }
        }
        return CardPrice(usd, eur)
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "HoloScan app (personal project)")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} for $url")
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun round2(v: Double) = round(v * 100) / 100

    companion object {
        const val TCGDEX = "https://api.tcgdex.net/v2/en"
        const val TCGCSV = "https://tcgcsv.com/tcgplayer/3"
        val EUR_FIELDS = listOf("avg", "low", "trend", "avg7", "avg30")
    }
}

/** Refreshed prices, kept in app storage and laid over the bundled snapshot. */
class RefreshedPrices(context: Context) {
    private val file = File(context.filesDir, "prices_refreshed.json")

    fun load(): PriceBook? = if (file.exists()) runCatching { PriceJson.parse(file.readText()) }.getOrNull() else null

    fun save(book: PriceBook) = file.writeText(PriceJson.write(book))
}

/**
 * At most [perHour] refreshes in any hour, and [cooldownMs] between two.
 * Pure: takes and returns the list of past refresh times (ms).
 */
object RefreshLimit {
    const val PER_HOUR = 5
    const val COOLDOWN_MS = 5 * 60_000L
    const val HOUR_MS = 60 * 60_000L

    /** ms to wait before the next refresh is allowed; 0 = allowed now. */
    fun waitMs(history: List<Long>, now: Long): Long {
        val recent = history.filter { now - it < HOUR_MS }.sorted()
        val cooldown = recent.lastOrNull()?.let { it + COOLDOWN_MS - now } ?: 0
        val hourly = if (recent.size >= PER_HOUR) recent[recent.size - PER_HOUR] + HOUR_MS - now else 0
        return maxOf(0, cooldown, hourly)
    }

    fun left(history: List<Long>, now: Long) = PER_HOUR - history.count { now - it < HOUR_MS }

    fun record(history: List<Long>, now: Long) = history.filter { now - it < HOUR_MS } + now
}

object PriceSources {
    // Replace with the Supabase-backed source once the backend exists.
    fun default(context: Context): PriceSource = AssetPriceSource(context)

    /** Refresh for the cards the user owns; [known] supplies each card's source ids. */
    fun live(known: PriceBook, now: () -> String): PriceSource = LivePriceSource(known, now)
}

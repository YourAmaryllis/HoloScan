package com.youramaryllis.holoscan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Loads the bundled model, card list and index (all on-device, no network). */
object Assets {
    const val MODEL_FULL = "embedder.onnx"
    const val MODEL_INT8 = "embedder.int8.onnx"

    fun modelFile(context: Context, name: String = MODEL_FULL): File {
        // ONNX Runtime wants a file path; copy once out of the APK.
        val out = File(context.filesDir, name)
        val size = context.assets.openFd(name).use { it.length }
        if (!out.exists() || out.length() != size) {
            context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    fun loadIndex(context: Context): CardIndex {
        val meta = JSONObject(context.assets.open("model.json").bufferedReader().readText())
        val dim = meta.getInt("dim")
        val arr = JSONArray(context.assets.open("cards.json").bufferedReader().readText())
        val cards = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Card(
                id = o.getString("id"),
                name = o.getString("name"),
                set = o.optString("set"),
                series = o.optString("series"),
                number = o.optString("number"),
                total = o.optString("total"),
                rarity = o.optString("rarity"),
                released = o.optString("released"),
                artist = o.optString("artist"),
            )
        }
        val vectors = context.assets.open("index.f16").use { CardIndex.readVectors(it, cards.size, dim) }
        return CardIndex(cards, vectors, dim)
    }
}

/** The user's collection: card id -> count, kept in a JSON file in app storage. */
class CollectionStore(context: Context) {
    private val file = File(context.filesDir, "collection.json")

    fun load(): Map<String, Int> {
        if (!file.exists()) return emptyMap()
        val o = JSONObject(file.readText())
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }

    fun add(cardId: String, delta: Int = 1): Map<String, Int> {
        val current = load().toMutableMap()
        val n = (current[cardId] ?: 0) + delta
        if (n <= 0) current.remove(cardId) else current[cardId] = n
        return replace(current)
    }

    /** Overwrites the whole collection (import, reset). */
    fun replace(collection: Map<String, Int>): Map<String, Int> {
        val clean = collection.filterValues { it > 0 }
        file.writeText(JSONObject(clean as Map<*, *>).toString())
        return clean
    }
}

/** App settings, kept in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // int8 is the default: same accuracy as full on device, and much faster.
    var model: String
        get() = prefs.getString("model", Assets.MODEL_INT8) ?: Assets.MODEL_INT8
        set(value) = prefs.edit().putString("model", value).apply()

    // Seconds on the same card before it's added automatically; 0 = off.
    var autoAddSeconds: Int
        get() = prefs.getInt("autoAddSeconds", 2)
        set(value) = prefs.edit().putInt("autoAddSeconds", value).apply()

    // Side of the screen for the check / cross buttons, for one-handed use.
    var buttonsLeft: Boolean
        get() = prefs.getBoolean("buttonsLeft", false)
        set(value) = prefs.edit().putBoolean("buttonsLeft", value).apply()

    // Wall-clock ms of recent price refreshes (for the rate limit).
    var priceRefreshes: List<Long>
        get() = prefs.getString("priceRefreshes", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        set(value) = prefs.edit().putString("priceRefreshes", value.joinToString(",")).apply()

    /** The user-facing settings, for a backup. */
    fun export(): Map<String, String> = mapOf(
        "model" to model, "autoAddSeconds" to autoAddSeconds.toString(), "buttonsLeft" to buttonsLeft.toString(),
    )

    /** Applies settings from a backup; unknown keys and bad values are ignored. */
    fun import(values: Map<String, String>) {
        values["model"]?.takeIf { it == Assets.MODEL_INT8 || it == Assets.MODEL_FULL }?.let { model = it }
        values["autoAddSeconds"]?.toIntOrNull()?.takeIf { it in 0..60 }?.let { autoAddSeconds = it }
        values["buttonsLeft"]?.toBooleanStrictOrNull()?.let { buttonsLeft = it }
    }
}

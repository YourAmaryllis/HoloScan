package com.youramaryllis.holoscan

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Minimal RFC 4180 CSV: comma separated, fields quoted when they contain a comma, quote or newline. */
object Csv {
    fun write(rows: List<List<String>>): String = buildString {
        for (row in rows) append(row.joinToString(",") { quote(it) }).append("\r\n")
    }

    private fun quote(f: String) =
        if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + f.replace("\"", "\"\"") + "\"" else f

    fun read(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                quoted -> field.append(ch)
                ch == ',' -> { row.add(field.toString()); field.clear() }
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** Rows as header -> value maps; the header row is matched case-insensitively. */
    fun records(text: String): List<Map<String, String>> {
        val rows = read(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        return rows.drop(1).map { r -> header.indices.associate { header[it] to r.getOrElse(it) { "" }.trim() } }
    }
}

/**
 * Export / import of the collection, so it can move to another phone without any
 * account or server. A zip of plain CSV files that also opens in a spreadsheet:
 *
 *   collection.csv   card_id,count,name,set,number,rarity   (only card_id and count are read back)
 *   settings.csv     key,value
 *   info.csv         key,value   (format version, export time)
 */
object Backup {
    const val FORMAT = "1"

    data class Contents(val collection: Map<String, Int>, val settings: Map<String, String>)

    fun export(out: OutputStream, collection: Map<String, Int>, cards: Map<String, Card>,
               settings: Map<String, String>, exportedAt: String) {
        val coll = listOf(listOf("card_id", "count", "name", "set", "number", "rarity")) +
            collection.entries.sortedBy { it.key }.map { (id, n) ->
                val c = cards[id]
                listOf(id, n.toString(), c?.name.orEmpty(), c?.set.orEmpty(), c?.number.orEmpty(), c?.rarity.orEmpty())
            }
        val sett = listOf(listOf("key", "value")) + settings.entries.sortedBy { it.key }.map { listOf(it.key, it.value) }
        val info = listOf(listOf("key", "value"), listOf("app", "HoloScan"), listOf("format", FORMAT),
            listOf("exported_at", exportedAt), listOf("cards", collection.values.sum().toString()))
        ZipOutputStream(out).use { zip ->
            for ((name, rows) in listOf("collection.csv" to coll, "settings.csv" to sett, "info.csv" to info)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(Csv.write(rows).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** Reads a backup zip, or a bare collection.csv. Throws IllegalArgumentException if neither. */
    fun import(input: InputStream): Contents {
        val bytes = input.readBytes()
        val files = HashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) files[e.name.substringAfterLast('/').lowercase()] = zip.readBytes().decodeToString()
            }
        }
        val collText = files["collection.csv"] ?: bytes.decodeToString().takeIf { files.isEmpty() }
            ?: throw IllegalArgumentException("no collection.csv in the backup")
        val records = Csv.records(collText)
        if (records.isNotEmpty() && "card_id" !in records.first()) throw IllegalArgumentException("collection.csv has no card_id column")
        val collection = HashMap<String, Int>()
        for (r in records) {
            val id = r["card_id"].orEmpty()
            val n = r["count"]?.toIntOrNull() ?: 1
            if (id.isNotEmpty() && n > 0) collection[id] = (collection[id] ?: 0) + n
        }
        val settings = files["settings.csv"]?.let { t -> Csv.records(t).associate { it["key"].orEmpty() to it["value"].orEmpty() } }
            ?.filterKeys { it.isNotEmpty() }.orEmpty()
        return Contents(collection, settings)
    }
}

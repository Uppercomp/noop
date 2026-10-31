package com.noop.ai

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.noop.data.WhoopDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Full mode uses a separate OS-enforced read-only SQLite handle, not a writable Room connection. */
internal class CoachDataAccess(private val ctx: Context) : java.io.Closeable {
    private val db: SQLiteDatabase
    init {
        WhoopDatabase.get(ctx).openHelper.readableDatabase // Ensure the current schema exists first.
        db = SQLiteDatabase.openDatabase(ctx.getDatabasePath(WhoopDatabase.DB_NAME).path, null, SQLiteDatabase.OPEN_READONLY)
    }
    fun catalog(): String = db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'", null).use { c ->
        buildString { while (c.moveToNext()) append(c.getString(1)).append('\n') }
    }
    private fun preferenceDatasets(): Map<String, Map<String, *>> = buildMap {
        for (name in listOf("noop_profile", "noop_moments", "noop_active_workout", "noop_lift_session",
            "noop_auto_workout_prefs", "noop_smart_alarm", "noop_wind_down")) {
            put(name, ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        }
        val local = ctx.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE).all
        val prefixes = listOf("noop.journal", "noop.caffeine", "noop.nap", "nap.", "noop.sleep", "sleep.",
            "cycle.", "noop.cycle", "hydration.", "biofeedback.", "noop.hrv", "noop.stress", "noop.fitness", "noop.training")
        put("healthLogs", local.filterKeys { key -> prefixes.any { key.startsWith(it) } })
    }

    fun preferences(dataset: String? = null, key: String? = null, offset: Int = 0): String {
        val stores = preferenceDatasets()
        if (dataset == null) return JSONObject().put("datasets", JSONObject(stores.mapValues { (_, entries) ->
            JSONObject(entries.mapValues { (_, value) -> value.toString().length })
        })).put("note", "Request dataset and key; values are paged in 12000-character chunks. No credential stores are exposed.").toString()
        val store = stores[dataset] ?: error("Unknown health dataset")
        if (key == null) return JSONObject().put("keys", JSONArray(store.keys)).toString()
        val value = store[key]?.toString() ?: return JSONObject().put("value", JSONObject.NULL).toString()
        val start = offset.coerceIn(0, value.length)
        val end = minOf(start + 12000, value.length)
        return JSONObject().put("value", value.substring(start, end)).put("characters", value.length)
            .put("nextOffset", if (end < value.length) end else JSONObject.NULL).toString()
    }

    fun query(sql: String): String {
        require(isReadQuery(sql)) { "Only one SELECT/WITH read query is allowed; no PRAGMA, writes or external access." }
        val cancellation = android.os.CancellationSignal()
        val timeout = java.util.Timer(true)
        timeout.schedule(object : java.util.TimerTask() { override fun run() = cancellation.cancel() }, 6_000)
        return try { db.rawQuery(sql, null, cancellation).use { cursor ->
            val rows = JSONArray()
            var size = 0
            while (rows.length() < 200 && size < 60_000 && cursor.moveToNext()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { index, name ->
                    val v: Any = when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).takeIf { it.isFinite() } ?: JSONObject.NULL
                        Cursor.FIELD_TYPE_BLOB -> {
                            val bytes = cursor.getBlob(index)
                            JSONObject().put("byteCount", bytes.size).put("hexPrefix", bytes.take(512).joinToString("") { "%02x".format(it.toInt() and 255) })
                                .put("note", "For complete bytes query hex(substr(column,offset,length)) in pages")
                        }
                        else -> cursor.getString(index).take(12_000)
                    }
                    row.put(name, v)
                }
                size += row.toString().length
                rows.put(row)
            }
            JSONObject().put("rows", rows).put("rowLimit", 200).put("possiblyMore", rows.length() == 200 || size >= 60_000)
                .put("note", "Use SQL LIMIT/OFFSET or aggregate queries for more; text cells capped at 12000 characters, use substr for larger cells.").toString()
        } } finally { timeout.cancel() }
    }
    override fun close() = db.close()

    companion object {
        fun isReadQuery(sql: String): Boolean {
            val clean = sql.trim()
            return Regex("^(SELECT|WITH)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) &&
                !Regex(";|--|/\\*|\\b(ATTACH|DETACH|PRAGMA|INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER|VACUUM|REINDEX|load_extension|writefile|readfile)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)
        }
    }
}

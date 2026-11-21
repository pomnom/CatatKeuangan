package id.catatkeuangan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Cadangan seluruh data ke satu file .json, dan pemulihannya. */
object Backup {
    private const val APP = "catatkeuangan"

    fun build(ctx: Context): JSONObject {
        val db = Db.get(ctx).readableDatabase
        val o = JSONObject()
        o.put("app", APP)
        o.put("version", 1)
        o.put("exported", System.currentTimeMillis())
        o.put("owner", Prefs.owner(ctx))
        o.put("stores", JSONArray(Prefs.stores(ctx).toList()))
        for (table in listOf("category", "tx", "rule", "import_log")) {
            val arr = JSONArray()
            db.rawQuery("SELECT * FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(name, c.getDouble(i))
                            else -> row.put(name, c.getString(i))
                        }
                    }
                    arr.put(row)
                }
            }
            o.put(table, arr)
        }
        return o
    }

    fun writeTo(ctx: Context, uri: Uri) {
        try {
            val json = build(ctx).toString()
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("tidak bisa menulis file")
            val n = Db.get(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM tx", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            Ui.info(ctx, "Cadangan tersimpan", "$n transaksi, kategori, dan nama yang diingat sudah disimpan.")
        } catch (e: Exception) {
            Ui.info(ctx, "Gagal menyimpan cadangan", e.message ?: e.javaClass.simpleName)
        }
    }

    fun askRestore(ctx: Context, uri: Uri, onDone: () -> Unit) {
        val o = try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("file kosong")
            JSONObject(text).also { if (it.optString("app") != APP) throw IllegalStateException("bukan file cadangan Catat Keuangan") }
        } catch (e: Exception) {
            Ui.info(ctx, "Tidak bisa dipulihkan", "File ini bukan cadangan Catat Keuangan (${e.message}).")
            return
        }
        val n = o.optJSONArray("tx")?.length() ?: 0
        Ui.confirm(ctx, "Pulihkan cadangan?",
            "Semua data di HP ini akan diganti dengan isi cadangan ($n transaksi).", "Pulihkan") {
            try {
                restore(ctx, o)
                Ui.info(ctx, "Cadangan dipulihkan", "$n transaksi sudah dipulihkan.")
                onDone()
            } catch (e: Exception) {
                Ui.info(ctx, "Gagal memulihkan", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun restore(ctx: Context, o: JSONObject) {
        val db = Db.get(ctx).writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("tx", "rule", "category", "import_log")) db.delete(table, null, null)
            for (table in listOf("category", "rule", "import_log", "tx")) {
                val arr = o.optJSONArray(table) ?: continue
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    val v = ContentValues()
                    val keys = row.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        when (val x = row.get(k)) {
                            JSONObject.NULL -> v.putNull(k)
                            is Int -> v.put(k, x.toLong())
                            is Long -> v.put(k, x)
                            is Double -> v.put(k, x)
                            is Boolean -> v.put(k, if (x) 1 else 0)
                            else -> v.put(k, x.toString())
                        }
                    }
                    db.insertOrThrow(table, null, v)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val stores = o.optJSONArray("stores")
        val set = HashSet<String>()
        if (stores != null) for (i in 0 until stores.length()) set += stores.getString(i)
        Prefs.save(ctx, o.optString("owner", ""), set)
    }
}

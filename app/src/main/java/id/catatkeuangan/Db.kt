package id.catatkeuangan

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import id.catatkeuangan.core.Classifier
import java.time.YearMonth

const val DIR_IN = 1
const val DIR_OUT = -1
const val KIND_NORMAL = Classifier.KIND_NORMAL
const val KIND_INTERNAL = Classifier.KIND_INTERNAL
const val KIND_SELF = Classifier.KIND_SELF_STORE
const val BOOK_PRIBADI = Classifier.BOOK_PRIBADI
const val BOOK_USAHA = Classifier.BOOK_USAHA
const val BOOK_ALL = -1
const val SRC_MANUAL = 0
const val SRC_IMPORT = 1

fun bookName(b: Int) = if (b == BOOK_USAHA) "Usaha" else "Pribadi"

data class Category(
    val id: Long,
    val name: String,
    val dir: Int,
    val book: Int,
    val key: String?,
    val sort: Int,
    val hidden: Boolean,
)

data class Tx(
    var id: Long = 0,
    var ts: Long = 0,
    var amount: Long = 0,
    var dir: Int = DIR_OUT,
    var kind: Int = KIND_NORMAL,
    var book: Int = BOOK_PRIBADI,
    var catId: Long? = null,
    var partyKey: String = "",
    var party: String = "",
    var type: String = "",
    var note: String = "",
    var memo: String = "",
    var raw: String = "",
    var source: Int = SRC_MANUAL,
    var account: String = "",
    var balance: Long? = null,
    var importKey: String? = null,
    var importId: Long? = null,
) {
    val signed: Long get() = if (dir == DIR_IN) amount else -amount
    val counted: Boolean get() = kind == KIND_NORMAL
}

data class Rule(
    val partyKey: String,
    val dir: Int,
    val party: String,
    val kind: Int,
    val book: Int,
    val catId: Long?,
)

data class PendingGroup(
    val partyKey: String,
    val dir: Int,
    val party: String,
    val count: Int,
    val total: Long,
    val type: String,
    val note: String,
)

data class ImportLog(
    val id: Long,
    val at: Long,
    val file: String,
    val account: String,
    val period: String,
    val owner: String,
    val total: Int,
    val added: Int,
    val dup: Int,
    val closing: Long?,
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "catat.db", null, 1) {

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }

        /** (kunci, nama, arah, buku) — kategori awal. */
        val SEED = listOf(
            Triple("in_sales", "Penjualan QRIS", DIR_IN) to BOOK_USAHA,
            Triple("in_sales_other", "Penjualan lain (tunai/transfer)", DIR_IN) to BOOK_USAHA,
            Triple("in_salary", "Gaji & tunjangan", DIR_IN) to BOOK_PRIBADI,
            Triple("in_family", "Kiriman keluarga", DIR_IN) to BOOK_PRIBADI,
            Triple("in_loan", "Pinjaman diterima", DIR_IN) to BOOK_PRIBADI,
            Triple("in_repay", "Pengembalian uang", DIR_IN) to BOOK_PRIBADI,
            Triple("in_interest", "Bunga bank", DIR_IN) to BOOK_PRIBADI,
            Triple("in_other", "Pemasukan lain", DIR_IN) to BOOK_PRIBADI,
            Triple("out_stock", "Belanja stok", DIR_OUT) to BOOK_USAHA,
            Triple("out_shop_ops", "Operasional toko", DIR_OUT) to BOOK_USAHA,
            Triple("out_food", "Makan & minum", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_daily", "Belanja harian", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_online", "Belanja online", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_phone", "Pulsa & internet", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_bills", "Listrik & tagihan", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_ewallet", "Top up e-wallet", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_apps", "Game & aplikasi", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_transport", "Transportasi", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_family", "Kirim ke keluarga", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_lend", "Pinjamkan ke orang", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_debt", "Bayar utang/cicilan", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_bank", "Biaya bank", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_cash", "Tarik tunai", DIR_OUT) to BOOK_PRIBADI,
            Triple("out_other", "Pengeluaran lain", DIR_OUT) to BOOK_PRIBADI,
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE category(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                dir INTEGER NOT NULL,
                book INTEGER NOT NULL DEFAULT 0,
                ckey TEXT UNIQUE,
                sort INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE tx(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                dir INTEGER NOT NULL,
                kind INTEGER NOT NULL DEFAULT 0,
                book INTEGER NOT NULL DEFAULT 0,
                cat_id INTEGER,
                party_key TEXT NOT NULL DEFAULT '',
                party TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                memo TEXT NOT NULL DEFAULT '',
                raw TEXT NOT NULL DEFAULT '',
                source INTEGER NOT NULL DEFAULT 0,
                account TEXT NOT NULL DEFAULT '',
                balance INTEGER,
                import_key TEXT UNIQUE,
                import_id INTEGER,
                created INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX tx_ts ON tx(ts)")
        db.execSQL("CREATE INDEX tx_party ON tx(party_key, dir)")
        db.execSQL(
            """CREATE TABLE rule(
                party_key TEXT NOT NULL,
                dir INTEGER NOT NULL,
                party TEXT NOT NULL,
                kind INTEGER NOT NULL,
                book INTEGER NOT NULL,
                cat_id INTEGER,
                PRIMARY KEY(party_key, dir))"""
        )
        db.execSQL(
            """CREATE TABLE import_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                file TEXT NOT NULL DEFAULT '',
                account TEXT NOT NULL DEFAULT '',
                period TEXT NOT NULL DEFAULT '',
                owner TEXT NOT NULL DEFAULT '',
                total INTEGER NOT NULL DEFAULT 0,
                added INTEGER NOT NULL DEFAULT 0,
                dup INTEGER NOT NULL DEFAULT 0,
                closing INTEGER)"""
        )
        seed(db)
    }

    private fun seed(db: SQLiteDatabase) {
        SEED.forEachIndexed { i, (t, book) ->
            db.insert("category", null, ContentValues().apply {
                put("ckey", t.first)
                put("name", t.second)
                put("dir", t.third)
                put("book", book)
                put("sort", i * 10)
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- kategori ----------

    fun categories(includeHidden: Boolean = true): List<Category> {
        val out = ArrayList<Category>()
        readableDatabase.rawQuery(
            "SELECT id,name,dir,book,ckey,sort,hidden FROM category" +
                (if (includeHidden) "" else " WHERE hidden=0") + " ORDER BY dir DESC, sort, id", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Category(
                    c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
                    if (c.isNull(4)) null else c.getString(4), c.getInt(5), c.getInt(6) != 0
                )
            }
        }
        return out
    }

    fun categoryMap(): Map<Long, Category> = categories().associateBy { it.id }

    fun categoryIdsByKey(): Map<String, Long> =
        categories().filter { it.key != null }.associate { it.key!! to it.id }

    fun addCategory(name: String, dir: Int, book: Int): Long {
        val maxSort = readableDatabase.rawQuery("SELECT COALESCE(MAX(sort),0) FROM category WHERE dir=?", arrayOf(dir.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return writableDatabase.insert("category", null, ContentValues().apply {
            put("name", name)
            put("dir", dir)
            put("book", book)
            put("sort", maxSort + 10)
        })
    }

    fun updateCategory(id: Long, name: String, book: Int, hidden: Boolean) {
        writableDatabase.update("category", ContentValues().apply {
            put("name", name)
            put("book", book)
            put("hidden", if (hidden) 1 else 0)
        }, "id=?", arrayOf(id.toString()))
    }

    // ---------- transaksi ----------

    private val TX_COLS = "id,ts,amount,dir,kind,book,cat_id,party_key,party,type,note,memo,raw,source,account,balance,import_key,import_id"

    private fun readTx(c: Cursor) = Tx(
        id = c.getLong(0), ts = c.getLong(1), amount = c.getLong(2), dir = c.getInt(3), kind = c.getInt(4),
        book = c.getInt(5), catId = if (c.isNull(6)) null else c.getLong(6), partyKey = c.getString(7),
        party = c.getString(8), type = c.getString(9), note = c.getString(10), memo = c.getString(11),
        raw = c.getString(12), source = c.getInt(13), account = c.getString(14),
        balance = if (c.isNull(15)) null else c.getLong(15), importKey = if (c.isNull(16)) null else c.getString(16),
        importId = if (c.isNull(17)) null else c.getLong(17),
    )

    private fun txValues(t: Tx) = ContentValues().apply {
        put("ts", t.ts)
        put("amount", t.amount)
        put("dir", t.dir)
        put("kind", t.kind)
        put("book", t.book)
        if (t.catId == null) putNull("cat_id") else put("cat_id", t.catId)
        put("party_key", t.partyKey)
        put("party", t.party)
        put("type", t.type)
        put("note", t.note)
        put("memo", t.memo)
        put("raw", t.raw)
        put("source", t.source)
        put("account", t.account)
        if (t.balance == null) putNull("balance") else put("balance", t.balance)
        if (t.importKey == null) putNull("import_key") else put("import_key", t.importKey)
        if (t.importId == null) putNull("import_id") else put("import_id", t.importId)
    }

    fun txRange(start: Long, end: Long): List<Tx> {
        val out = ArrayList<Tx>()
        readableDatabase.rawQuery(
            "SELECT $TX_COLS FROM tx WHERE ts>=? AND ts<? ORDER BY ts DESC, id DESC",
            arrayOf(start.toString(), end.toString())
        ).use { c -> while (c.moveToNext()) out += readTx(c) }
        return out
    }

    fun tx(id: Long): Tx? = readableDatabase.rawQuery("SELECT $TX_COLS FROM tx WHERE id=?", arrayOf(id.toString()))
        .use { c -> if (c.moveToFirst()) readTx(c) else null }

    /** -1 bila sudah ada (import_key sama). */
    fun insertTx(db: SQLiteDatabase, t: Tx): Long {
        val v = txValues(t).apply { put("created", System.currentTimeMillis()) }
        return db.insertWithOnConflict("tx", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun insertTx(t: Tx): Long = insertTx(writableDatabase, t)

    fun updateTx(t: Tx) {
        writableDatabase.update("tx", txValues(t), "id=?", arrayOf(t.id.toString()))
    }

    fun deleteTx(id: Long) {
        writableDatabase.delete("tx", "id=?", arrayOf(id.toString()))
    }

    fun monthsWithData(): List<YearMonth> {
        val out = ArrayList<YearMonth>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT strftime('%Y-%m', ts/1000, 'unixepoch') AS m FROM tx ORDER BY m DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val s = c.getString(0) ?: continue
                try {
                    out += YearMonth.parse(s)
                } catch (e: Exception) {
                    // abaikan
                }
            }
        }
        return out
    }

    fun latestMonth(): YearMonth? = readableDatabase.rawQuery("SELECT MAX(ts) FROM tx", null).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) Fmt.toLocal(c.getLong(0)).let { YearMonth.of(it.year, it.monthValue) } else null
    }

    /** Saldo bank terakhir yang tercatat dari e-statement: (waktu, saldo, rekening). */
    fun latestBalance(): Triple<Long, Long, String>? = readableDatabase.rawQuery(
        "SELECT ts, balance, account FROM tx WHERE balance IS NOT NULL AND source=$SRC_IMPORT ORDER BY ts DESC, id DESC LIMIT 1",
        null
    ).use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getString(2)) else null }

    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM tx WHERE kind=$KIND_NORMAL AND cat_id IS NULL", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun pendingGroups(): List<PendingGroup> {
        val out = ArrayList<PendingGroup>()
        readableDatabase.rawQuery(
            """SELECT party_key, dir, MAX(party), COUNT(*), SUM(amount), MAX(type), MAX(note)
               FROM tx WHERE kind=$KIND_NORMAL AND cat_id IS NULL
               GROUP BY party_key, dir ORDER BY SUM(amount) DESC""", null
        ).use { c ->
            while (c.moveToNext()) {
                out += PendingGroup(
                    c.getString(0), c.getInt(1), c.getString(2) ?: "", c.getInt(3), c.getLong(4),
                    c.getString(5) ?: "", c.getString(6) ?: ""
                )
            }
        }
        return out
    }

    // ---------- aturan "ingat nama" ----------

    fun rules(): List<Rule> {
        val out = ArrayList<Rule>()
        readableDatabase.rawQuery("SELECT party_key, dir, party, kind, book, cat_id FROM rule ORDER BY party", null).use { c ->
            while (c.moveToNext()) {
                out += Rule(c.getString(0), c.getInt(1), c.getString(2), c.getInt(3), c.getInt(4),
                    if (c.isNull(5)) null else c.getLong(5))
            }
        }
        return out
    }

    /** Simpan aturan & terapkan ke semua transaksi import dengan nama & arah yang sama. */
    fun saveRule(r: Rule) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("rule", null, ContentValues().apply {
                put("party_key", r.partyKey)
                put("dir", r.dir)
                put("party", r.party)
                put("kind", r.kind)
                put("book", r.book)
                if (r.catId == null) putNull("cat_id") else put("cat_id", r.catId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.update("tx", ContentValues().apply {
                put("kind", r.kind)
                put("book", r.book)
                if (r.catId == null) putNull("cat_id") else put("cat_id", r.catId)
            }, "party_key=? AND dir=? AND source=$SRC_IMPORT AND kind<>$KIND_SELF", arrayOf(r.partyKey, r.dir.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteRule(partyKey: String, dir: Int) {
        writableDatabase.delete("rule", "party_key=? AND dir=?", arrayOf(partyKey, dir.toString()))
    }

    // ---------- riwayat import ----------

    fun imports(): List<ImportLog> {
        val out = ArrayList<ImportLog>()
        readableDatabase.rawQuery(
            "SELECT id, at, file, account, period, owner, total, added, dup, closing FROM import_log ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out += ImportLog(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getString(5), c.getInt(6), c.getInt(7), c.getInt(8), if (c.isNull(9)) null else c.getLong(9))
            }
        }
        return out
    }

    fun countPendingForImport(importId: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM (SELECT DISTINCT party_key, dir FROM tx WHERE import_id=? AND kind=$KIND_NORMAL AND cat_id IS NULL)",
        arrayOf(importId.toString())
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}

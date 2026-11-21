package id.catatkeuangan

import android.content.ContentValues
import android.content.Context
import id.catatkeuangan.core.Classifier
import id.catatkeuangan.core.Statement
import java.time.YearMonth

/** Pengaturan kecil: nama pemilik rekening & nama toko QRIS (dibaca otomatis dari e-statement). */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("catat", Context.MODE_PRIVATE)

    fun owner(ctx: Context): String = sp(ctx).getString("owner", "") ?: ""

    fun stores(ctx: Context): Set<String> = sp(ctx).getStringSet("stores", emptySet())?.toSet() ?: emptySet()

    fun save(ctx: Context, owner: String?, stores: Set<String>?) {
        val e = sp(ctx).edit()
        if (owner != null) e.putString("owner", owner)
        if (stores != null) e.putStringSet("stores", stores)
        e.apply()
    }
}

class ImportResult(
    val statement: Statement,
    val importId: Long,
    val added: Int,
    val dup: Int,
    val pendingNames: Int,
    val lastMonth: YearMonth,
)

object Importer {
    const val BANK = "Mandiri"

    fun account(st: Statement) = BANK + if (st.accountLast4.isNotEmpty()) " ${st.accountLast4}" else ""

    fun save(ctx: Context, st: Statement, fileName: String): ImportResult {
        val dbh = Db.get(ctx)
        val stores = Prefs.stores(ctx).toMutableSet()
        val results = Classifier.classify(st.txs, st.owner, stores)
        results.filter { it.catKey == Classifier.Cat.IN_SALES }.forEach { stores += it.party }
        Prefs.save(ctx, st.owner.ifBlank { null }, stores)

        val catIds = dbh.categoryIdsByKey()
        val rules = dbh.rules().associateBy { it.partyKey + "|" + it.dir }
        val acct = account(st)
        val db = dbh.writableDatabase
        var added = 0
        var dup = 0
        val importId: Long
        db.beginTransaction()
        try {
            importId = db.insert("import_log", null, ContentValues().apply {
                put("at", System.currentTimeMillis())
                put("file", fileName)
                put("account", acct)
                put("period", st.period)
                put("owner", st.owner)
                put("total", st.txs.size)
                if (st.closingBalance == null) putNull("closing") else put("closing", st.closingBalance)
            })
            for (r in results) {
                val raw = r.raw
                if (raw.`in` == 0L && raw.out == 0L) continue
                val dir = if (raw.`in` > 0) DIR_IN else DIR_OUT
                var kind = r.kind
                var book = r.book
                var cat: Long? = r.catKey?.let { catIds[it] }
                if (kind == KIND_NORMAL && r.partyKey.isNotEmpty()) {
                    rules[r.partyKey + "|" + dir]?.let {
                        kind = it.kind
                        book = it.book
                        cat = it.catId
                    }
                }
                val t = Tx(
                    ts = raw.`when`,
                    amount = r.amount(),
                    dir = dir,
                    kind = kind,
                    book = book,
                    catId = if (kind == KIND_NORMAL) cat else null,
                    partyKey = r.partyKey,
                    party = r.party,
                    type = r.type,
                    note = r.note,
                    raw = raw.remarks(),
                    source = SRC_IMPORT,
                    account = acct,
                    balance = raw.balance,
                    importKey = raw.key(acct),
                    importId = importId,
                )
                if (dbh.insertTx(db, t) == -1L) dup++ else added++
            }
            db.update("import_log", ContentValues().apply {
                put("added", added)
                put("dup", dup)
            }, "id=?", arrayOf(importId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val last = Fmt.toLocal(st.txs.maxOf { it.`when` })
        return ImportResult(st, importId, added, dup, dbh.countPendingForImport(importId), YearMonth.of(last.year, last.monthValue))
    }
}

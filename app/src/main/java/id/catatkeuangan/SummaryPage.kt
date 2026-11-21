package id.catatkeuangan

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SummaryPage(private val main: MainActivity) : Page {
    private val content = Ui.vbox(main, 12)
    override val view: View = ScrollView(main).apply {
        isFillViewport = true
        addView(content)
    }

    override fun refresh() {
        val ctx = main
        val db = main.db
        content.removeAllViews()
        content.addView(Ui.title(ctx, "Ringkasan").apply { setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4)) })
        content.addView(main.filterBar())

        val pending = db.pendingGroups()
        if (pending.isNotEmpty()) {
            val n = pending.sumOf { it.count }
            val b = Ui.vbox(ctx)
            b.addView(Ui.text(ctx, "${pending.size} nama belum dikenal", 15f, Ui.ORANGE, true))
            b.addView(Ui.text(ctx, "$n transaksi menunggu kategori. Pilih sekali, berikutnya otomatis.", 13f, Ui.SUB))
            b.addView(Ui.filled(ctx, "Pilih kategori") {
                ctx.startActivity(Intent(ctx, ReviewActivity::class.java))
            }, Ui.matchWrap().apply { topMargin = Ui.dp(ctx, 8) })
            content.addView(Ui.card(ctx, b, 14, Ui.WARN_BG))
        }

        val all = db.txRange(Fmt.monthStart(main.month), Fmt.monthEnd(main.month))
        val list = if (main.book == BOOK_ALL) all else all.filter { it.book == main.book }
        if (list.isEmpty()) {
            val e = Ui.vbox(ctx)
            e.addView(Ui.text(ctx, "Belum ada transaksi di ${Fmt.monthTitle(main.month)}" +
                if (main.book == BOOK_ALL) "." else " (buku ${bookName(main.book)}).", 15f, Ui.SUB))
            e.addView(Ui.filled(ctx, "Import e-statement") { main.openTab(MainActivity.TAB_IMPORT) },
                Ui.matchWrap().apply { topMargin = Ui.dp(ctx, 10) })
            e.addView(Ui.outlined(ctx, "Catat transaksi tunai") {
                TxEditor.show(ctx, null, main.defaultBook(), main.defaultTs()) { main.refreshCurrent() }
            }, Ui.matchWrap())
            content.addView(Ui.card(ctx, e))
            return
        }

        val cats = db.categoryMap()
        val counted = list.filter { it.counted }
        val income = counted.filter { it.dir == DIR_IN }.sumOf { it.amount }
        val expense = counted.filter { it.dir == DIR_OUT }.sumOf { it.amount }

        val tot = Ui.vbox(ctx)
        tot.addView(totalRow("Pemasukan", Fmt.rp(income), Ui.GREEN, false))
        tot.addView(totalRow("Pengeluaran", Fmt.rp(expense), Ui.RED, false))
        tot.addView(Ui.divider(ctx).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
        })
        val net = income - expense
        tot.addView(totalRow("Sisa", Fmt.rp(net, signed = true), if (net >= 0) Ui.GREEN else Ui.RED, true))
        content.addView(Ui.card(ctx, tot))

        // Penjualan QRIS toko
        val salesId = db.categoryIdsByKey()["in_sales"]
        val sales = counted.filter { it.dir == DIR_IN && it.catId == salesId }
        if (sales.isNotEmpty() && main.book != BOOK_PRIBADI) {
            val days = sales.map { Fmt.toLocal(it.ts).toLocalDate() }.distinct().size
            val sum = sales.sumOf { it.amount }
            val s = Ui.vbox(ctx)
            s.addView(Ui.text(ctx, "Penjualan QRIS", 13f, Ui.SUB, true))
            s.addView(Ui.text(ctx, Fmt.rp(sum), 18f, Ui.TEXT, true))
            s.addView(Ui.text(ctx, "${sales.size} transaksi di $days hari · rata-rata ${Fmt.rpShort(sum / maxOf(days, 1))}/hari",
                13f, Ui.SUB))
            content.addView(Ui.card(ctx, s))
        }

        // Yang tidak dihitung
        val internal = list.filter { it.kind == KIND_INTERNAL }
        val self = list.filter { it.kind == KIND_SELF }
        if (internal.isNotEmpty() || self.isNotEmpty()) {
            val n = Ui.vbox(ctx)
            n.addView(Ui.text(ctx, "Tidak dihitung", 13f, Ui.SUB, true))
            if (internal.isNotEmpty()) {
                val outSum = internal.filter { it.dir == DIR_OUT }.sumOf { it.amount }
                val inSum = internal.filter { it.dir == DIR_IN }.sumOf { it.amount }
                val parts = ArrayList<String>()
                if (outSum > 0) parts += "keluar ${Fmt.rp(outSum)}"
                if (inSum > 0) parts += "masuk ${Fmt.rp(inSum)}"
                n.addView(Ui.text(ctx, "• Pindah dana ke/dari rekening sendiri: " + parts.joinToString(", "), 13f, Ui.TEXT))
            }
            if (self.isNotEmpty()) {
                val pay = self.filter { it.dir == DIR_OUT }
                n.addView(Ui.text(ctx, "• Bayar QR ke toko sendiri: ${pay.size}× ${Fmt.rp(pay.sumOf { it.amount })} " +
                    "(kembali sebagai pencairan, saling meniadakan)", 13f, Ui.TEXT))
            }
            val c = Ui.card(ctx, n, 14, Ui.OK_BG)
            c.setOnClickListener { main.openTx(TxFilter(TxFilter.NOT_COUNTED, label = "Tidak dihitung")) }
            content.addView(c)
        }

        db.latestBalance()?.let { (ts, bal, acct) ->
            content.addView(Ui.text(ctx, "Saldo $acct per ${Fmt.dateTime(ts)}: ${Fmt.rp(bal)}", 13f, Ui.SUB).apply {
                setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            })
        }

        content.addView(Ui.section(ctx, "Pengeluaran per kategori"))
        breakdown(counted.filter { it.dir == DIR_OUT }, expense, Ui.RED, cats, DIR_OUT)
        content.addView(Ui.section(ctx, "Pemasukan per kategori"))
        breakdown(counted.filter { it.dir == DIR_IN }, income, Ui.GREEN, cats, DIR_IN)
    }

    private fun totalRow(label: String, value: String, color: Int, big: Boolean): View {
        val r = Ui.hbox(main)
        r.setPadding(0, Ui.dp(main, 3), 0, Ui.dp(main, 3))
        r.addView(Ui.text(main, label, if (big) 16f else 15f, Ui.TEXT, big), Ui.weight(1f))
        r.addView(Ui.text(main, value, if (big) 20f else 16f, color, true))
        return r
    }

    private fun breakdown(items: List<Tx>, total: Long, color: Int, cats: Map<Long, Category>, dir: Int) {
        val ctx = main
        if (items.isEmpty()) {
            content.addView(Ui.text(ctx, "Belum ada.", 13f, Ui.GRAY).apply { setPadding(Ui.dp(ctx, 4), 0, 0, 0) })
            return
        }
        val groups = items.groupBy { it.catId }
            .map { (id, l) -> Triple(id, l.sumOf { it.amount }, l.size) }
            .sortedByDescending { it.second }
        val box = Ui.vbox(ctx)
        groups.forEachIndexed { i, (id, sum, n) ->
            if (i > 0) box.addView(Ui.divider(ctx))
            val row = Ui.vbox(ctx)
            row.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10))
            val top = Ui.hbox(ctx)
            val name: TextView = if (id == null) Ui.text(ctx, "Belum dikategorikan", 15f, Ui.ORANGE, true)
            else Ui.text(ctx, cats[id]?.name ?: "(kategori terhapus)", 15f, Ui.TEXT)
            top.addView(name, Ui.weight(1f))
            top.addView(Ui.text(ctx, Fmt.rp(sum), 15f, Ui.TEXT, true))
            row.addView(top)
            val pct = if (total > 0) sum * 100.0 / total else 0.0
            row.addView(Ui.text(ctx, "$n transaksi · ${"%.0f".format(pct)}%", 12f, Ui.SUB))
            row.addView(Ui.bar(ctx, (pct / 100.0).toFloat(), if (id == null) Ui.ORANGE else color))
            row.setOnClickListener {
                if (id == null) ctx.startActivity(Intent(ctx, ReviewActivity::class.java))
                else main.openTx(TxFilter(TxFilter.CATEGORY, id, dir, cats[id]?.name ?: ""))
            }
            box.addView(row)
        }
        content.addView(Ui.card(ctx, box, 14))
    }
}

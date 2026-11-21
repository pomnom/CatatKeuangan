package id.catatkeuangan

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import java.time.LocalDateTime

/** Pemilih kategori: daftar kategori sesuai arah, opsi "pindah dana", dan "kategori baru". */
object Pickers {
    fun category(
        ctx: Context,
        dir: Int,
        book: Int,
        allowInternal: Boolean,
        onPick: (cat: Category?, internal: Boolean) -> Unit,
    ) {
        val db = Db.get(ctx)
        val cats = db.categories(includeHidden = false).filter { it.dir == dir }
        val labels = ArrayList<String>()
        if (allowInternal) labels += "↔ Pindah dana ke/dari rekening sendiri (tidak dihitung)"
        cats.forEach { labels += it.name + if (it.book == BOOK_USAHA) "  · usaha" else "" }
        labels += "+ Kategori baru…"
        val offset = if (allowInternal) 1 else 0
        Ui.dialog(ctx)
            .setTitle(if (dir == DIR_IN) "Kategori pemasukan" else "Kategori pengeluaran")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    allowInternal && which == 0 -> onPick(null, true)
                    which == labels.size - 1 -> Ui.prompt(ctx, "Kategori baru", "Nama kategori", "") { name ->
                        val id = db.addCategory(name, dir, book)
                        onPick(db.categoryMap()[id], false)
                    }
                    else -> onPick(cats[which - offset], false)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}

object TxEditor {
    fun show(ctx: Context, existing: Tx?, defaultBook: Int, defaultTs: Long, onSaved: () -> Unit) {
        val db = Db.get(ctx)
        val cats = db.categoryMap()
        val isImport = existing?.source == SRC_IMPORT
        val t = existing?.copy() ?: Tx(
            ts = defaultTs, dir = DIR_OUT, book = defaultBook, source = SRC_MANUAL, account = "Tunai", type = "Tunai",
        )
        val originalKind = t.kind

        val box = Ui.vbox(ctx, 20)
        val scroll = ScrollView(ctx).apply { addView(box) }

        // ----- bagian atas -----
        val amountField = Ui.input(ctx, "Jumlah (Rp)", if (t.amount > 0) (t.amount / 100).toString() else "",
            InputType.TYPE_CLASS_NUMBER)
        val partyField = Ui.input(ctx, "Keterangan (mis. beli bensin)", t.party)
        val memoField = Ui.input(ctx, "Catatan (opsional)", t.memo)
        val dateBtn = Ui.outlined(ctx, "") {}
        val timeBtn = Ui.outlined(ctx, "") {}
        lateinit var catBtn: MaterialButton
        lateinit var catRow: View
        lateinit var bookToggleHolder: LinearLayout

        fun refreshDate() {
            dateBtn.text = Fmt.date(t.ts)
            timeBtn.text = Fmt.time(t.ts)
        }

        fun refreshCat() {
            val c = t.catId?.let { cats[it] ?: db.categoryMap()[it] }
            catBtn.text = c?.name ?: "Pilih kategori ▾"
            catBtn.setTextColor(if (c == null) Ui.ORANGE else Ui.PRIMARY)
            catRow.visibility = if (t.kind == KIND_NORMAL) View.VISIBLE else View.GONE
        }

        if (isImport) {
            val info = Ui.vbox(ctx)
            info.addView(Ui.text(ctx, t.party, 17f, Ui.TEXT, true))
            info.addView(Ui.text(ctx, Fmt.rp(t.signed, signed = true), 20f, if (t.dir == DIR_IN) Ui.GREEN else Ui.RED, true))
            info.addView(Ui.text(ctx, Fmt.dateTime(t.ts) + " · " + t.account, 13f, Ui.SUB))
            if (t.type.isNotEmpty()) info.addView(Ui.text(ctx, t.type, 13f, Ui.SUB))
            if (t.note.isNotEmpty()) info.addView(Ui.text(ctx, "“" + t.note + "”", 13f, Ui.SUB))
            info.addView(Ui.text(ctx, t.raw, 12f, Ui.GRAY).apply { setPadding(0, Ui.dp(ctx, 6), 0, 0) })
            t.balance?.let { info.addView(Ui.text(ctx, "Saldo sesudahnya: " + Fmt.rp(it), 12f, Ui.GRAY)) }
            box.addView(Ui.card(ctx, info, 12, Ui.BG))
        } else {
            box.addView(Ui.toggle(ctx, listOf("Pengeluaran", "Pemasukan"), if (t.dir == DIR_IN) 1 else 0) { i ->
                val nd = if (i == 1) DIR_IN else DIR_OUT
                if (nd != t.dir) {
                    t.dir = nd
                    t.catId = null
                    refreshCat()
                }
            }.apply { layoutParams = Ui.matchWrap() })
            box.addView(amountField)
            box.addView(partyField)
            val dt = Ui.hbox(ctx).apply { layoutParams = Ui.matchWrap().apply { topMargin = Ui.dp(ctx, 8) } }
            dt.addView(dateBtn, Ui.weight(1.4f))
            dt.addView(View(ctx), LinearLayout.LayoutParams(Ui.dp(ctx, 8), 1))
            dt.addView(timeBtn, Ui.weight(1f))
            box.addView(dt)
            dateBtn.setOnClickListener {
                val d = Fmt.toLocal(t.ts)
                DatePickerDialog(ctx, { _, y, m, day ->
                    t.ts = Fmt.toTs(LocalDateTime.of(y, m + 1, day, d.hour, d.minute))
                    refreshDate()
                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }
            timeBtn.setOnClickListener {
                val d = Fmt.toLocal(t.ts)
                TimePickerDialog(ctx, { _, h, min ->
                    t.ts = Fmt.toTs(d.withHour(h).withMinute(min).withSecond(0))
                    refreshDate()
                }, d.hour, d.minute, true).show()
            }
            refreshDate()
        }

        // ----- dihitung / tidak -----
        box.addView(Ui.section(ctx, "Dihitung di laporan?"))
        val countedIdx = if (t.kind == KIND_NORMAL) 0 else 1
        box.addView(Ui.toggle(ctx, listOf("Dihitung", "Tidak (pindah dana)"), countedIdx) { i ->
            t.kind = if (i == 0) KIND_NORMAL else if (originalKind != KIND_NORMAL) originalKind else KIND_INTERNAL
            refreshCat()
            bookToggleHolder.visibility = if (t.kind == KIND_NORMAL) View.VISIBLE else View.GONE
        }.apply { layoutParams = Ui.matchWrap() })
        if (originalKind == KIND_SELF) {
            box.addView(Ui.text(ctx, "Bayar QR ke toko sendiri yang langsung kembali sebagai pencairan — " +
                "pasangannya juga tidak dihitung.", 12f, Ui.GRAY).apply { setPadding(0, Ui.dp(ctx, 4), 0, 0) })
        }

        // ----- buku & kategori -----
        bookToggleHolder = Ui.vbox(ctx)
        bookToggleHolder.addView(Ui.section(ctx, "Buku"))
        bookToggleHolder.addView(Ui.toggle(ctx, listOf("Pribadi", "Usaha"), if (t.book == BOOK_USAHA) 1 else 0) { i ->
            t.book = if (i == 1) BOOK_USAHA else BOOK_PRIBADI
        }.apply { layoutParams = Ui.matchWrap() })
        bookToggleHolder.visibility = if (t.kind == KIND_NORMAL) View.VISIBLE else View.GONE
        box.addView(bookToggleHolder)

        val catBox = Ui.vbox(ctx)
        catBox.addView(Ui.section(ctx, "Kategori"))
        catBtn = Ui.outlined(ctx, "") {
            Pickers.category(ctx, t.dir, t.book, allowInternal = false) { c, _ ->
                if (c != null) {
                    t.catId = c.id
                    refreshCat()
                }
            }
        }
        catBox.addView(catBtn, Ui.matchWrap())
        catRow = catBox
        box.addView(catBox)

        var remember: CheckBox? = null
        if (isImport) {
            box.addView(memoField)
            if (t.partyKey.isNotEmpty() && originalKind != KIND_SELF) {
                remember = CheckBox(ctx).apply {
                    text = "Ingat untuk semua transaksi “${t.party}” (${if (t.dir == DIR_IN) "masuk" else "keluar"})"
                    isChecked = true
                    setPadding(0, Ui.dp(ctx, 6), 0, 0)
                }
                box.addView(remember)
            }
        }
        refreshCat()

        val b = Ui.dialog(ctx)
            .setTitle(if (existing == null) "Catat transaksi" else "Ubah transaksi")
            .setView(scroll)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
        if (existing != null) {
            b.setNeutralButton("Hapus") { _, _ ->
                Ui.confirm(ctx, "Hapus transaksi?",
                    if (isImport) "Transaksi ini akan muncul lagi bila e-statement yang sama di-import ulang."
                    else "Transaksi ini akan dihapus.", "Hapus") {
                    db.deleteTx(existing.id)
                    onSaved()
                }
            }
        }
        val d: AlertDialog = b.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isImport) {
                    val amt = Fmt.parseAmount(amountField.value())
                    if (amt == null) {
                        amountField.error = "Isi jumlahnya"
                        return@setOnClickListener
                    }
                    amountField.error = null
                    t.amount = amt
                    t.party = partyField.value()
                }
                if (t.kind == KIND_NORMAL && t.catId == null) {
                    Toast.makeText(ctx, "Pilih kategori dulu", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (t.kind != KIND_NORMAL) t.catId = null
                if (!isImport && t.party.isEmpty()) t.party = t.catId?.let { cats[it]?.name ?: db.categoryMap()[it]?.name } ?: "Tunai"
                if (isImport) t.memo = memoField.value()
                if (existing == null) db.insertTx(t) else db.updateTx(t)
                if (remember?.isChecked == true) {
                    db.saveRule(Rule(t.partyKey, t.dir, t.party, t.kind, t.book, t.catId))
                }
                d.dismiss()
                onSaved()
            }
        }
        d.show()
    }
}

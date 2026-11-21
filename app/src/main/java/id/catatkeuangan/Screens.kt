package id.catatkeuangan

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView

/** Layar sederhana: bilah judul dengan tombol kembali + isi yang bisa digulir. */
abstract class ScreenActivity : AppCompatActivity() {
    protected lateinit var db: Db
    protected lateinit var content: LinearLayout
    protected lateinit var bottom: LinearLayout

    abstract val screenTitle: String
    abstract fun build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        val root = Ui.vbox(this).apply { setBackgroundColor(Ui.BG) }
        val bar = Ui.hbox(this)
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4))
        bar.addView(Ui.iconButton(this, R.drawable.ic_arrow_back, "Kembali") { finish() })
        bar.addView(Ui.text(this, screenTitle, 19f, Ui.TEXT, true), Ui.weight(1f))
        root.addView(bar, Ui.matchWrap())
        content = Ui.vbox(this, 12)
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottom = Ui.vbox(this).apply {
            setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 12))
            setBackgroundColor(Ui.CARD)
            visibility = View.GONE
        }
        root.addView(bottom, Ui.matchWrap())
        setContentView(root)
        build()
    }
}

/** Tanya sekali untuk nama baru: pilih kategori & buku, lalu diingat. */
class ReviewActivity : ScreenActivity() {
    override val screenTitle = "Kategorikan nama baru"

    private class State(var book: Int, var catId: Long? = null, var internal: Boolean = false, var saved: Boolean = false)

    override fun build() {
        val groups = db.pendingGroups()
        content.addView(Ui.text(this,
            "Pilih kategori untuk tiap nama. Pilihan langsung disimpan dan dipakai otomatis di import berikutnya.",
            14f, Ui.SUB).apply { setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), Ui.dp(context, 10)) })
        if (groups.isEmpty()) {
            content.addView(Ui.text(this, "Semua transaksi sudah punya kategori. 👍", 15f, Ui.TEXT))
        }
        val cats = db.categoryMap()
        for (g in groups) {
            val st = State(BOOK_PRIBADI)
            val box = Ui.vbox(this)
            box.addView(Ui.text(this, g.party.ifEmpty { "(tanpa nama)" }, 16f, Ui.TEXT, true))
            val dirText = if (g.dir == DIR_IN) "Masuk" else "Keluar"
            box.addView(Ui.text(this, "$dirText · ${g.count}× · ${Fmt.rp(g.total)}", 14f,
                if (g.dir == DIR_IN) Ui.GREEN else Ui.RED, true))
            if (g.type.isNotEmpty()) box.addView(Ui.text(this, g.type, 12f, Ui.SUB))
            if (g.note.isNotEmpty()) box.addView(Ui.text(this, "“${g.note}”", 12f, Ui.SUB))

            val status = Ui.text(this, "", 13f, Ui.PRIMARY, true).apply { visibility = View.GONE }
            lateinit var card: MaterialCardView
            lateinit var catBtn: MaterialButton
            lateinit var bookGroup: MaterialButtonToggleGroup

            fun save() {
                if (!st.internal && st.catId == null) return
                db.saveRule(Rule(g.partyKey, g.dir, g.party,
                    if (st.internal) KIND_INTERNAL else KIND_NORMAL, st.book, if (st.internal) null else st.catId))
                st.saved = true
                val label = if (st.internal) "Pindah dana (tidak dihitung)"
                else (cats[st.catId!!]?.name ?: db.categoryMap()[st.catId!!]?.name ?: "") + " · " + bookName(st.book)
                status.text = "✓ Disimpan: $label"
                status.visibility = View.VISIBLE
                catBtn.text = if (st.internal) "Pindah dana" else (cats[st.catId!!]?.name ?: db.categoryMap()[st.catId!!]?.name ?: "Kategori")
                card.setCardBackgroundColor(Ui.OK_BG)
                bookGroup.visibility = if (st.internal) View.GONE else View.VISIBLE
            }

            bookGroup = Ui.toggle(this, listOf("Pribadi", "Usaha"), 0) { i ->
                val nb = if (i == 1) BOOK_USAHA else BOOK_PRIBADI
                if (nb != st.book) {
                    st.book = nb
                    save()
                }
            }
            box.addView(bookGroup, Ui.matchWrap().apply { topMargin = Ui.dp(this@ReviewActivity, 10) })
            catBtn = Ui.outlined(this, "Pilih kategori ▾") {
                Pickers.category(this, g.dir, st.book, allowInternal = true) { c, internal ->
                    st.internal = internal
                    if (!internal && c != null) {
                        st.catId = c.id
                        if (c.book != st.book) {
                            st.book = c.book
                            bookGroup.check(bookGroup.getChildAt(if (c.book == BOOK_USAHA) 1 else 0).id)
                        }
                    }
                    save()
                }
            }
            box.addView(catBtn, Ui.matchWrap())
            box.addView(status)
            card = Ui.card(this, box)
            content.addView(card)
        }
        bottom.visibility = View.VISIBLE
        bottom.addView(Ui.filled(this, "Selesai") { finish() }, Ui.matchWrap())
    }
}

/** Kelola kategori: ganti nama, buku bawaan, sembunyikan, tambah. */
class CategoriesActivity : ScreenActivity() {
    override val screenTitle = "Kategori"

    override fun build() {
        reload()
    }

    private fun reload() {
        content.removeAllViews()
        val all = db.categories()
        for (dir in listOf(DIR_IN, DIR_OUT)) {
            content.addView(Ui.section(this, if (dir == DIR_IN) "Pemasukan" else "Pengeluaran"))
            val box = Ui.vbox(this)
            all.filter { it.dir == dir }.forEachIndexed { i, c ->
                if (i > 0) box.addView(Ui.divider(this))
                val row = Ui.hbox(this)
                row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12))
                row.addView(Ui.text(this, c.name, 15f, if (c.hidden) Ui.GRAY else Ui.TEXT), Ui.weight(1f))
                row.addView(Ui.text(this, (if (c.hidden) "disembunyikan · " else "") + bookName(c.book), 12f, Ui.SUB))
                row.setOnClickListener { edit(c) }
                box.addView(row)
            }
            content.addView(Ui.card(this, box))
            content.addView(Ui.flat(this, "+ Tambah kategori " + if (dir == DIR_IN) "pemasukan" else "pengeluaran") {
                Ui.prompt(this, "Kategori baru", "Nama kategori", "") { name ->
                    db.addCategory(name, dir, BOOK_PRIBADI)
                    reload()
                }
            })
        }
    }

    private fun edit(c: Category) {
        val box = Ui.vbox(this, 20)
        val name = Ui.input(this, "Nama", c.name)
        box.addView(name)
        box.addView(Ui.section(this, "Buku bawaan"))
        var book = c.book
        box.addView(Ui.toggle(this, listOf("Pribadi", "Usaha"), if (c.book == BOOK_USAHA) 1 else 0) { i ->
            book = if (i == 1) BOOK_USAHA else BOOK_PRIBADI
        }, Ui.matchWrap())
        val hide = CheckBox(this).apply {
            text = "Sembunyikan dari pilihan"
            isChecked = c.hidden
        }
        box.addView(hide)
        Ui.dialog(this).setTitle("Ubah kategori").setView(box).setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val n = name.value().ifEmpty { c.name }
                db.updateCategory(c.id, n, book, hide.isChecked)
                reload()
            }.show()
    }
}

/** Daftar nama yang sudah diingat beserta kategorinya. */
class RulesActivity : ScreenActivity() {
    override val screenTitle = "Nama yang diingat"

    override fun build() {
        reload()
    }

    private fun reload() {
        content.removeAllViews()
        val rules = db.rules()
        val cats = db.categoryMap()
        if (rules.isEmpty()) {
            content.addView(Ui.text(this, "Belum ada. Nama tersimpan saat Anda memilih kategori untuk nama baru.", 14f, Ui.SUB))
            return
        }
        content.addView(Ui.text(this, "Ketuk untuk mengubah. Perubahan juga diterapkan ke transaksi lama dengan nama itu.",
            13f, Ui.SUB).apply { setPadding(Ui.dp(context, 4), 0, 0, Ui.dp(context, 8)) })
        val box = Ui.vbox(this)
        rules.forEachIndexed { i, r ->
            if (i > 0) box.addView(Ui.divider(this))
            val row = Ui.vbox(this)
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10))
            row.addView(Ui.text(this, r.party + if (r.dir == DIR_IN) "  (masuk)" else "  (keluar)", 15f, Ui.TEXT, true))
            val target = if (r.kind == KIND_INTERNAL) "Pindah dana (tidak dihitung)"
            else (r.catId?.let { cats[it]?.name } ?: "-") + " · " + bookName(r.book)
            row.addView(Ui.text(this, "→ $target", 13f, Ui.SUB))
            row.setOnClickListener { options(r) }
            box.addView(row)
        }
        content.addView(Ui.card(this, box))
    }

    private fun options(r: Rule) {
        Ui.dialog(this).setTitle(r.party).setItems(arrayOf("Ubah kategori", "Lupakan nama ini")) { _, w ->
            if (w == 0) {
                Pickers.category(this, r.dir, r.book, allowInternal = true) { c, internal ->
                    val book = c?.book ?: r.book
                    db.saveRule(r.copy(kind = if (internal) KIND_INTERNAL else KIND_NORMAL, book = book,
                        catId = if (internal) null else c?.id))
                    reload()
                }
            } else {
                Ui.confirm(this, "Lupakan ${r.party}?",
                    "Transaksi yang sudah ada tetap memakai kategorinya. Di import berikutnya nama ini ditanyakan lagi.",
                    "Lupakan") {
                    db.deleteRule(r.partyKey, r.dir)
                    reload()
                }
            }
        }.show()
    }
}

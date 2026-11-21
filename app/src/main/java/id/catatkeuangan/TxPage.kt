package id.catatkeuangan

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.time.LocalDate
import java.util.Locale

class TxPage(private val main: MainActivity) : Page {
    private val root = FrameLayout(main)
    private val header = Ui.vbox(main)
    private val searchField = Ui.input(main, "Cari nama atau keterangan")
    private val empty = Ui.text(main, "Tidak ada transaksi.", 14f, Ui.GRAY).apply {
        gravity = Gravity.CENTER
        setPadding(0, Ui.dp(main, 32), 0, 0)
        visibility = View.GONE
    }
    private val adapter = TxAdapter(main) { tx ->
        TxEditor.show(main, tx, main.defaultBook(), main.defaultTs()) { main.refreshCurrent() }
    }
    private var monthTx: List<Tx> = emptyList()
    private var cats: Map<Long, Category> = emptyMap()

    override val view: View = root

    init {
        val column = Ui.vbox(main)
        column.setPadding(Ui.dp(main, 12), 0, Ui.dp(main, 12), 0)
        column.addView(header, Ui.matchWrap())
        column.addView(searchField)
        searchField.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        column.addView(empty, Ui.matchWrap())
        val rv = RecyclerView(main).apply {
            layoutManager = LinearLayoutManager(main)
            adapter = this@TxPage.adapter
            clipToPadding = false
            setPadding(0, Ui.dp(main, 4), 0, Ui.dp(main, 96))
        }
        column.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val fab = ExtendedFloatingActionButton(main).apply {
            text = "Catat"
            setIconResource(R.drawable.ic_add)
            setOnClickListener {
                TxEditor.show(main, null, main.defaultBook(), main.defaultTs()) { main.refreshCurrent() }
            }
        }
        root.addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply {
            val m = Ui.dp(main, 16)
            setMargins(m, m, m, m)
        })
    }

    override fun refresh() {
        header.removeAllViews()
        header.addView(Ui.title(main, "Transaksi").apply { setPadding(Ui.dp(main, 4), Ui.dp(main, 8), 0, Ui.dp(main, 4)) })
        header.addView(main.filterBar())
        main.txFilter?.let { f ->
            val chip = Ui.hbox(main)
            val label = when (f.mode) {
                TxFilter.CATEGORY -> "Kategori: " + f.label
                TxFilter.PENDING -> "Belum dikategorikan"
                else -> f.label.ifEmpty { "Tidak dihitung" }
            }
            chip.addView(Ui.pill(main, label, Ui.PRIMARY, Ui.OK_BG))
            chip.addView(Ui.flat(main, "Hapus saringan") {
                main.txFilter = null
                refresh()
            })
            header.addView(chip, Ui.matchWrap())
        }
        cats = main.db.categoryMap()
        monthTx = main.db.txRange(Fmt.monthStart(main.month), Fmt.monthEnd(main.month))
        adapter.cats = cats
        adapter.showBook = main.book == BOOK_ALL
        applyFilter()
    }

    private fun applyFilter() {
        val q = (searchField.editText?.text?.toString() ?: "").trim().lowercase(Locale.ROOT)
        val f = main.txFilter
        val list = monthTx.filter { t ->
            (main.book == BOOK_ALL || t.book == main.book) &&
                when (f?.mode) {
                    TxFilter.CATEGORY -> t.counted && t.catId == f.catId && (f.dir == 0 || t.dir == f.dir)
                    TxFilter.PENDING -> t.counted && t.catId == null
                    TxFilter.NOT_COUNTED -> !t.counted
                    else -> true
                } &&
                (q.isEmpty() || listOf(t.party, t.note, t.memo, t.type, t.raw, t.catId?.let { cats[it]?.name } ?: "")
                    .any { it.lowercase(Locale.ROOT).contains(q) })
        }
        val items = ArrayList<Any>()
        var day: LocalDate? = null
        for (t in list) {
            val d = Fmt.toLocal(t.ts).toLocalDate()
            if (d != day) {
                day = d
                val net = list.filter { it.counted && Fmt.toLocal(it.ts).toLocalDate() == d }.sumOf { it.signed }
                items += DayHeader(d, net)
            }
            items += t
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}

data class DayHeader(val date: LocalDate, val net: Long)

class TxAdapter(private val main: MainActivity, private val onClick: (Tx) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<Any> = emptyList()
    var cats: Map<Long, Category> = emptyMap()
    var showBook = false

    private class HeaderHolder(val box: LinearLayout, val title: TextView, val net: TextView) : RecyclerView.ViewHolder(box)
    private class RowHolder(
        val box: LinearLayout, val party: TextView, val sub: TextView, val amount: TextView, val time: TextView,
    ) : RecyclerView.ViewHolder(box)

    override fun getItemViewType(position: Int) = if (items[position] is DayHeader) 0 else 1

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return if (viewType == 0) {
            val box = Ui.hbox(ctx)
            box.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 14), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            val title = Ui.text(ctx, "", 13f, Ui.SUB, true)
            val net = Ui.text(ctx, "", 12f, Ui.GRAY)
            box.addView(title, Ui.weight(1f))
            box.addView(net)
            box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            HeaderHolder(box, title, net)
        } else {
            val box = Ui.hbox(ctx)
            box.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
            box.setBackgroundColor(Ui.CARD)
            val left = Ui.vbox(ctx)
            val party = Ui.text(ctx, "", 15f, Ui.TEXT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val sub = Ui.text(ctx, "", 12f, Ui.SUB).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            left.addView(party)
            left.addView(sub)
            box.addView(left, Ui.weight(1f))
            val right = Ui.vbox(ctx).apply { gravity = Gravity.END }
            val amount = Ui.text(ctx, "", 15f, Ui.TEXT, true).apply { gravity = Gravity.END }
            val time = Ui.text(ctx, "", 12f, Ui.GRAY).apply { gravity = Gravity.END }
            right.addView(amount)
            right.addView(time)
            box.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ui.dp(ctx, 8)
            })
            box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 1
            }
            RowHolder(box, party, sub, amount, time)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderHolder && item is DayHeader) {
            holder.title.text = Fmt.dayTitle(item.date)
            holder.net.text = if (item.net == 0L) "" else Fmt.rp(item.net, signed = true)
        } else if (holder is RowHolder && item is Tx) {
            holder.party.text = item.party.ifEmpty { item.type.ifEmpty { "Transaksi" } }
            val catLabel = when {
                item.kind == KIND_INTERNAL -> "Pindah dana"
                item.kind == KIND_SELF -> "Netral (toko sendiri)"
                item.catId == null -> "Belum dikategorikan"
                else -> item.catId?.let { cats[it]?.name } ?: "-"
            }
            val parts = ArrayList<String>()
            parts += catLabel
            if (showBook && item.counted) parts += bookName(item.book)
            if (item.memo.isNotEmpty()) parts += item.memo
            else if (item.type.isNotEmpty() && item.type != item.party) parts += item.type
            holder.sub.text = parts.joinToString(" · ")
            holder.sub.setTextColor(if (item.counted && item.catId == null) Ui.ORANGE else Ui.SUB)
            holder.amount.text = Fmt.rp(item.signed, signed = true)
            holder.amount.setTextColor(
                when {
                    !item.counted -> Ui.GRAY
                    item.dir == DIR_IN -> Ui.GREEN
                    else -> Ui.RED
                }
            )
            holder.time.text = Fmt.time(item.ts)
            holder.box.setOnClickListener { onClick(item) }
        }
    }
}

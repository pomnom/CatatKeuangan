package id.catatkeuangan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.textfield.TextInputLayout
import id.catatkeuangan.core.OfficeCrypto
import id.catatkeuangan.core.Statement
import id.catatkeuangan.core.StatementException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.YearMonth

interface Page {
    val view: View
    fun refresh()
}

/** Saringan daftar transaksi yang dibuka dari Ringkasan. */
data class TxFilter(val mode: Int, val catId: Long? = null, val dir: Int = 0, val label: String = "") {
    companion object {
        const val CATEGORY = 1
        const val PENDING = 2
        const val NOT_COUNTED = 3
    }
}

class MainActivity : AppCompatActivity() {
    lateinit var db: Db
    var month: YearMonth = YearMonth.now()
    var book: Int = BOOK_PRIBADI
    var txFilter: TxFilter? = null

    private lateinit var container: FrameLayout
    private lateinit var nav: BottomNavigationView
    private val pages = HashMap<Int, Page>()
    private var currentTab = TAB_SUMMARY

    companion object {
        const val TAB_SUMMARY = 1
        const val TAB_TX = 2
        const val TAB_IMPORT = 3
        const val TAB_SETTINGS = 4
        private const val MAX_FILE = 30 * 1024 * 1024
    }

    private val pickStatement = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) startImport(uri)
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) Backup.writeTo(this, uri)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Backup.askRestore(this, uri) { refreshCurrent() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        month = db.latestMonth() ?: YearMonth.now()
        book = getSharedPreferences("catat", Context.MODE_PRIVATE).getInt("book", BOOK_PRIBADI)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nav = BottomNavigationView(this).apply {
            menu.add(0, TAB_SUMMARY, 0, "Ringkasan").setIcon(R.drawable.ic_home)
            menu.add(0, TAB_TX, 1, "Transaksi").setIcon(R.drawable.ic_list)
            menu.add(0, TAB_IMPORT, 2, "Import").setIcon(R.drawable.ic_upload)
            menu.add(0, TAB_SETTINGS, 3, "Pengaturan").setIcon(R.drawable.ic_settings)
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            setBackgroundColor(Ui.CARD)
            setOnItemSelectedListener { item ->
                showTab(item.itemId)
                true
            }
        }
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        val start = if (db.latestMonth() == null) TAB_IMPORT else TAB_SUMMARY
        nav.selectedItemId = start
        if (pages.isEmpty()) showTab(start)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (pages.isNotEmpty()) refreshCurrent()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            nav.selectedItemId = TAB_IMPORT
            startImport(uri)
        }
    }

    private fun page(tab: Int): Page = pages.getOrPut(tab) {
        when (tab) {
            TAB_TX -> TxPage(this)
            TAB_IMPORT -> ImportPage(this)
            TAB_SETTINGS -> SettingsPage(this)
            else -> SummaryPage(this)
        }
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        val p = page(tab)
        container.removeAllViews()
        (p.view.parent as? ViewGroup)?.removeView(p.view)
        container.addView(p.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.refresh()
    }

    fun refreshCurrent() {
        pages[currentTab]?.refresh()
    }

    /** Buka tab Transaksi dengan saringan tertentu. */
    fun openTx(filter: TxFilter?) {
        txFilter = filter
        if (nav.selectedItemId == TAB_TX) refreshCurrent() else nav.selectedItemId = TAB_TX
    }

    fun openTab(tab: Int) {
        nav.selectedItemId = tab
    }

    // ---------- bulan & buku ----------

    fun filterBar(): View {
        val box = Ui.vbox(this)
        val row = Ui.hbox(this)
        row.addView(Ui.iconButton(this, R.drawable.ic_chevron_left, "Bulan sebelumnya") {
            month = month.minusMonths(1)
            container.post { refreshCurrent() }
        })
        val title = Ui.text(this, Fmt.monthTitle(month) + " ▾", 18f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
            setOnClickListener { pickMonth() }
        }
        row.addView(title, Ui.weight(1f))
        row.addView(Ui.iconButton(this, R.drawable.ic_chevron_right, "Bulan berikutnya") {
            month = month.plusMonths(1)
            container.post { refreshCurrent() }
        })
        box.addView(row, Ui.matchWrap())
        val idx = when (book) {
            BOOK_USAHA -> 1
            BOOK_ALL -> 2
            else -> 0
        }
        box.addView(Ui.toggle(this, listOf("Pribadi", "Usaha", "Semua"), idx) { i ->
            book = when (i) {
                1 -> BOOK_USAHA
                2 -> BOOK_ALL
                else -> BOOK_PRIBADI
            }
            getSharedPreferences("catat", Context.MODE_PRIVATE).edit().putInt("book", book).apply()
            container.post { refreshCurrent() }
        }, Ui.matchWrap().apply { topMargin = Ui.dp(this@MainActivity, 4); bottomMargin = Ui.dp(this@MainActivity, 8) })
        return box
    }

    private fun pickMonth() {
        val months = db.monthsWithData().toMutableList()
        val now = YearMonth.now()
        if (now !in months) months.add(0, now)
        val labels = months.map { Fmt.monthTitle(it) }.toTypedArray()
        Ui.dialog(this).setTitle("Pilih bulan").setItems(labels) { _, w ->
            month = months[w]
            container.post { refreshCurrent() }
        }.show()
    }

    /** Waktu bawaan untuk transaksi manual baru di bulan yang sedang dilihat. */
    fun defaultTs(): Long =
        if (month == YearMonth.now()) Fmt.nowTs()
        else Fmt.toTs(month.atEndOfMonth().atTime(12, 0))

    fun defaultBook(): Int = if (book == BOOK_ALL) BOOK_PRIBADI else book

    // ---------- import ----------

    fun chooseStatement() {
        pickStatement.launch(arrayOf("*/*"))
    }

    fun chooseBackupTarget() {
        createBackup.launch("catat-keuangan-" + java.time.LocalDate.now() + ".json")
    }

    fun chooseBackupSource() {
        openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: (uri.lastPathSegment ?: "e-statement")
    } catch (e: Exception) {
        uri.lastPathSegment ?: "e-statement"
    }

    private fun readAll(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (true) {
            val r = input.read(buf)
            if (r <= 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_FILE) return null
        }
        return out.toByteArray()
    }

    fun startImport(uri: Uri) {
        val name = displayName(uri)
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { readAll(it) }
        } catch (e: Exception) {
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            Ui.info(this, "File tidak bisa dibuka", "File tidak bisa dibaca atau terlalu besar. Pilih file e-statement Excel (.xlsx) dari Livin'.")
            return
        }
        if (OfficeCrypto.isEncrypted(bytes)) askPassword(bytes, name, null) else runImport(bytes, name, "")
    }

    private fun askPassword(bytes: ByteArray, name: String, error: String?) {
        val box = Ui.vbox(this, 20)
        box.addView(Ui.text(this, name, 13f, Ui.SUB))
        val til = Ui.input(this, "Password e-statement", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        til.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        if (error != null) til.error = error
        box.addView(til)
        box.addView(Ui.text(this, "Password hanya dipakai untuk membuka file ini dan tidak disimpan.", 12f, Ui.GRAY).apply {
            setPadding(0, Ui.dp(context, 8), 0, 0)
        })
        val d: AlertDialog = Ui.dialog(this).setTitle("File dikunci password").setView(box)
            .setNegativeButton("Batal", null).setPositiveButton("Buka", null).create()
        d.setOnShowListener {
            til.editText?.requestFocus()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = til.editText?.text?.toString() ?: ""
                if (pw.isEmpty()) {
                    til.error = "Isi password dulu"
                } else {
                    d.dismiss()
                    runImport(bytes, name, pw)
                }
            }
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun runImport(bytes: ByteArray, name: String, password: String) {
        val box = Ui.hbox(this).apply {
            val p = Ui.dp(context, 24)
            setPadding(p, p, p, p)
            addView(ProgressBar(context))
            addView(Ui.text(context, "Membuka & membaca e-statement…", 15f).apply {
                setPadding(Ui.dp(context, 16), 0, 0, 0)
            })
        }
        val progress = Ui.dialog(this).setView(box).setCancelable(false).show()
        val app = applicationContext
        Thread {
            val result: Any = try {
                val st = Statement.parse(bytes, password)
                Importer.save(app, st, name)
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ImportResult -> showImportResult(result)
                    is StatementException.WrongPassword -> askPassword(bytes, name, result.message)
                    is StatementException -> Ui.info(this, "Tidak bisa di-import", result.message ?: "File tidak dikenali.")
                    is Exception -> Ui.info(this, "Tidak bisa di-import", "Terjadi kesalahan: " + (result.message ?: result.javaClass.simpleName))
                }
            }
        }.start()
    }

    private fun showImportResult(r: ImportResult) {
        val st = r.statement
        month = r.lastMonth
        val msg = StringBuilder()
        msg.append(Importer.BANK)
        if (st.accountMasked.isNotEmpty()) msg.append(" ").append(st.accountMasked)
        if (st.period.isNotEmpty()) msg.append("\nPeriode ").append(st.period)
        msg.append("\n\n").append(st.txs.size).append(" transaksi dibaca — ").append(r.added).append(" baru")
        if (r.dup > 0) msg.append(", ").append(r.dup).append(" sudah pernah di-import")
        msg.append(".\n")
        msg.append(if (st.totalsMatch()) "✓ Jumlah masuk & keluar cocok dengan ringkasan e-statement.\n"
        else "⚠ Jumlah masuk/keluar berbeda dari ringkasan e-statement — periksa lagi file-nya.\n")
        val breaks = st.balanceBreaks()
        msg.append(if (breaks == 0) "✓ Saldo tiap baris nyambung.\n" else "⚠ $breaks baris saldonya tidak nyambung.\n")
        st.closingBalance?.let { msg.append("Saldo akhir: ").append(Fmt.rp(it)).append("\n") }
        if (r.pendingNames > 0) {
            msg.append("\n").append(r.pendingNames)
                .append(" nama belum dikenal. Pilih kategorinya sekali, berikutnya otomatis.")
        }
        val b = Ui.dialog(this).setTitle("Import selesai").setMessage(msg.toString())
        if (r.pendingNames > 0) {
            b.setPositiveButton("Kategorikan sekarang") { _, _ -> startActivity(Intent(this, ReviewActivity::class.java)) }
            b.setNegativeButton("Nanti") { _, _ -> openTab(TAB_SUMMARY) }
        } else {
            b.setPositiveButton("Lihat ringkasan") { _, _ -> openTab(TAB_SUMMARY) }
        }
        b.show()
        refreshCurrent()
    }
}

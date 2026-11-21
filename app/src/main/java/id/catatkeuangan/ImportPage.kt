package id.catatkeuangan

import android.content.Intent
import android.view.View
import android.widget.ScrollView
import java.time.Instant
import java.time.ZoneId

class ImportPage(private val main: MainActivity) : Page {
    private val content = Ui.vbox(main, 12)
    override val view: View = ScrollView(main).apply {
        isFillViewport = true
        addView(content)
    }

    override fun refresh() {
        val ctx = main
        content.removeAllViews()
        content.addView(Ui.title(ctx, "Import e-statement").apply { setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8)) })

        val c = Ui.vbox(ctx)
        c.addView(Ui.text(ctx, "E-statement Livin' by Mandiri", 16f, Ui.TEXT, true))
        c.addView(Ui.text(ctx,
            "Unduh e-statement format Excel (.xlsx) dari Livin', lalu pilih file-nya di sini. " +
                "Bisa juga lewat \"Bagikan\" / \"Buka dengan\" dari aplikasi lain.", 14f, Ui.SUB).apply {
            setPadding(0, Ui.dp(ctx, 4), 0, 0)
        })
        c.addView(Ui.filled(ctx, "Pilih file e-statement") { main.chooseStatement() },
            Ui.matchWrap().apply { topMargin = Ui.dp(ctx, 12) })
        c.addView(Ui.text(ctx,
            "• Password file hanya dipakai untuk membukanya dan tidak disimpan.\n" +
                "• Import bulan yang sama dua kali tidak membuat transaksi dobel.\n" +
                "• Transfer ke/dari rekening atas nama sendiri dicatat sebagai pindah dana.\n" +
                "• Bayar QR ke toko sendiri yang kembali sebagai pencairan tidak dihitung.",
            13f, Ui.SUB).apply { setPadding(0, Ui.dp(ctx, 10), 0, 0) })
        content.addView(Ui.card(ctx, c))

        val pending = main.db.pendingGroups()
        if (pending.isNotEmpty()) {
            val p = Ui.vbox(ctx)
            p.addView(Ui.text(ctx, "${pending.size} nama belum dikenal", 15f, Ui.ORANGE, true))
            p.addView(Ui.filled(ctx, "Pilih kategori") { ctx.startActivity(Intent(ctx, ReviewActivity::class.java)) },
                Ui.matchWrap().apply { topMargin = Ui.dp(ctx, 8) })
            content.addView(Ui.card(ctx, p, 14, Ui.WARN_BG))
        }

        val logs = main.db.imports()
        content.addView(Ui.section(ctx, "Riwayat import"))
        if (logs.isEmpty()) {
            content.addView(Ui.text(ctx, "Belum pernah import.", 13f, Ui.GRAY).apply { setPadding(Ui.dp(ctx, 4), 0, 0, 0) })
            return
        }
        val box = Ui.vbox(ctx)
        logs.forEachIndexed { i, l ->
            if (i > 0) box.addView(Ui.divider(ctx))
            val r = Ui.vbox(ctx)
            r.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
            r.addView(Ui.text(ctx, l.account + if (l.period.isNotEmpty()) " · " + l.period else "", 14f, Ui.TEXT, true))
            val at = Instant.ofEpochMilli(l.at).atZone(ZoneId.systemDefault()).toLocalDateTime()
            r.addView(Ui.text(ctx,
                "${l.total} transaksi · ${l.added} baru" + (if (l.dup > 0) " · ${l.dup} sudah ada" else "") +
                    " · ${Fmt.dateTime(Fmt.toTs(at.withNano(0)))}", 12f, Ui.SUB))
            box.addView(r)
        }
        content.addView(Ui.card(ctx, box))
    }
}

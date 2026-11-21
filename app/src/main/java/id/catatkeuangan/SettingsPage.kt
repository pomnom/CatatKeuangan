package id.catatkeuangan

import android.content.Intent
import android.view.View
import android.widget.ScrollView

class SettingsPage(private val main: MainActivity) : Page {
    private val content = Ui.vbox(main, 12)
    override val view: View = ScrollView(main).apply {
        isFillViewport = true
        addView(content)
    }

    override fun refresh() {
        val ctx = main
        content.removeAllViews()
        content.addView(Ui.title(ctx, "Pengaturan").apply { setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8)) })

        val acc = Ui.vbox(ctx)
        acc.addView(Ui.text(ctx, "Rekening & toko", 16f, Ui.TEXT, true))
        val owner = Prefs.owner(ctx)
        acc.addView(Ui.text(ctx, "Pemilik rekening: " + owner.ifEmpty { "terisi otomatis saat import" }, 14f, Ui.SUB))
        val stores = Prefs.stores(ctx)
        acc.addView(Ui.text(ctx, "Toko QRIS: " + if (stores.isEmpty()) "terisi otomatis saat import" else stores.sorted().joinToString(", "),
            14f, Ui.SUB))
        acc.addView(Ui.text(ctx,
            "Transfer ke/dari nama pemilik rekening dianggap pindah dana. Pencairan QRIS toko masuk buku Usaha.",
            12f, Ui.GRAY).apply { setPadding(0, Ui.dp(ctx, 6), 0, 0) })
        content.addView(Ui.card(ctx, acc))

        val cat = Ui.vbox(ctx)
        cat.addView(Ui.text(ctx, "Kategori & nama", 16f, Ui.TEXT, true))
        cat.addView(Ui.outlined(ctx, "Kelola kategori") {
            ctx.startActivity(Intent(ctx, CategoriesActivity::class.java))
        }, Ui.matchWrap().apply { topMargin = Ui.dp(ctx, 8) })
        val rules = main.db.rules().size
        cat.addView(Ui.outlined(ctx, "Nama yang diingat ($rules)") {
            ctx.startActivity(Intent(ctx, RulesActivity::class.java))
        }, Ui.matchWrap())
        val pending = main.db.pendingGroups().size
        if (pending > 0) {
            cat.addView(Ui.outlined(ctx, "Nama belum dikenal ($pending)") {
                ctx.startActivity(Intent(ctx, ReviewActivity::class.java))
            }, Ui.matchWrap())
        }
        content.addView(Ui.card(ctx, cat))

        val bk = Ui.vbox(ctx)
        bk.addView(Ui.text(ctx, "Cadangan data", 16f, Ui.TEXT, true))
        bk.addView(Ui.text(ctx,
            "Data hanya tersimpan di HP ini. Simpan cadangan (.json) sebelum ganti HP atau memasang ulang app.",
            13f, Ui.SUB))
        bk.addView(Ui.filled(ctx, "Simpan cadangan") { main.chooseBackupTarget() },
            Ui.matchWrap().apply { topMargin = Ui.dp(ctx, 8) })
        bk.addView(Ui.outlined(ctx, "Pulihkan dari cadangan") { main.chooseBackupSource() }, Ui.matchWrap())
        content.addView(Ui.card(ctx, bk))

        val about = Ui.vbox(ctx)
        about.addView(Ui.text(ctx, "Tentang", 16f, Ui.TEXT, true))
        val version = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        about.addView(Ui.text(ctx, "Catat Keuangan $version", 14f, Ui.SUB))
        about.addView(Ui.text(ctx, "App tidak memakai internet. Semua data keuangan tetap di HP.", 13f, Ui.SUB))
        content.addView(Ui.card(ctx, about))
    }
}

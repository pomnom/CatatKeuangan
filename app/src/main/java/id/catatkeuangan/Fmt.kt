package id.catatkeuangan

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** Format rupiah & tanggal. Semua waktu disimpan "apa adanya" (jam dinding) sebagai epoch UTC. */
object Fmt {
    private val MONTHS = arrayOf(
        "Januari", "Februari", "Maret", "April", "Mei", "Juni",
        "Juli", "Agustus", "September", "Oktober", "November", "Desember"
    )
    private val MONTHS_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
    private val DAYS = arrayOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")

    private fun group(v: Long): String {
        val s = v.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
            sb.append(s[i])
        }
        return sb.toString()
    }

    /** 123456789 sen → "Rp1.234.567,89"; tanpa sen bila bulat. */
    fun rp(sen: Long, signed: Boolean = false): String {
        val neg = sen < 0
        val abs = kotlin.math.abs(sen)
        val whole = abs / 100
        val cents = abs % 100
        val body = "Rp" + group(whole) + if (cents != 0L) "," + cents.toString().padStart(2, '0') else ""
        return when {
            neg -> "−$body"
            signed && sen > 0 -> "+$body"
            else -> body
        }
    }

    /** Ringkas: "Rp1,2 jt", "Rp350 rb". */
    fun rpShort(sen: Long): String {
        val r = kotlin.math.abs(sen) / 100.0
        val sign = if (sen < 0) "−" else ""
        return sign + when {
            r >= 1_000_000_000 -> "Rp" + one(r / 1_000_000_000) + " M"
            r >= 1_000_000 -> "Rp" + one(r / 1_000_000) + " jt"
            r >= 1_000 -> "Rp" + one(r / 1_000) + " rb"
            else -> "Rp" + r.toLong()
        }
    }

    private fun one(v: Double): String {
        val t = BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return t.replace('.', ',')
    }

    /** "150000", "150.000", "150.000,50", "1,5" → sen. */
    fun parseAmount(text: String): Long? {
        var t = text.replace("Rp", "").replace(" ", "").trim()
        if (t.isEmpty()) return null
        t = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t.replace(".", "")
        return try {
            val v = BigDecimal(t).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
            if (v > 0) v else null
        } catch (e: Exception) {
            null
        }
    }

    /** Untuk kolom isian: 1500000 sen → "15.000". */
    fun amountInput(sen: Long): String {
        val whole = sen / 100
        val cents = sen % 100
        return group(whole) + if (cents != 0L) "," + cents.toString().padStart(2, '0') else ""
    }

    fun toLocal(ts: Long): LocalDateTime = LocalDateTime.ofEpochSecond(Math.floorDiv(ts, 1000L), 0, ZoneOffset.UTC)

    fun toTs(dt: LocalDateTime): Long = dt.toEpochSecond(ZoneOffset.UTC) * 1000L

    fun monthStart(ym: YearMonth): Long = toTs(ym.atDay(1).atStartOfDay())

    fun monthEnd(ym: YearMonth): Long = toTs(ym.plusMonths(1).atDay(1).atStartOfDay())

    fun monthTitle(ym: YearMonth): String = "${MONTHS[ym.monthValue - 1]} ${ym.year}"

    fun dayTitle(d: LocalDate): String = "${DAYS[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]} ${d.year}"

    fun date(ts: Long): String {
        val d = toLocal(ts)
        return "${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]} ${d.year}"
    }

    fun time(ts: Long): String {
        val d = toLocal(ts)
        return "%02d:%02d".format(d.hour, d.minute)
    }

    fun dateTime(ts: Long): String = date(ts) + ", " + time(ts)

    /** Waktu sekarang menurut jam HP, disimpan sebagai jam dinding. */
    fun nowTs(): Long = toTs(LocalDateTime.now().withNano(0))
}

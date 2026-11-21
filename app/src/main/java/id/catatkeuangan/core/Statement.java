package id.catatkeuangan.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Isi e-statement Livin' by Mandiri (.xlsx). Semua nominal dalam sen (Rp1 = 100).
 * Waktu disimpan "apa adanya" seperti tertulis di e-statement (dibaca sebagai UTC agar tidak bergeser zona).
 */
public final class Statement {
    public String bank = "Mandiri";
    public String owner = "";
    public String accountMasked = "";
    public String accountLast4 = "";
    public String period = "";
    public Long openingBalance;
    public Long totalIn;
    public Long totalOut;
    public Long closingBalance;
    public final List<RawTx> txs = new ArrayList<>();

    /** Satu baris transaksi dari e-statement. */
    public static final class RawTx {
        public long when;
        public String date;
        public String time;
        public String[] lines;
        public long in;
        public long out;
        public long balance;

        public String remarks() {
            return String.join("\n", lines);
        }

        /** Kunci untuk mencegah transaksi yang sama ter-import dua kali. */
        public String key(String account) {
            return account + "|" + when + "|" + in + "|" + out + "|" + balance;
        }
    }

    // ---- hasil pemeriksaan ----

    public long sumIn() {
        long s = 0;
        for (RawTx t : txs) s += t.in;
        return s;
    }

    public long sumOut() {
        long s = 0;
        for (RawTx t : txs) s += t.out;
        return s;
    }

    /** true bila jumlah masuk/keluar sama dengan ringkasan di bagian atas e-statement. */
    public boolean totalsMatch() {
        return totalIn != null && totalOut != null && totalIn == sumIn() && totalOut == sumOut();
    }

    /** Jumlah baris yang saldonya tidak nyambung dengan baris sebelumnya. */
    public int balanceBreaks() {
        if (openingBalance == null) return 0;
        long prev = openingBalance;
        int bad = 0;
        for (RawTx t : txs) {
            if (prev + t.in - t.out != t.balance) bad++;
            prev = t.balance;
        }
        return bad;
    }

    // ---- pembacaan ----

    private static final Pattern DATE = Pattern.compile("^\\s*(\\d{1,2})[ \\-/]([A-Za-z]{3,9})[ \\-/](\\d{4})\\s*$");
    private static final Pattern TIME = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?.*$");

    public static Statement parse(byte[] file, String password) throws StatementException {
        byte[] xlsx;
        if (OfficeCrypto.isEncrypted(file)) xlsx = OfficeCrypto.decrypt(file, password);
        else if (XlsxSheet.isZip(file)) xlsx = file;
        else throw new StatementException("Format file tidak dikenali. Pilih e-statement Excel (.xlsx) dari Livin'.");
        return fromSheet(XlsxSheet.read(xlsx));
    }

    static Statement fromSheet(XlsxSheet sh) throws StatementException {
        Statement st = new Statement();
        st.owner = clean(labelValue(sh, "nama/name", "nama"));
        st.period = clean(labelValue(sh, "periode/period", "periode"));
        String acct = clean(labelValue(sh, "nomor rekening", "account number"));
        String digits = acct == null ? "" : acct.replaceAll("\\D", "");
        if (digits.length() >= 4) {
            st.accountLast4 = digits.substring(digits.length() - 4);
            st.accountMasked = "•••• " + st.accountLast4;
        }
        st.openingBalance = money(labelValue(sh, "saldo awal", "initial balance"));
        st.totalIn = money(labelValue(sh, "dana masuk/", "incoming transactions"));
        st.totalOut = money(labelValue(sh, "dana keluar/", "outgoing transactions"));
        st.closingBalance = money(labelValue(sh, "saldo akhir", "closing balance"));

        // Baris judul tabel: "Tanggal", "Keterangan", "Dana Masuk (IDR)", "Dana Keluar (IDR)", "Saldo (IDR)".
        int headerRow = -1, cDate = -1, cDesc = -1, cIn = -1, cOut = -1, cBal = -1;
        for (Map.Entry<Integer, java.util.TreeMap<Integer, String>> r : sh.rows.entrySet()) {
            int d = -1, k = -1, i = -1, o = -1, b = -1;
            for (Map.Entry<Integer, String> c : r.getValue().entrySet()) {
                String v = c.getValue().trim().toLowerCase(Locale.ROOT);
                if (v.equals("tanggal") || v.equals("date")) d = c.getKey();
                else if (v.equals("keterangan") || v.equals("remarks")) k = c.getKey();
                else if (v.startsWith("dana masuk") || v.startsWith("incoming")) i = c.getKey();
                else if (v.startsWith("dana keluar") || v.startsWith("outgoing")) o = c.getKey();
                else if (v.startsWith("saldo") || v.startsWith("balance")) b = c.getKey();
            }
            if (d > 0 && k > 0 && i > 0 && o > 0) {
                headerRow = r.getKey();
                cDate = d;
                cDesc = k;
                cIn = i;
                cOut = o;
                cBal = b;
                break;
            }
        }
        if (headerRow < 0) {
            throw new StatementException("Tabel transaksi tidak ditemukan. Pastikan ini e-statement Livin' by Mandiri.");
        }

        RawTx cur = null;
        for (Map.Entry<Integer, java.util.TreeMap<Integer, String>> r : sh.rows.tailMap(headerRow, false).entrySet()) {
            String dv = r.getValue().get(cDate);
            if (dv == null) continue;
            Matcher dm = DATE.matcher(dv);
            if (dm.matches()) {
                int month = month(dm.group(2));
                if (month == 0) continue;
                cur = new RawTx();
                cur.date = dv.trim();
                cur.time = "";
                String desc = r.getValue().get(cDesc);
                cur.lines = splitLines(desc);
                Long in = money(r.getValue().get(cIn));
                Long out = money(r.getValue().get(cOut));
                Long bal = cBal > 0 ? money(r.getValue().get(cBal)) : null;
                cur.in = in == null ? 0 : in;
                cur.out = out == null ? 0 : out;
                cur.balance = bal == null ? 0 : bal;
                cur.when = LocalDateTime.of(Integer.parseInt(dm.group(3)), month, Integer.parseInt(dm.group(1)), 0, 0)
                        .toEpochSecond(ZoneOffset.UTC) * 1000L;
                st.txs.add(cur);
                continue;
            }
            Matcher tm = TIME.matcher(dv);
            if (tm.matches() && cur != null && cur.time.isEmpty()) {
                cur.time = dv.trim();
                int h = Integer.parseInt(tm.group(1));
                int m = Integer.parseInt(tm.group(2));
                int s = tm.group(3) == null ? 0 : Integer.parseInt(tm.group(3));
                cur.when += ((h * 60L + m) * 60L + s) * 1000L;
                cur = null;
            }
        }
        if (st.txs.isEmpty()) throw new StatementException("Tidak ada transaksi di e-statement ini.");
        return st;
    }

    private static String[] splitLines(String s) {
        if (s == null) return new String[]{""};
        List<String> out = new ArrayList<>();
        for (String l : s.replace("\r", "").split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) out.add("");
        return out.toArray(new String[0]);
    }

    /** Teks sel pertama yang bukan ":" di kanan label (baris yang sama). */
    private static String labelValue(XlsxSheet sh, String... prefixes) {
        for (Map.Entry<Integer, java.util.TreeMap<Integer, String>> r : sh.rows.entrySet()) {
            boolean found = false;
            for (Map.Entry<Integer, String> c : r.getValue().entrySet()) {
                String v = c.getValue().trim();
                if (found) {
                    if (v.replace(":", "").trim().isEmpty()) continue;
                    return v.startsWith(":") ? v.substring(1).trim() : v;
                }
                String lv = v.toLowerCase(Locale.ROOT);
                for (String p : prefixes) {
                    if (lv.startsWith(p)) {
                        found = true;
                        break;
                    }
                }
            }
        }
        return null;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static int month(String m) {
        String t = m.toLowerCase(Locale.ROOT);
        if (t.length() > 3) t = t.substring(0, 3);
        switch (t) {
            case "jan": return 1;
            case "feb": return 2;
            case "mar": return 3;
            case "apr": return 4;
            case "may": case "mei": return 5;
            case "jun": return 6;
            case "jul": return 7;
            case "aug": case "agu": case "agt": return 8;
            case "sep": return 9;
            case "oct": case "okt": return 10;
            case "nov": return 11;
            case "dec": case "des": return 12;
            default: return 0;
        }
    }

    /** "13.186.446,45" atau "200000.0" → sen. null bila kosong / bukan angka. */
    public static Long money(String s) {
        if (s == null) return null;
        String t = s.replace("Rp", "").replace("IDR", "").replace("\u00a0", "").replace(" ", "").trim();
        if (t.isEmpty() || t.equals("-")) return null;
        boolean neg = t.startsWith("-") || (t.startsWith("(") && t.endsWith(")"));
        t = t.replace("-", "").replace("(", "").replace(")", "");
        if (t.contains(",")) {
            t = t.replace(".", "").replace(",", ".");
        } else {
            int dots = t.length() - t.replace(".", "").length();
            if (dots > 1 || (dots == 1 && t.length() - t.indexOf('.') - 1 == 3)) t = t.replace(".", "");
        }
        try {
            long v = new BigDecimal(t).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return neg ? -v : v;
        } catch (Exception e) {
            return null;
        }
    }
}

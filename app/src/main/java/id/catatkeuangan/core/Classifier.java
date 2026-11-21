package id.catatkeuangan.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mengelompokkan transaksi e-statement Mandiri: jenis, buku (usaha/pribadi), lawan transaksi, dan
 * kategori bawaan. Kategori bawaan memakai kunci tetap (lihat {@link Cat}); bila null, app akan
 * menanyakan kategori untuk nama itu sekali lalu mengingatnya.
 */
public final class Classifier {
    public static final int KIND_NORMAL = 0;
    /** Pindah dana antar-rekening sendiri — tidak dihitung pemasukan/pengeluaran. */
    public static final int KIND_INTERNAL = 1;
    /** Bayar QR ke toko sendiri yang langsung kembali sebagai pencairan — saling meniadakan. */
    public static final int KIND_SELF_STORE = 2;

    public static final int BOOK_PRIBADI = 0;
    public static final int BOOK_USAHA = 1;

    /** Kunci kategori bawaan. */
    public static final class Cat {
        public static final String IN_SALES = "in_sales";
        public static final String IN_INTEREST = "in_interest";
        public static final String OUT_FOOD = "out_food";
        public static final String OUT_DAILY = "out_daily";
        public static final String OUT_ONLINE = "out_online";
        public static final String OUT_PHONE = "out_phone";
        public static final String OUT_BILLS = "out_bills";
        public static final String OUT_EWALLET = "out_ewallet";
        public static final String OUT_APPS = "out_apps";
        public static final String OUT_BANK = "out_bank";
        public static final String OUT_CASH = "out_cash";

        private Cat() {}
    }

    public static final class Result {
        public Statement.RawTx raw;
        public int kind = KIND_NORMAL;
        public int book = BOOK_PRIBADI;
        /** Kunci kategori bawaan, atau null bila perlu ditanyakan. */
        public String catKey;
        /** Nama lawan transaksi yang dinormalisasi — dasar "tanya sekali lalu ingat". */
        public String partyKey = "";
        /** Nama untuk ditampilkan. */
        public String party = "";
        /** Jenis transaksi singkat, mis. "Transfer BI Fast · BCA". */
        public String type = "";
        /** Berita/catatan yang ikut di keterangan transfer. */
        public String note = "";

        public boolean isIn() {
            return raw.in > 0;
        }

        public long amount() {
            return raw.in > 0 ? raw.in : raw.out;
        }
    }

    private static final String[] BANKS = {
            "BANK SEABANK INDONESIA", "SEABANK INDONESIA", "SEABANK", "BANK NTB SYARIAH", "BANK SYARIAH INDONESIA",
            "BANK CENTRAL ASIA", "BANK RAKYAT INDONESIA", "BANK NEGARA INDONESIA", "BANK MANDIRI", "BANK BCA",
            "BANK BRI", "BANK BNI", "BANK BSI", "BANK BTN", "BANK JAGO", "BANK CIMB NIAGA", "CIMB NIAGA",
            "BANK PERMATA", "PERMATA", "BANK DANAMON", "DANAMON", "BANK MEGA", "BANK MUAMALAT", "BANK BTPN",
            "BANK DKI", "BANK JATIM", "BANK BJB", "BANK BPD BALI", "BANK NTT", "BANK SULSELBAR", "ALLO BANK",
            "BCA DIGITAL", "BLU BY BCA DIGITAL", "BANK NEO COMMERCE", "SUPERBANK", "KROM", "OCBC", "MAYBANK",
            "PANIN", "JENIUS", "BCA", "BRI", "BNI", "BSI", "BTN", "JAGO",
    };

    static {
        Arrays.sort(BANKS, Comparator.comparingInt(String::length).reversed());
    }

    private Classifier() {}

    /** "M. Budi  Santoso" → "M BUDI SANTOSO". */
    public static String norm(String s) {
        if (s == null) return "";
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    /**
     * @param txs         transaksi dalam urutan e-statement
     * @param owner       nama pemilik rekening (dari kepala e-statement)
     * @param knownStores nama toko QRIS yang sudah dikenal dari import sebelumnya (boleh kosong)
     */
    public static List<Result> classify(List<Statement.RawTx> txs, String owner, Collection<String> knownStores) {
        String ownerN = norm(owner);
        Set<String> stores = new HashSet<>();
        if (knownStores != null) for (String s : knownStores) stores.add(norm(s));
        for (Statement.RawTx t : txs) {
            if (up(line(t, 0)).startsWith("PENCAIRAN RT") && !line(t, 2).isEmpty()) stores.add(norm(line(t, 2)));
        }

        List<Result> out = new ArrayList<>();
        for (Statement.RawTx t : txs) out.add(one(t, ownerN, stores));

        // Bayar QR ke toko sendiri + pencairan RT dengan nominal sama (≤ 2 detik) → saling meniadakan.
        boolean[] used = new boolean[out.size()];
        for (int i = 0; i < out.size(); i++) {
            Result q = out.get(i);
            if (used[i] || q.isIn() || !"Bayar QR".equals(q.type) || !stores.contains(q.partyKey)) continue;
            for (int j = 0; j < out.size(); j++) {
                Result r = out.get(j);
                if (used[j] || j == i || !Cat.IN_SALES.equals(r.catKey) || r.raw.in != q.raw.out) continue;
                if (Math.abs(r.raw.when - q.raw.when) > 2000) continue;
                used[i] = used[j] = true;
                for (Result x : new Result[]{q, r}) {
                    x.kind = KIND_SELF_STORE;
                    x.catKey = null;
                    x.book = BOOK_USAHA;
                }
                q.note = "Dibayar ke toko sendiri, kembali sebagai pencairan QRIS";
                r.note = "Pasangan bayar QR ke toko sendiri";
                break;
            }
        }
        return out;
    }

    private static Result one(Statement.RawTx t, String ownerN, Set<String> stores) {
        Result r = new Result();
        r.raw = t;
        String l0 = line(t, 0);
        String u0 = up(l0);
        boolean in = t.in > 0;

        if (u0.startsWith("PENCAIRAN RT")) {
            String store = line(t, 2).isEmpty() ? "QRIS" : line(t, 2);
            set(r, "Penjualan QRIS", store);
            r.book = BOOK_USAHA;
            r.catKey = Cat.IN_SALES;
            return r;
        }
        if (u0.startsWith("BIAYA") || u0.startsWith("PAJAK")) {
            set(r, l0, "Biaya bank");
            r.partyKey = "BIAYA BANK";
            r.note = line(t, 1);
            r.catKey = Cat.OUT_BANK;
            return r;
        }
        if (u0.startsWith("BUNGA")) {
            set(r, l0, "Bunga bank");
            r.catKey = in ? Cat.IN_INTEREST : Cat.OUT_BANK;
            return r;
        }
        if (u0.startsWith("PEMBAYARAN QR") || u0.startsWith("PEMBELIAN QR")) {
            String m = stripPrefix(line(t, 1), "ke ", "dari ");
            if (m.isEmpty()) m = "QR";
            set(r, "Bayar QR", m);
            r.catKey = in ? null : merchantCat(m);
            return r;
        }
        boolean payLike = u0.startsWith("PEMBAYARAN ") || u0.startsWith("PEMBELIAN ") || u0.startsWith("TOP UP")
                || u0.startsWith("TOPUP") || u0.startsWith("TOP-UP") || u0.startsWith("ISI ULANG");
        String wallet = payLike ? walletName(u0) : null;
        if (wallet != null) {
            set(r, "Top up e-wallet", wallet);
            r.catKey = in ? null : Cat.OUT_EWALLET;
            return r;
        }
        if (payLike && u0.contains("PLN")) {
            set(r, l0, "PLN");
            r.catKey = Cat.OUT_BILLS;
            return r;
        }
        if (u0.contains("PULSA") || u0.contains("PAKET DATA")) {
            set(r, l0, stripDigits(l0));
            r.catKey = Cat.OUT_PHONE;
            return r;
        }
        if (u0.contains("TARIK TUNAI")) {
            set(r, "Tarik tunai", "Tarik tunai");
            r.catKey = Cat.OUT_CASH;
            return r;
        }

        // Transfer: "Transfer BI Fast / Dari BRI / NAMA NOREK / berita"
        //           "Transfer dari BANK MANDIRI / NAMA NOREK / berita"
        //           "Transfer ke Bank lain / BANK NAMA NOREK"
        if (u0.startsWith("TRANSFER")) {
            String bank;
            String nameLine;
            String note;
            String rest = u0.replaceFirst("^TRANSFER\\s*", "");
            if (rest.startsWith("DARI ") || rest.startsWith("KE ")) {
                bank = l0.substring(l0.length() - rest.length()).replaceFirst("(?i)^(dari|ke)\\s+", "");
                nameLine = line(t, 1);
                note = line(t, 2);
            } else {
                bank = stripPrefix(line(t, 1), "dari ", "ke ");
                nameLine = line(t, 2);
                note = line(t, 3);
                if (nameLine.isEmpty()) {
                    nameLine = line(t, 1);
                    bank = "";
                }
            }
            String name = stripDigits(nameLine);
            if (up(bank).contains("BANK LAIN") || bank.isEmpty()) {
                String[] split = splitBank(name);
                if (split[0] != null) {
                    bank = split[0];
                    name = split[1];
                }
            }
            if (bank.equalsIgnoreCase("Bank lain")) bank = "";
            String method = rest.startsWith("DARI ") || rest.startsWith("KE ") ? "Transfer" : "Transfer " + titleType(l0);
            r.type = method + (bank.isEmpty() ? "" : " · " + prettyBank(bank));
            r.note = note.equals("-") ? "" : note;
            if (name.isEmpty()) name = l0;
            if (isOwner(norm(name), ownerN)) {
                r.kind = KIND_INTERNAL;
                r.party = (in ? "Dari" : "Ke") + " rekening sendiri" + (bank.isEmpty() ? "" : " " + prettyBank(bank));
                r.partyKey = "SENDIRI " + norm(bank);
                return r;
            }
            r.party = name;
            r.partyKey = norm(name);
            return r;
        }

        if (u0.startsWith("PEMBAYARAN ") || u0.startsWith("PEMBELIAN ")) {
            String m = stripDigits(l0.substring(l0.indexOf(' ') + 1));
            set(r, l0.substring(0, l0.indexOf(' ')), m);
            r.catKey = in ? null : merchantCat(m);
            return r;
        }
        String p = stripDigits(l0);
        set(r, p, p.isEmpty() ? "Lainnya" : p);
        return r;
    }

    private static String walletName(String u0) {
        String w = " " + u0.replaceAll("[^A-Z0-9]+", " ") + " ";
        if (w.contains("GOPAY")) return "GoPay";
        if (w.contains("SHOPEEPAY")) return "ShopeePay";
        if (w.contains(" OVO ")) return "OVO";
        if (w.contains("LINKAJA")) return "LinkAja";
        if (w.contains(" E MONEY ") || w.contains(" EMONEY ")) return "e-Money";
        if (w.contains(" DANA ")) return "DANA";
        return null;
    }

    private static void set(Result r, String type, String party) {
        r.type = type;
        r.party = party;
        r.partyKey = norm(party);
    }

    private static boolean isOwner(String nameN, String ownerN) {
        if (ownerN.isEmpty() || nameN.isEmpty()) return false;
        if (nameN.equals(ownerN)) return true;
        // BCA memotong nama penerima sampai ±20 huruf.
        return nameN.length() >= 12 && (ownerN.startsWith(nameN) || nameN.startsWith(ownerN));
    }

    /** [bank, nama] bila awal teks adalah nama bank yang dikenal, selain itu [null, teks]. */
    private static String[] splitBank(String s) {
        String u = up(s);
        for (String b : BANKS) {
            if (u.startsWith(b + " ") && u.length() > b.length() + 1) {
                return new String[]{s.substring(0, b.length()), s.substring(b.length() + 1).trim()};
            }
        }
        return new String[]{null, s};
    }

    private static String prettyBank(String b) {
        String u = up(b).trim();
        if (u.equals("BANK MANDIRI")) return "Mandiri";
        if (u.startsWith("SEABANK")) return "SeaBank";
        if (u.length() <= 4) return u;
        StringBuilder sb = new StringBuilder();
        for (String w : b.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.length() <= 3 && !w.equals("bank") ? w.toUpperCase(Locale.ROOT)
                    : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return sb.toString();
    }

    private static String titleType(String l0) {
        return l0.replaceFirst("(?i)^transfer\\s*", "").trim();
    }

    /** Kategori bawaan untuk merchant QR yang umum; null bila belum dikenal. */
    public static String merchantCat(String merchant) {
        String m = " " + norm(merchant) + " ";
        if (has(m, "SHOPEE", "TOKOPEDIA", "LAZADA", "TIKTOK", "BUKALAPAK", "BLIBLI")) return Cat.OUT_ONLINE;
        if (has(m, "GOOGLE", "CODA", "UNIPIN", "APPLE", "STEAM", "NETFLIX", "SPOTIFY", "YOUTUBE", "VIDIO"))
            return Cat.OUT_APPS;
        if (has(m, " XL ", " AXIS ", "TELKOMSEL", "INDOSAT", " IM3 ", "SMARTFREN", " TRI ", " BY U "))
            return Cat.OUT_PHONE;
        if (has(m, " IDM ", "INDOMARET", "ALFAMART", "ALFAMIDI", "SUPERINDO", "HYPERMART", "LOTTE MART"))
            return Cat.OUT_DAILY;
        if (has(m, " PLN ", "PDAM", "BPJS", "INDIHOME", "TELKOM ")) return Cat.OUT_BILLS;
        if (has(m, "COFFEE", "COFFE ", "KOPI", " CAFE", " KAFE", "RESTO", "WARUNG", "BAKSO", " MIE ", "AYAM ",
                " KFC ", "MCDONALD", "STARBUCKS", " JCO ", "CHATIME", "MIXUE", "RUMAH MAKAN"))
            return Cat.OUT_FOOD;
        return null;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private static String line(Statement.RawTx t, int i) {
        return t.lines != null && i < t.lines.length ? t.lines[i].trim() : "";
    }

    private static String up(String s) {
        return s.toUpperCase(Locale.ROOT).trim();
    }

    private static String stripPrefix(String s, String... prefixes) {
        String t = s.trim();
        for (String p : prefixes) {
            if (t.regionMatches(true, 0, p, 0, p.length())) return t.substring(p.length()).trim();
        }
        return t;
    }

    /** Buang nomor rekening/referensi di ujung: "EKO DWI 9019214" → "EKO DWI". */
    static String stripDigits(String s) {
        String t = s.trim();
        while (true) {
            String n = t.replaceFirst("\\s+[-#]?[0-9][0-9\\-]*$", "").trim();
            if (n.equals(t)) break;
            t = n;
        }
        if (t.matches("[0-9\\-\\s]+")) return "";
        return t;
    }
}

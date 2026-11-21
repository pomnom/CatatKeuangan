# Catat Keuangan

App Android untuk mencatat pemasukan & pengeluaran dari **e-statement Livin' by Mandiri** (.xlsx berpassword),
ditambah catatan tunai manual. Semua data tetap di HP; app tidak memakai internet.

## Fitur

- **Import e-statement** — file Excel berpassword dibuka langsung di HP (enkripsi Agile ECMA-376).
  Jumlah masuk/keluar dicocokkan dengan ringkasan e-statement dan saldo tiap baris diperiksa.
  Import bulan yang sama dua kali tidak membuat transaksi dobel.
- **Dua buku: Usaha & Pribadi** — pencairan QRIS toko otomatis masuk buku Usaha.
- **Tidak dihitung otomatis**
  - transfer ke/dari rekening atas nama sendiri (BCA, BRI, dll.) → *pindah dana*;
  - bayar QR ke toko sendiri yang langsung kembali sebagai pencairan → saling meniadakan.
- **Kategori otomatis** untuk biaya bank, belanja QR (Shopee, Google Play, Indomaret, pulsa, PLN), top up e-wallet.
  Nama baru (orang/merchant) **ditanyakan sekali**, lalu diingat untuk import berikutnya.
- **Ringkasan bulanan** per buku: pemasukan, pengeluaran, sisa, rincian per kategori, penjualan QRIS per hari.
- **Cadangan** seluruh data ke file .json dan pemulihannya.

## Kode

- `app/src/main/java/id/catatkeuangan/core/` — Java murni (bisa diuji di komputer tanpa Android):
  `Cfb` + `OfficeCrypto` (buka file berpassword), `MiniZip` + `XlsxSheet` (baca sheet), `Statement`
  (format e-statement Mandiri), `Classifier` (jenis, buku, kategori bawaan).
- `app/src/main/java/id/catatkeuangan/` — tampilan (Kotlin, View + Material 3) dan database SQLite.

## Build

Push ke `main` → GitHub Actions membuat APK di halaman **Releases**.

Supaya APK baru bisa dipasang di atas versi lama tanpa uninstall (data tetap ada), isi Secret
`CK_SIGNING_PASS` (Settings → Secrets and variables → Actions). Kunci tanda tangan tersimpan terenkripsi di
`signing/release.keystore.enc`. Tanpa Secret, APK memakai kunci debug sementara.

E-statement asli tidak pernah disimpan di repo ini.

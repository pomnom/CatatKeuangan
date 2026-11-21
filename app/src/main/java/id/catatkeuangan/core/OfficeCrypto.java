package id.catatkeuangan.core;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Membuka file Excel (.xlsx) yang dikunci password — enkripsi "Agile" ECMA-376 (MS-OFFCRYPTO 2.3.4.10),
 * yang dipakai e-statement Livin' by Mandiri.
 */
public final class OfficeCrypto {
    private static final byte[] BK_VERIFIER_INPUT = hex("fea7d2763b4b9e79");
    private static final byte[] BK_VERIFIER_VALUE = hex("d7aa0f6d3061344e");
    private static final byte[] BK_KEY = hex("146e0be7abacd0d6");
    private static final int SEGMENT = 4096;

    private OfficeCrypto() {}

    /** true bila isi file adalah wadah terenkripsi (perlu password). */
    public static boolean isEncrypted(byte[] file) {
        return Cfb.isCfb(file);
    }

    /** Mengembalikan isi .xlsx (zip) yang sudah dibuka. */
    public static byte[] decrypt(byte[] file, String password) throws StatementException {
        Cfb cfb = new Cfb(file);
        byte[] info = cfb.stream("EncryptionInfo");
        byte[] pkg = cfb.stream("EncryptedPackage");
        if (info.length < 8) throw new StatementException("Info enkripsi file tidak lengkap.");
        int major = (info[0] & 0xff) | ((info[1] & 0xff) << 8);
        int minor = (info[2] & 0xff) | ((info[3] & 0xff) << 8);
        if (major == 4 && minor == 4) return agile(info, pkg, password == null ? "" : password);
        throw new StatementException("Jenis kunci file ini belum didukung (versi " + major + "." + minor + ").");
    }

    private static final class Params {
        Map<String, String> keyData = new HashMap<>();
        Map<String, String> encryptor = new HashMap<>();
    }

    private static Params parseInfo(byte[] xml) throws StatementException {
        Params p = new Params();
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(true);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            SAXParser parser = f.newSAXParser();
            parser.parse(new ByteArrayInputStream(xml), new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes a) {
                    String n = localName != null && !localName.isEmpty() ? localName
                            : qName.substring(qName.indexOf(':') + 1);
                    Map<String, String> target = null;
                    if (n.equals("keyData")) target = p.keyData;
                    else if (n.equals("encryptedKey") && p.encryptor.isEmpty()) target = p.encryptor;
                    if (target == null) return;
                    for (int i = 0; i < a.getLength(); i++) {
                        String an = a.getLocalName(i);
                        if (an == null || an.isEmpty()) an = a.getQName(i);
                        target.put(an, a.getValue(i));
                    }
                }
            });
        } catch (Exception e) {
            throw new StatementException("Info enkripsi file tidak bisa dibaca.", e);
        }
        if (p.keyData.isEmpty() || p.encryptor.isEmpty()) {
            throw new StatementException("File ini tidak dikunci dengan password biasa.");
        }
        return p;
    }

    private static void trySet(SAXParserFactory f, String feature, boolean v) {
        try {
            f.setFeature(feature, v);
        } catch (Exception ignored) {
            // Sebagian parser (Android) tidak mengenal fitur ini; default-nya sudah aman.
        }
    }

    private static byte[] agile(byte[] info, byte[] pkg, String password) throws StatementException {
        Params p = parseInfo(Arrays.copyOfRange(info, 8, info.length));
        try {
            Map<String, String> ek = p.encryptor;
            MessageDigest md = digest(ek.get("hashAlgorithm"));
            byte[] salt = Base64.getDecoder().decode(ek.get("saltValue"));
            int spin = Integer.parseInt(ek.get("spinCount"));
            int keyBytes = Integer.parseInt(ek.get("keyBits")) / 8;
            int blockSize = Integer.parseInt(ek.get("blockSize"));
            int hashSize = Integer.parseInt(ek.get("hashSize"));

            md.update(salt);
            md.update(password.getBytes(StandardCharsets.UTF_16LE));
            byte[] h = md.digest();
            byte[] it = new byte[4];
            for (int i = 0; i < spin; i++) {
                it[0] = (byte) i;
                it[1] = (byte) (i >>> 8);
                it[2] = (byte) (i >>> 16);
                it[3] = (byte) (i >>> 24);
                md.update(it);
                md.update(h);
                h = md.digest();
            }
            byte[] iv = fit(salt, blockSize);

            byte[] verInput = aes(derive(md, h, BK_VERIFIER_INPUT, keyBytes), iv,
                    Base64.getDecoder().decode(ek.get("encryptedVerifierHashInput")));
            byte[] verValue = aes(derive(md, h, BK_VERIFIER_VALUE, keyBytes), iv,
                    Base64.getDecoder().decode(ek.get("encryptedVerifierHashValue")));
            byte[] calc = md.digest(Arrays.copyOf(verInput, salt.length));
            if (!MessageDigest.isEqual(Arrays.copyOf(calc, hashSize), Arrays.copyOf(verValue, hashSize))) {
                throw new StatementException.WrongPassword(password.isEmpty()
                        ? "File ini dikunci. Masukkan password e-statement."
                        : "Password salah. Coba lagi.");
            }
            byte[] secret = Arrays.copyOf(aes(derive(md, h, BK_KEY, keyBytes), iv,
                    Base64.getDecoder().decode(ek.get("encryptedKeyValue"))), keyBytes);

            Map<String, String> kd = p.keyData;
            MessageDigest pkgMd = digest(kd.get("hashAlgorithm"));
            byte[] kdSalt = Base64.getDecoder().decode(kd.get("saltValue"));
            int kdBlock = Integer.parseInt(kd.get("blockSize"));
            if (pkg.length < 8) throw new StatementException("Isi file terenkripsi kosong.");
            long size = 0;
            for (int i = 7; i >= 0; i--) size = (size << 8) | (pkg[i] & 0xff);
            if (size < 0 || size > pkg.length) throw new StatementException("Ukuran isi file tidak wajar.");
            byte[] out = new byte[(int) size];
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            SecretKeySpec ks = new SecretKeySpec(secret, "AES");
            int pos = 0;
            for (int seg = 0; pos < size; seg++) {
                int from = 8 + seg * SEGMENT;
                int len = Math.min(SEGMENT, pkg.length - from);
                len -= len % 16;
                if (len <= 0) break;
                pkgMd.update(kdSalt);
                pkgMd.update(new byte[]{(byte) seg, (byte) (seg >>> 8), (byte) (seg >>> 16), (byte) (seg >>> 24)});
                byte[] segIv = fit(pkgMd.digest(), kdBlock);
                c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(segIv));
                byte[] plain = c.doFinal(pkg, from, len);
                int n = (int) Math.min(plain.length, size - pos);
                System.arraycopy(plain, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (StatementException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementException("File tidak bisa dibuka: " + e.getMessage(), e);
        }
    }

    private static byte[] derive(MessageDigest md, byte[] h, byte[] blockKey, int keyBytes) {
        md.update(h);
        md.update(blockKey);
        return fit(md.digest(), keyBytes);
    }

    /** Potong, atau isi 0x36, sampai panjang {@code n} (aturan MS-OFFCRYPTO). */
    private static byte[] fit(byte[] b, int n) {
        byte[] r = Arrays.copyOf(b, n);
        for (int i = b.length; i < n; i++) r[i] = 0x36;
        return r;
    }

    private static byte[] aes(byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data, 0, data.length - data.length % 16);
    }

    private static MessageDigest digest(String name) throws Exception {
        String n = name == null ? "SHA1" : name.toUpperCase(java.util.Locale.ROOT).replace("-", "");
        switch (n) {
            case "SHA1": return MessageDigest.getInstance("SHA-1");
            case "SHA256": return MessageDigest.getInstance("SHA-256");
            case "SHA384": return MessageDigest.getInstance("SHA-384");
            case "SHA512": return MessageDigest.getInstance("SHA-512");
            case "MD5": return MessageDigest.getInstance("MD5");
            default: throw new StatementException("Algoritma " + name + " belum didukung.");
        }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}

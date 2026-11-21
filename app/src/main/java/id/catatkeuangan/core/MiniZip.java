package id.catatkeuangan.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Pembaca zip lewat central directory. Dipakai karena file Excel dari Livin' menulis ukuran 0 di header
 * lokal (tanpa data descriptor), sehingga ZipInputStream bawaan Java menolaknya.
 */
final class MiniZip {
    private MiniZip() {}

    interface Filter {
        boolean want(String name);
    }

    static Map<String, byte[]> read(byte[] z, Filter filter, long maxTotal) throws StatementException {
        int eocd = -1;
        for (int i = z.length - 22; i >= Math.max(0, z.length - 22 - 65535); i--) {
            if (u32(z, i) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) throw new StatementException("File Excel rusak (akhir zip tidak ditemukan).");
        int count = u16(z, eocd + 10);
        int pos = (int) u32(z, eocd + 16);
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        for (int n = 0; n < count; n++) {
            if (pos + 46 > z.length || u32(z, pos) != 0x02014b50L) {
                throw new StatementException("File Excel rusak (daftar isi zip).");
            }
            int method = u16(z, pos + 10);
            long comp = u32(z, pos + 20);
            long size = u32(z, pos + 24);
            int nameLen = u16(z, pos + 28);
            int extraLen = u16(z, pos + 30);
            int commentLen = u16(z, pos + 32);
            int local = (int) u32(z, pos + 42);
            String name = new String(z, pos + 46, nameLen, StandardCharsets.UTF_8);
            pos += 46 + nameLen + extraLen + commentLen;
            if (!filter.want(name)) continue;

            if (local + 30 > z.length || u32(z, local) != 0x04034b50L) {
                throw new StatementException("File Excel rusak (isi zip).");
            }
            int data = local + 30 + u16(z, local + 26) + u16(z, local + 28);
            if (data + comp > z.length) throw new StatementException("File Excel terpotong.");
            byte[] content;
            if (method == 0) {
                content = new byte[(int) comp];
                System.arraycopy(z, data, content, 0, (int) comp);
            } else if (method == 8) {
                content = inflate(z, data, (int) comp, size, maxTotal - total);
            } else {
                throw new StatementException("Kompresi zip jenis " + method + " belum didukung.");
            }
            total += content.length;
            if (total > maxTotal) throw new StatementException("Isi file terlalu besar.");
            out.put(name, content);
        }
        return out;
    }

    private static byte[] inflate(byte[] z, int off, int len, long size, long limit) throws StatementException {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(z, off, len);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(size > 0 && size < limit ? (int) size : 65536);
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int r = inf.inflate(buf);
                if (r == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                    continue;
                }
                bo.write(buf, 0, r);
                if (bo.size() > limit) throw new StatementException("Isi file terlalu besar.");
            }
            return bo.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new StatementException("File Excel rusak (data terkompresi).", e);
        } finally {
            inf.end();
        }
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int i) {
        return ((b[i] & 0xffL)) | ((b[i + 1] & 0xffL) << 8) | ((b[i + 2] & 0xffL) << 16) | ((b[i + 3] & 0xffL) << 24);
    }
}

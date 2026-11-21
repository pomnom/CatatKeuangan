package id.catatkeuangan.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pembaca minimal Compound File Binary (wadah yang dipakai Excel untuk file berpassword).
 * Hanya mendukung membaca stream berdasarkan nama — cukup untuk EncryptionInfo & EncryptedPackage.
 */
final class Cfb {
    private static final int HEADER_DIFAT = 109;

    private final byte[] data;
    private final ByteBuffer buf;
    private final int sectorSize;
    private final int miniSectorSize;
    private final long miniCutoff;
    private final int[] fat;
    private final int[] miniFat;
    private final byte[] miniStream;
    private final List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        String name;
        int type;
        int start;
        long size;
    }

    static boolean isCfb(byte[] d) {
        int[] sig = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};
        if (d == null || d.length < 512) return false;
        for (int i = 0; i < sig.length; i++) if ((d[i] & 0xff) != sig[i]) return false;
        return true;
    }

    Cfb(byte[] data) throws StatementException {
        if (!isCfb(data)) throw new StatementException("File bukan file Excel berpassword.");
        this.data = data;
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        try {
            sectorSize = 1 << (buf.getShort(0x1E) & 0xffff);
            miniSectorSize = 1 << (buf.getShort(0x20) & 0xffff);
            int numFat = buf.getInt(0x2C);
            int dirStart = buf.getInt(0x30);
            miniCutoff = buf.getInt(0x38) & 0xffffffffL;
            int miniFatStart = buf.getInt(0x3C);
            int numDifat = buf.getInt(0x48);
            int difatStart = buf.getInt(0x44);

            List<Integer> fatSectors = new ArrayList<>();
            for (int i = 0; i < HEADER_DIFAT && fatSectors.size() < numFat; i++) {
                int s = buf.getInt(0x4C + i * 4);
                if (s >= 0) fatSectors.add(s);
            }
            int per = sectorSize / 4 - 1;
            int ds = difatStart;
            int guard = 0;
            while (ds >= 0 && fatSectors.size() < numFat && guard++ <= numDifat) {
                int off = offset(ds);
                for (int i = 0; i < per && fatSectors.size() < numFat; i++) {
                    int s = buf.getInt(off + i * 4);
                    if (s >= 0) fatSectors.add(s);
                }
                ds = buf.getInt(off + per * 4);
            }
            int perSector = sectorSize / 4;
            fat = new int[fatSectors.size() * perSector];
            for (int i = 0; i < fatSectors.size(); i++) {
                int off = offset(fatSectors.get(i));
                for (int j = 0; j < perSector; j++) fat[i * perSector + j] = buf.getInt(off + j * 4);
            }

            byte[] dir = readChain(dirStart, -1);
            ByteBuffer db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN);
            Entry root = null;
            for (int off = 0; off + 128 <= dir.length; off += 128) {
                int type = dir[off + 0x42];
                if (type == 0) continue;
                int nameLen = db.getShort(off + 0x40) & 0xffff;
                Entry e = new Entry();
                e.name = nameLen >= 2 ? new String(dir, off, Math.min(nameLen, 64) - 2, StandardCharsets.UTF_16LE) : "";
                e.type = type;
                e.start = db.getInt(off + 0x74);
                e.size = db.getLong(off + 0x78);
                if (sectorSize == 512) e.size &= 0xffffffffL;
                if (type == 5 && root == null) root = e;
                entries.add(e);
            }
            if (root != null && root.start >= 0 && root.size > 0) {
                miniStream = readChain(root.start, root.size);
            } else {
                miniStream = new byte[0];
            }
            if (miniFatStart >= 0) {
                byte[] mf = readChain(miniFatStart, -1);
                ByteBuffer mb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN);
                miniFat = new int[mf.length / 4];
                for (int i = 0; i < miniFat.length; i++) miniFat[i] = mb.getInt(i * 4);
            } else {
                miniFat = new int[0];
            }
        } catch (IndexOutOfBoundsException e) {
            throw new StatementException("File rusak atau tidak lengkap.", e);
        }
    }

    private int offset(int sector) {
        long off = (long) (sector + 1) * sectorSize;
        if (off + sectorSize > data.length) throw new IndexOutOfBoundsException("sector " + sector);
        return (int) off;
    }

    private byte[] readChain(int start, long size) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int s = start;
        int steps = 0;
        while (s >= 0 && steps++ <= fat.length) {
            out.write(data, offset(s), sectorSize);
            if (size >= 0 && out.size() >= size) break;
            s = s < fat.length ? fat[s] : -2;
        }
        byte[] b = out.toByteArray();
        if (size >= 0 && b.length > size) {
            byte[] t = new byte[(int) size];
            System.arraycopy(b, 0, t, 0, (int) size);
            return t;
        }
        return b;
    }

    private byte[] readMini(int start, long size) {
        byte[] out = new byte[(int) size];
        int s = start;
        int pos = 0;
        int steps = 0;
        while (s >= 0 && pos < size && steps++ <= miniFat.length) {
            int off = s * miniSectorSize;
            int n = (int) Math.min(miniSectorSize, size - pos);
            if (off + n > miniStream.length) throw new IndexOutOfBoundsException("mini " + s);
            System.arraycopy(miniStream, off, out, pos, n);
            pos += n;
            s = s < miniFat.length ? miniFat[s] : -2;
        }
        return out;
    }

    /** Isi stream dengan nama persis {@code name}, atau exception bila tidak ada. */
    byte[] stream(String name) throws StatementException {
        for (Entry e : entries) {
            if (e.type == 2 && name.equals(e.name)) {
                try {
                    return e.size < miniCutoff ? readMini(e.start, e.size) : readChain(e.start, e.size);
                } catch (IndexOutOfBoundsException ex) {
                    throw new StatementException("File rusak atau tidak lengkap.", ex);
                }
            }
        }
        throw new StatementException("Bagian \"" + name + "\" tidak ditemukan di file.");
    }
}

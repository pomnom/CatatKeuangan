package id.catatkeuangan.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Membaca sheet pertama .xlsx menjadi teks per sel (baris & kolom mulai dari 1). */
public final class XlsxSheet {
    /** baris → (kolom → teks). Sel kosong tidak disimpan. */
    public final TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();

    private XlsxSheet() {}

    public static boolean isZip(byte[] b) {
        return b != null && b.length > 4 && b[0] == 'P' && b[1] == 'K';
    }

    public String get(int row, int col) {
        TreeMap<Integer, String> r = rows.get(row);
        return r == null ? null : r.get(col);
    }

    public static XlsxSheet read(byte[] xlsx) throws StatementException {
        Map<String, byte[]> files = MiniZip.read(xlsx,
                n -> n.equals("xl/workbook.xml") || n.equals("xl/_rels/workbook.xml.rels")
                        || n.equals("xl/sharedStrings.xml") || (n.startsWith("xl/worksheets/") && n.endsWith(".xml")),
                96L * 1024 * 1024);

        List<String> shared = files.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(files.get("xl/sharedStrings.xml")) : new ArrayList<>();
        String path = firstSheetPath(files);
        byte[] sheet = path == null ? null : files.get(path);
        if (sheet == null) {
            for (String n : new java.util.TreeSet<>(files.keySet())) {
                if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) {
                    sheet = files.get(n);
                    break;
                }
            }
        }
        if (sheet == null) throw new StatementException("Sheet tidak ditemukan di file Excel.");
        XlsxSheet s = new XlsxSheet();
        s.parseSheet(sheet, shared);
        return s;
    }

    private static SAXParserFactory factory() {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception ignored) {
        }
        return f;
    }

    private static String local(String localName, String qName) {
        return localName != null && !localName.isEmpty() ? localName : qName.substring(qName.indexOf(':') + 1);
    }

    private static String attr(Attributes a, String name) {
        for (int i = 0; i < a.getLength(); i++) {
            String n = a.getLocalName(i);
            if (n == null || n.isEmpty()) n = a.getQName(i);
            if (n.equals(name) || n.endsWith(":" + name)) return a.getValue(i);
        }
        return null;
    }

    private static void parse(byte[] xml, DefaultHandler h) throws StatementException {
        try (InputStream in = new ByteArrayInputStream(xml)) {
            factory().newSAXParser().parse(in, h);
        } catch (Exception e) {
            throw new StatementException("Isi file Excel tidak bisa dibaca.", e);
        }
    }

    private static String firstSheetPath(Map<String, byte[]> files) throws StatementException {
        byte[] wb = files.get("xl/workbook.xml");
        byte[] rels = files.get("xl/_rels/workbook.xml.rels");
        if (wb == null || rels == null) return null;
        final String[] rid = {null};
        parse(wb, new DefaultHandler() {
            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                if (rid[0] == null && local(ln, qn).equals("sheet")) rid[0] = attr(a, "id");
            }
        });
        if (rid[0] == null) return null;
        final String[] target = {null};
        parse(rels, new DefaultHandler() {
            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                if (local(ln, qn).equals("Relationship") && rid[0].equals(attr(a, "Id"))) target[0] = attr(a, "Target");
            }
        });
        if (target[0] == null) return null;
        String t = target[0];
        return t.startsWith("/") ? t.substring(1) : "xl/" + t;
    }

    private static List<String> sharedStrings(byte[] xml) throws StatementException {
        List<String> out = new ArrayList<>();
        parse(xml, new DefaultHandler() {
            StringBuilder cur;
            boolean inT;
            int depthRph;

            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                String n = local(ln, qn);
                if (n.equals("si")) cur = new StringBuilder();
                else if (n.equals("rPh")) depthRph++;
                else if (n.equals("t") && depthRph == 0) inT = true;
            }

            @Override
            public void endElement(String uri, String ln, String qn) {
                String n = local(ln, qn);
                if (n.equals("si")) {
                    out.add(cur == null ? "" : cur.toString());
                    cur = null;
                } else if (n.equals("rPh")) depthRph--;
                else if (n.equals("t")) inT = false;
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inT && cur != null) cur.append(ch, start, length);
            }
        });
        return out;
    }

    private void parseSheet(byte[] xml, List<String> shared) throws StatementException {
        parse(xml, new DefaultHandler() {
            String ref;
            String type;
            StringBuilder text;
            boolean inValue;
            int rowNum;
            int colNum;
            int depthRph;

            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                String n = local(ln, qn);
                switch (n) {
                    case "row": {
                        String r = attr(a, "r");
                        rowNum = r != null ? Integer.parseInt(r) : rowNum + 1;
                        colNum = 0;
                        break;
                    }
                    case "c": {
                        ref = attr(a, "r");
                        type = attr(a, "t");
                        text = new StringBuilder();
                        colNum = ref != null ? colOf(ref) : colNum + 1;
                        break;
                    }
                    case "rPh":
                        depthRph++;
                        break;
                    case "v":
                    case "t":
                        if (text != null && depthRph == 0) inValue = true;
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void endElement(String uri, String ln, String qn) {
                String n = local(ln, qn);
                if (n.equals("v") || n.equals("t")) {
                    inValue = false;
                } else if (n.equals("rPh")) {
                    depthRph--;
                } else if (n.equals("c") && text != null) {
                    String v = text.toString();
                    if ("s".equals(type)) {
                        try {
                            int idx = Integer.parseInt(v.trim());
                            v = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
                        } catch (NumberFormatException e) {
                            v = "";
                        }
                    }
                    if (!v.trim().isEmpty()) {
                        rows.computeIfAbsent(rowNum, k -> new TreeMap<>()).put(colNum, v);
                    }
                    text = null;
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inValue && text != null) text.append(ch, start, length);
            }
        });
    }

    /** "AB12" → 28. */
    static int colOf(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
            else break;
        }
        return c;
    }
}

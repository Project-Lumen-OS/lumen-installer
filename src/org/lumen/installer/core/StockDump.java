package org.lumen.installer.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.lumen.report.PropFilter;
import org.lumen.report.Scrubber;

/**
 * The stock system info a user may send from the Installer (Engine.dump): the texts of the script's dump table (root),
 * the texts of Host.facts (no root) and, for a firmware the catalog does not know, the stock settings app's APK files
 * without their pictures, videos and large libraries (slim: about a third of the APK, its code and texts, the left
 * out entries listed inside). A few MB, up to about 25 MB with a slim APK. Every text is cleaned before
 * it leaves the projector with the problem report rules of apps/core/lumen-report: a getprop, settings or app
 * preference key that names a serial, an address, a network, an account, a place or a secret loses its value
 * (PropFilter.denied and the lists below), Bluetooth device names and what a media session plays are removed, and
 * every line passes the Scrubber with the unit's serial and those values as literals. Large texts are read and cleaned
 * in parts, so memory stays small. The APK files are the system's own files, the same on every unit of a firmware.
 * Plain Java.
 */
public final class StockDump {
    private StockDump() {}

    /** what one text file may take (its end), all texts before cleaning, one APK file and the whole zip (the server
     *  takes a dump of 200 MiB) */
    public static final int FILE_MAX = 48 << 20;
    public static final long TEXT_MAX = 320L << 20, APK_MAX = 128L << 20, ZIP_MAX = 190L << 20;
    /** the stock settings app package names of the XGIMI firmwares (release_838_newsettings is com.android.newsettings);
     *  a firmware without one of them: the system app that opens the Settings screen (Host.settingsActivityPackage) */
    public static final String[] SETTINGS_APPS = { "com.android.newsettings", "com.xgimi.newsettings", "com.xgimi.settings" };
    static final int CHUNK = 256 << 10;
    static final String REMOVED = "<removed>";
    private static final Pattern PROP = Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$");
    private static final Pattern SETTING = Pattern.compile("^([A-Za-z0-9_.:-]+)=(.*)$");
    static final Pattern NAME = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)?\\.txt");
    static final Pattern APK_NAME = Pattern.compile("apk/[a-z][a-z0-9_]*(\\.[a-z0-9_]+){1,7}/[A-Za-z0-9_.-]{1,100}\\.apk");
    /** settings keys that hold a name or an address beyond PropFilter's list (bluetooth_name, device_name ...) */
    private static final Pattern SETTING_SECRET = Pattern.compile("(?i)(bluetooth|_name$|owner|address)");
    /** app preference keys that may hold a network, an account, a place or a name of the user */
    private static final Pattern PREF_SECRET = Pattern.compile(
            "(?i)(ssid|wifi|password|passwd|pwd|token|account|mail|phone|city|location|latitude|longitude|address|mac|serial"
            + "|(^|_)sn($|_)|device_?id|uuid|user|nick|name|cookie|session|secret|key)");
    private static final Pattern PREF_LINE = Pattern.compile("^(\\s*<[a-z]+ name=\")([^\"]*)(\".*)$");
    private static final Pattern MAC = Pattern.compile("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");
    private static final Pattern BT_NAME = Pattern.compile("(?i)^(\\s*(?:m?name|m?alias|friendly ?name|remote ?name|device ?name)\\s*[:=]\\s*).+$");
    /** keys whose value is an id or a name of this unit: the value is removed in every file (Scrubber literal). A
     *  narrower list than PropFilter's on purpose: "mac" there also matches "machine", whose value is a chip name. */
    private static final Pattern LITERAL_KEY = Pattern.compile(
            "(?i)(serial|(^|[._])sn($|[._])|device_?name|bt_?name|bluetooth.?name|hostname|account|e_?mail|user_?name|owner"
            + "|android_?id|gsf|uuid|ssid)");

    /** entries of an APK the slim copy leaves out: pictures, videos, sounds and fonts (by name ending), the signature
     *  folder and native libraries over LIB_MAX (third party engines such as an animation player) */
    static final Pattern MEDIA = Pattern.compile("(?i).*\\.(png|webp|jpe?g|gif|bmp|mp4|webm|mkv|3gp|pag|mp3|ogg|wav|m4a|aac|flac|ttf|otf|ttc|svga)$");
    static final long LIB_MAX = 2L << 20;
    static final String LEFT_OUT = "lumen-left-out.txt";

    /** One entry of the zip: a text (app side), a text file root wrote (read in parts), a binary file or an APK sent slim. */
    public static final class Item {
        public final String name, text;
        public final File file;
        public final boolean binary, slim;

        private Item(String name, String text, File file, boolean binary, boolean slim) {
            this.name = name;
            this.text = text;
            this.file = file;
            this.binary = binary;
            this.slim = slim;
        }

        public static Item text(String name, String text) { return new Item(name, text, null, false, false); }

        public static Item textFile(String name, File f) { return new Item(name, null, f, false, false); }

        public static Item binary(String name, File f) { return new Item(name, null, f, true, false); }

        /** an APK without its media (slim), stored in the zip under name */
        public static Item slim(String name, File f) { return new Item(name, null, f, true, true); }

        long size() { return text != null ? text.length() : file.length(); }
    }

    /** what went into the zip */
    public static final class Result {
        public int files;
        public long textBytes;
        public final List<String> left = new ArrayList<>();
    }

    /** getprop output: the value of a denied key becomes REMOVED */
    public static String props(String text) {
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            Matcher m = PROP.matcher(line);
            b.append(m.find() && PropFilter.denied(m.group(1)) ? "[" + m.group(1) + "]: [" + REMOVED + "]" : line).append('\n');
        }
        return b.toString();
    }

    /** `settings list` output (key=value, "== name" headers): the value of a denied key becomes REMOVED */
    public static String settings(String text) {
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            Matcher m = SETTING.matcher(line);
            b.append(m.find() && settingSecret(m.group(1)) ? m.group(1) + "=" + REMOVED : line).append('\n');
        }
        return b.toString();
    }

    /** the stock settings app's shared_prefs texts: an entry whose key may name the user's network, account, place or
     *  a secret keeps its key only */
    public static String prefs(String text) {
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            Matcher m = PREF_LINE.matcher(line);
            b.append(m.find() && (settingSecret(m.group(2)) || PREF_SECRET.matcher(m.group(2)).find())
                    ? m.group(1) + m.group(2) + "\" " + REMOVED + " />" : line).append('\n');
        }
        return b.toString();
    }

    /** dumpsys bluetooth_manager: the names of paired devices go, a line naming a device keeps its address (which
     *  the Scrubber then removes) */
    public static String bluetooth(String text) {
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            Matcher n = BT_NAME.matcher(line), m = MAC.matcher(line);
            if (n.find()) b.append(n.group(1)).append(REMOVED);
            else if (m.find()) b.append(line, 0, m.end()).append(' ').append(REMOVED);
            else b.append(line);
            b.append('\n');
        }
        return b.toString();
    }

    /** dumpsys media_session: what is played (titles, descriptions) goes */
    public static String mediaSessions(String text) {
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String l = line.toLowerCase(java.util.Locale.ROOT);
            b.append(l.contains("metadata") || l.contains("description=") || l.contains("title=") ? REMOVED : line).append('\n');
        }
        return b.toString();
    }

    static boolean settingSecret(String key) {
        return PropFilter.denied(key) || SETTING_SECRET.matcher(key).find();
    }

    /** the values of the removed settings (a Bluetooth name may show up in another file too) as Scrubber literals */
    public static void settingLiterals(Scrubber s, String text) {
        for (String line : text.split("\n")) {
            Matcher m = SETTING.matcher(line);
            if (m.find() && settingSecret(m.group(1)) && !m.group(2).trim().matches("[0-9.]*|true|false|null")) s.addLiteral(m.group(2), "name");
        }
    }

    /** The Scrubber of this unit: its serial and the value of every denied property long enough to be an id. */
    public static Scrubber scrubber(Map<String, String> props, String serial) {
        Scrubber s = new Scrubber().addLiteral(serial, "serial");
        for (String k : new String[] { "ro.serialno", "ro.boot.serialno" }) s.addLiteral(props.get(k), "serial");
        for (Map.Entry<String, String> e : props.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (LITERAL_KEY.matcher(e.getKey()).find() && v.length() >= 4 && !v.matches("[0-9.]+|true|false|unknown|null|none"))
                s.addLiteral(v, "id");
        }
        return s;
    }

    /** One piece of a text file as it goes out: filtered by its kind, scrubbed. Pieces end at a line end. */
    public static String clean(String name, String text, Scrubber s) {
        String t = text;
        String base = name.substring(name.lastIndexOf('/') + 1);
        if (base.equals("getprop.txt")) t = props(t);
        else if (base.equals("settings.txt")) t = settings(t);
        else if (base.equals("settings_app.txt")) t = prefs(t);
        else if (base.equals("dumpsys_bluetooth_manager.txt")) t = bluetooth(t);
        else if (base.equals("dumpsys_media_session.txt")) t = mediaSessions(t);
        return s.scrubText(t);
    }

    /** the old form: summary.txt plus texts {name, text} */
    public static int zip(File out, String summary, List<String[]> files, Scrubber s) throws IOException {
        List<Item> items = new ArrayList<>();
        for (String[] f : files) items.add(Item.text(f[0], f[1]));
        return write(out, summary, items, s).files;
    }

    /**
     * Writes the zip: summary.txt first, then every item in order (odd names are skipped), texts cleaned, APK files as
     * they are, contents.txt last (each entry and its size, and what was left out and why). Stops adding texts at
     * TEXT_MAX and anything at ZIP_MAX.
     */
    public static Result write(File out, String summary, List<Item> items, Scrubber s) throws IOException {
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        Result r = new Result();
        StringBuilder contents = new StringBuilder();
        try (Counting cnt = new Counting(new FileOutputStream(out)); ZipOutputStream z = new ZipOutputStream(cnt)) {
            z.setLevel(9);
            put(z, "summary.txt", s.scrubText(summary));
            for (Item it : items) {
                if (!(it.binary ? APK_NAME : NAME).matcher(it.name).matches()) { r.left.add(it.name + " name"); continue; }
                if (it.file != null && (!it.file.isFile() || !it.file.canRead())) { r.left.add(it.name + " unreadable"); continue; }
                long size = it.size();
                if (it.binary && (size > APK_MAX || cnt.n + size > ZIP_MAX)) { r.left.add(it.name + " " + size + " bytes, too large"); continue; }
                if (!it.binary && (r.textBytes >= TEXT_MAX || cnt.n >= ZIP_MAX - (1 << 20))) { r.left.add(it.name + " " + size + " bytes, the zip is full"); continue; }
                long before = cnt.n;
                if (it.slim) {
                    File tmp = new File(dir == null ? new File(".") : dir, "slim.tmp");
                    try {
                        long[] s2 = slim(it.file, tmp);
                        r.left.add(it.name + " " + s2[1] + " entries (" + s2[2] + " bytes) of pictures, videos and large libraries, listed in its " + LEFT_OUT);
                        stored(z, it.name, tmp);
                    } finally {
                        tmp.delete();
                    }
                } else if (it.binary) stored(z, it.name, it.file);
                else if (it.text != null) {
                    String t = it.text.length() > FILE_MAX ? it.text.substring(it.text.length() - FILE_MAX) : it.text;
                    r.textBytes += t.length();
                    put(z, it.name, clean(it.name, t, s));
                } else r.textBytes += textFile(z, it.name, it.file, s);
                r.files++;
                contents.append(it.name).append(' ').append(size).append(" bytes, ").append(cnt.n - before).append(" in the zip\n");
            }
            for (String l : r.left) contents.append("left out: ").append(l).append('\n');
            put(z, "contents.txt", contents.toString());
        }
        return r;
    }

    /** a text file root wrote: its last FILE_MAX bytes from a line start, cleaned in pieces that end at a line end */
    private static long textFile(ZipOutputStream z, String name, File f, Scrubber s) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        long read = 0;
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long len = in.length(), from = Math.max(0, len - FILE_MAX);
            in.seek(from);
            if (from > 0) while (in.getFilePointer() < len && in.read() != '\n') { /* to the next line */ }
            byte[] buf = new byte[CHUNK];
            byte[] carry = new byte[0];
            int k;
            while ((k = in.read(buf)) > 0) {
                read += k;
                byte[] all = new byte[carry.length + k];
                System.arraycopy(carry, 0, all, 0, carry.length);
                System.arraycopy(buf, 0, all, carry.length, k);
                int cut = all.length;
                while (cut > 0 && all[cut - 1] != '\n') cut--;
                if (cut == 0 && all.length < 4 * CHUNK) { carry = all; continue; }   // a very long line: wait for its end
                if (cut == 0) cut = all.length;
                z.write(clean(name, new String(all, 0, cut, StandardCharsets.UTF_8), s).getBytes(StandardCharsets.UTF_8));
                carry = java.util.Arrays.copyOfRange(all, cut, all.length);
            }
            if (carry.length > 0) z.write(clean(name, new String(carry, StandardCharsets.UTF_8), s).getBytes(StandardCharsets.UTF_8));
        }
        z.closeEntry();
        return read;
    }

    /** the name of an APK sent slim: Settings.apk becomes Settings.slim.apk (jadx and unzip open it as an APK) */
    public static String slimName(String apk) {
        return apk.endsWith(".apk") ? apk.substring(0, apk.length() - 4) + ".slim.apk" : apk + ".slim.apk";
    }

    /** true: the slim copy of an APK leaves this entry out */
    static boolean leftOut(String name, long size) {
        return name.startsWith("META-INF/") || MEDIA.matcher(name).matches() || (name.startsWith("lib/") && size > LIB_MAX);
    }

    /**
     * Writes the slim copy of an APK to `to`: every entry but the left out ones (leftOut), deflated at level 9 (an APK
     * keeps its code uncompressed, so this alone saves about half of it), and LEFT_OUT listing each left out entry
     * with its size and CRC. Returns {entries kept, entries left out, bytes left out}.
     */
    static long[] slim(File apk, File to) throws IOException {
        long[] n = new long[3];
        StringBuilder left = new StringBuilder();
        byte[] buf = new byte[64 * 1024];
        try (java.util.zip.ZipFile in = new java.util.zip.ZipFile(apk); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(to))) {
            out.setLevel(9);
            java.util.Enumeration<? extends ZipEntry> en = in.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (leftOut(e.getName(), e.getSize())) {
                    left.append(e.getName()).append(' ').append(e.getSize()).append(' ').append(Long.toHexString(e.getCrc())).append('\n');
                    n[1]++;
                    n[2] += e.getSize();
                    continue;
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                try (InputStream i = in.getInputStream(e)) {
                    for (int k; (k = i.read(buf)) > 0; ) out.write(buf, 0, k);
                }
                out.closeEntry();
                n[0]++;
            }
            out.putNextEntry(new ZipEntry(LEFT_OUT));
            out.write(("left out of " + apk.getName() + " (" + apk.length() + " bytes): name size crc32\n" + left).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return n;
    }

    /** an APK as it is (it is compressed already): stored, its CRC first */
    private static void stored(ZipOutputStream z, String name, File f) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            int k;
            while ((k = in.read(buf)) > 0) crc.update(buf, 0, k);
        }
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(f.length());
        e.setCompressedSize(f.length());
        e.setCrc(crc.getValue());
        z.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            int k;
            while ((k = in.read(buf)) > 0) z.write(buf, 0, k);
        }
        z.closeEntry();
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    /** the bytes written so far */
    private static final class Counting extends FilterOutputStream {
        long n;

        Counting(OutputStream o) { super(o); }

        @Override public void write(int b) throws IOException { out.write(b); n++; }

        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); n += len; }
    }

    /** a text file, read as UTF-8, at most max bytes from its end */
    static String read(File f, int max) throws IOException {
        long len = f.length();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long from = Math.max(0, len - max);
            r.seek(from);
            byte[] b = new byte[(int) (len - from)];
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }
    }
}

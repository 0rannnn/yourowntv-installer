package com.gathin.kit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import android.content.Intent;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.muntashirakon.adb.AdbStream;

/**
 * The install kit's work and the phone page's requests. The same steps as the computer wizard (wizard.py): back up,
 * install the launcher with its permissions, put the chosen themes on with the owner's name and city, turn apps off,
 * our screensaver, and optionally the ad-blocking DNS. Themes, the launcher and the lists ride in this app's assets
 * (packed by pack.py from the editor's theme library), so nothing is downloaded here.
 */
final class Kit implements WebServer.Handler {
    static final String PKG = "com.gathin.tvhome";
    static final String FILES = "/sdcard/Android/data/" + PKG + "/files";
    static final String URI = "content://" + PKG + ".theme";
    private static final String TMP = "/data/local/tmp/kit-file";
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final Pattern PROTECTED = Pattern.compile("^(android|com\\.android\\.(systemui|shell|phone|settings|"
            + "tv\\.settings|permissioncontroller|packageinstaller|providers\\..*|ext\\..*|modulemetadata)|com\\.google\\."
            + "android\\.(permissioncontroller|packageinstaller|ext\\..*|modulemetadata|overlay\\.modules.*|gms|gsf)|"
            + "com\\.android\\.vending)$");
    private static final String[] PERSONAL = {"greeting_name", "greeting_name_he", "location_label",
            "location_label_he", "weather_lat", "weather_lon"};
    private static final String[] THEME_FILES = {"config.json", "clock_font.ttf", "nav_sound.wav", "ui_font_he.ttf",
            "wallpaper.jpg"};

    private final Context context;
    private final AssetManager assets;

    // What the phone page and the TV screen show while a run goes on.
    volatile boolean running, done;
    volatile String error, step = "";
    /** How far the run is, 0 to 100, for the bar on the TV and on the phone. */
    volatile int progress;
    /** While this app downloads its newer version (updateIfNewer): 0 to 100; -1 the rest of the time. */
    volatile int downloadPct = -1;
    /** The last run was the red button's removal (the screen then says so, with no setup after it). */
    volatile boolean removed;
    /** The last run was "Update the current pack" (updateOnly): done shows "updated", no setup screens. */
    volatile boolean updated;
    /** Theme files sent so far and in all, for the bar while the themes go on. */
    private int filesDone, filesTotal;
    final List<String[]> log = new ArrayList<>();

    Kit(Context context) {
        this.context = context;
        this.assets = context.getAssets();
    }

    // ---- the phone's requests -------------------------------------------------------------------------------

    @Override
    public WebServer.Response handle(WebServer.Request r) throws Exception {
        switch (r.path) {
            case "/":
            case "/index.html":
                return new WebServer.Response(200, "text/html; charset=utf-8", read("www/index.html"));
            case "/api/info":
                return WebServer.Response.json(info().toString());
            case "/api/preview":
                byte[] picture = preview(r.query.get("slot"));
                return picture == null ? WebServer.Response.notFound() : new WebServer.Response(200, "image/jpeg", picture);
            case "/api/pair":
                return WebServer.Response.json(pair(new JSONObject(r.bodyText()).optString("code")).toString());
            case "/api/connect":
                return WebServer.Response.json(connect().toString());
            case "/api/run":
                start(new JSONObject(r.bodyText()), false);
                return WebServer.Response.json("{\"ok\":true}");
            case "/api/restore":
                start(new JSONObject(), true);
                return WebServer.Response.json("{\"ok\":true}");
            case "/api/state":
                return WebServer.Response.json(state().toString());
            case "/photos":
                return new WebServer.Response(200, "text/html; charset=utf-8", read("www/photos.html"));
            case "/api/photo":
                addPhoto(r.body);
                return WebServer.Response.json("{\"ok\":true,\"count\":" + photoCount() + "}");
            case "/api/photos":
                return WebServer.Response.json("{\"count\":" + photoCount() + ",\"ready\":" + done + "}");
            default:
                return null;
        }
    }

    private JSONObject info() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray themes = new JSONArray();
        for (String slot : slots()) {
            JSONObject cfg = new JSONObject(new String(read("library/" + slot + "/config.json"), StandardCharsets.UTF_8));
            themes.put(new JSONObject().put("slot", slot).put("name", cfg.optString("theme_name", slot))
                    .put("name_he", cfg.optString("theme_name_he", slot)));
        }
        out.put("themes", themes);
        JSONArray lists = new JSONArray();
        String[] files = assets.list("debloat");
        if (files != null) {
            Arrays.sort(files);
            for (String file : files) {
                JSONArray packages = new JSONArray();
                for (String[] p : listPackages(file)) packages.put(new JSONObject().put("package", p[0]).put("note", p[1]));
                lists.put(new JSONObject().put("file", file).put("packages", packages));
            }
        }
        out.put("lists", lists);
        boolean connected = false;
        try {
            connected = Adb.get(context).isConnected();
        } catch (Exception ignored) {
        }
        out.put("connected", connected).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("backup", latestBackup() != null);
        return out;
    }

    private JSONObject pair(String code) {
        JSONObject out = new JSONObject();
        try {
            if (!code.matches("[0-9]{6}")) return out.put("ok", false).put("error", "הקוד הוא 6 ספרות");
            Adb adb = Adb.get(context);
            if (!adb.pairWithCode(code)) return out.put("ok", false).put("error", "הצימוד לא הצליח: לבדוק את הקוד במקרן");
            return connect();
        } catch (IllegalStateException e) {
            return safe(out, "לא מצאתי את חלון הצימוד: במקרן, ב-Wireless debugging, לבחור Pair device with pairing code ולהשאיר אותו פתוח");
        } catch (Throwable e) {
            return safe(out, "הצימוד לא הצליח: " + e.getMessage());
        }
    }

    private JSONObject connect() {
        JSONObject out = new JSONObject();
        try {
            boolean ok = Adb.get(context).connectSelf();
            if (!ok) return out.put("ok", false).put("error", "עוד לא מחובר: צריך קודם לצמד עם הקוד");
            return out.put("ok", true).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE);
        } catch (Throwable e) {
            return safe(out, "לא הצלחתי להתחבר: " + e.getMessage());
        }
    }

    private static JSONObject safe(JSONObject out, String message) {
        try {
            return out.put("ok", false).put("error", message);
        } catch (Exception e) {
            return out;
        }
    }

    private synchronized JSONObject state() throws Exception {
        JSONArray lines = new JSONArray();
        for (String[] line : log) lines.put(new JSONObject().put("t", line[0]).put("kind", line[1]).put("text", line[2]));
        return new JSONObject().put("running", running).put("done", done).put("error", error == null ? JSONObject.NULL : error)
                .put("step", step).put("progress", progress).put("log", lines);
    }

    // ---- the run --------------------------------------------------------------------------------------------

    /** The TV's own "install now" button: everything with the usual choices, no phone needed once connected. */
    synchronized boolean startAuto() {
        if (running) return false;
        try {
            // Every theme from the editor's library, Apple first, our screensaver instead of Google's. No name and no
            // place built in (this app may be downloaded by anyone): the setup screens after the install ask for them,
            // and an update puts back what was typed there last time.
            start(new JSONObject().put("screensaver", true).put("block_google", true).put("default", "apple"), false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The red button: everything of ours off this TV, the TV's own home and settings back (removeAll). */
    /** "Update the current pack": the newest launcher over the one on the TV, everything of the owner's kept. */
    synchronized boolean startUpdate() {
        if (running) return false;
        try {
            start(new JSONObject().put("update", true), false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    synchronized boolean startRemove() {
        if (running) return false;
        try {
            start(new JSONObject().put("remove", true), false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- self-update: the installer on the stick or from Downloader may be older than the one online ----------

    /** Where the newest installer was before (its free credits ran out): the last place to look. */
    static final String OLD_SITE = "https://tv-i.netlify.app";

    /** Where the newest installer is: the GitHub Pages site deploy.py uploads to (assets/site.txt), then OLD_SITE. */
    private String[] sites() {
        try (InputStream in = context.getAssets().open("site.txt")) {
            // Read by hand: InputStream.readAllBytes() only exists from Android 13, and on Android 12 (a TCL) the
            // missing method crashed every button that checks for a newer installer.
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            // One address per line (deploy.py uploads to each; the first is the one older installers know).
            List<String> out = new ArrayList<>();
            for (String site : new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                if (site.trim().startsWith("https://")) out.add(site.trim());
            }
            if (!out.isEmpty()) {
                out.add(OLD_SITE);
                return out.toArray(new String[0]);
            }
        } catch (IOException none) {
            // built without one
        }
        return new String[]{OLD_SITE};
    }

    /**
     * The pack's version online (version.json's "version", the number both apps are built with); 0 when the host
     * answers but gives no number (an upload from before the numbers), -1 when no host answers. Network: call it off
     * the main thread.
     */
    int onlineVersion() {
        for (String s : sites()) {
            try {
                return new JSONObject(new String(fetch(s + "/version.json", 8_000), StandardCharsets.UTF_8)).optInt("version", 0);
            } catch (Exception tryNext) {
                // this host is not answering: the next one
            }
        }
        return -1;
    }

    /** The version of a package on this TV when it is a pack number ("75"), else -1 (not installed, or older builds). */
    static int installedVersion(Context context, String pkg) {
        try {
            String name = context.getPackageManager().getPackageInfo(pkg, 0).versionName;
            return name != null && name.matches("\\d+") ? Integer.parseInt(name) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * When the installer online (SITE/version.json, written by deploy.py on every commit) is not this one, it is
     * downloaded and installed over this app, which then starts again by itself (this call never comes back then).
     * Returns false when there is nothing newer or anything goes wrong (no internet, no connection to the TV): carry on
     * with this version.
     */
    boolean updateIfNewer(java.util.function.Consumer<String> status) {
        JSONObject online = null;
        String site = null;
        for (String s : sites()) {
            try {
                online = new JSONObject(new String(fetch(s + "/version.json", 8_000), StandardCharsets.UTF_8));
                site = s;
                break;
            } catch (Exception tryNext) {
                // this host is not answering: the next one
            }
        }
        if (online == null) return false;
        try {
            String want = online.optString("sha256");
            if (want.isEmpty() || want.equalsIgnoreCase(sha256(new File(context.getApplicationInfo().sourceDir)))) return false;
            // By the version number when both have one: only a newer one replaces this app, never an older one (a
            // test build here, or a host that has not caught up yet).
            int newest = online.optInt("version", 0), mine = installedVersion(context, context.getPackageName());
            if (newest > 0 && mine > 0 && mine >= newest) return false;
            status.accept("יש גרסה חדשה של המתקין, מורידה…");
            say("גרסה חדשה של המתקין מ-" + site + " (" + online.optString("built") + ")", "info");
            flushLog();
            File apk = new File(context.getExternalFilesDir(null), "update.apk");
            downloadPct = 0;
            try (InputStream in = open(site + "/tv.apk", 20_000); OutputStream out = new java.io.FileOutputStream(apk)) {
                byte[] buffer = new byte[65536];
                long total = Math.max(1, online.optLong("size", 20_000_000)), got = 0;
                int n, last = -1;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    got += n;
                    int pct = (int) Math.min(100, got * 100 / total);
                    downloadPct = pct;
                    if (pct != last && pct % 5 == 0) {
                        last = pct;
                        status.accept("מוריד את הגרסה החדשה של המתקין… " + pct + "/100");
                    }
                }
            }
            downloadPct = 100;
            if (!want.equalsIgnoreCase(sha256(apk))) return false;   // a broken download: keep this version
            if (!connectNow()) return false;
            status.accept("מתקין את הגרסה החדשה של המתקין…");
            String path = "/sdcard/Android/data/" + context.getPackageName() + "/files/update.apk";
            // The install replaces (and stops) this app, which takes its shell session down with it: the install and
            // the start of the new version run apart from it (nohup, in the background), so the new one always opens.
            try {
                // The system cannot read Android/data (SELinux): the shell copies it to /data/local/tmp, then installs.
                // setsid + nohup take it out of this session; the second's wait lets it get away before the session
                // closes (tested on the projector: without it the job dies with the session).
                sh("cp " + path + " /data/local/tmp/kit-update.apk && setsid nohup sh -c 'pm install -r -g /data/local/tmp/kit-update.apk; "
                        + "rm -f /data/local/tmp/kit-update.apk; sleep 1; am start -n " + context.getPackageName()
                        + "/.MainActivity' >/dev/null 2>&1 </dev/null & sleep 1", 60_000, 1);
                Thread.sleep(60_000);   // the app is replaced well before this ends
            } catch (Exception replacedMidway) {
                // expected: this app is gone before the command ends
            }
            return false;   // still here a minute later: the update did not go in, carry on with this version
        } catch (Exception e) {
            return false;
        } finally {
            downloadPct = -1;
        }
    }

    private static InputStream open(String address, int timeoutMs) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        return c.getInputStream();
    }

    private static byte[] fetch(String address, int timeoutMs) throws IOException {
        try (InputStream in = open(address + "?t=" + System.currentTimeMillis(), timeoutMs)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String sha256(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) md.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    /**
     * The shell-side watcher for holding a volume key (the launcher's VolumeHold): getevent's lines streamed to the
     * launcher on 127.0.0.1:8897, so a held key repeats quickly even where the firmware hides the keys from apps. It
     * runs on its own session (it outlives this connection) and reconnects whenever Home restarts; the old one goes
     * first, by its saved process group. Shell processes end at a reboot: WatcherReceiver starts it again.
     */
    static final String VOLUME_WATCHER = "p=/data/local/tmp/tvhome-volhold.pid; [ -f $p ] && kill -9 -$(cat $p) 2>/dev/null; "
            + "setsid nohup sh -c 'echo $$ > /data/local/tmp/tvhome-volhold.pid; while true; do getevent -ql 2>/dev/null"
            + " | nc 127.0.0.1 8897 >/dev/null 2>&1; sleep 3; done' >/dev/null 2>&1 </dev/null & sleep 1";

    /**
     * The order of the TV's own Google TV home ("Your apps"), as packages one per line; "" when it cannot be read.
     * Google TV keeps it to itself, so it is read off the screen: its Apps tab opens, the row is walked with the
     * remote's Right key and read with uiautomator after every few steps, and the names are matched to the apps.
     * Takes about 15 seconds; this app comes back to the front afterwards.
     */
    private String googleTvOrder() {
        String gtv = "com.google.android.apps.tv.launcherx";
        try {
            if (!sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1")
                    .contains(gtv)) return "";
            stepTo("1. קורא את סדר האפליקציות מ-Google TV");
            // Our launcher already here (an update) sends Google TV's home away the moment it opens (its
            // stock_home_redirect): stopped first, so Google TV stays on screen to be read. It is reinstalled next.
            if (sh("pm path " + PKG).contains("package:")) sh("am force-stop " + PKG);
            sh("am start -n " + gtv + "/.home.HomeActivity; sleep 5; input keyevent DPAD_UP; input keyevent DPAD_UP; "
                    + "input keyevent DPAD_RIGHT; sleep 3; input keyevent DPAD_DOWN; sleep 1", 30_000, 1);
            List<String> names = new ArrayList<>();
            String lastSeen = "";
            for (int round = 0; round < 10; round++) {
                // Only "name|bounds" lines come back (the whole dump is one 35 KB line, too much for this connection).
                String lines = sh("uiautomator dump /sdcard/kit-gtv.xml >/dev/null 2>&1; tr '>' '\\n' < /sdcard/kit-gtv.xml"
                        + " | sed -n 's/.*content-desc=\"\\([^\"]*\\)\".*bounds=\"\\([^\"]*\\)\".*/\\1|\\2/p'", 20_000, 1);
                List<String> row = yourAppsRow(lines);
                if (row.isEmpty()) break;
                mergeInOrder(names, row);
                String now = row.get(row.size() - 1);
                if (now.equals(lastSeen)) break;   // the row's end: walking on shows nothing new
                lastSeen = now;
                sh("for i in 1 2 3 4 5 6; do input keyevent DPAD_RIGHT; done; sleep 1", 20_000, 1);
            }
            sh("rm -f /sdcard/kit-gtv.xml");
            backInFront();
            List<String> packages = packagesFor(names);
            say("סדר האפליקציות מ-Google TV: " + packages.size() + " אפליקציות", packages.isEmpty() ? "warn" : "ok");
            return String.join("\n", packages);
        } catch (Exception e) {
            say("לא הצלחתי לקרוא את סדר האפליקציות מ-Google TV (ממשיך בלעדיו): " + e, "warn");
            try {
                backInFront();
            } catch (Exception ignored) {
            }
            return "";
        }
    }

    /** The app names in the "Your apps" row, left to right, from uiautomator's "content-desc|bounds" lines. */
    static List<String> yourAppsRow(String lines) {
        int top = -1, bottom = -1;
        List<int[]> boxes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String line : lines.split("\n")) {
            int bar = line.lastIndexOf('|');
            if (bar < 0) continue;
            String desc = line.substring(0, bar).trim();
            int[] b = bounds(line.substring(bar + 1));
            if (b == null) continue;
            if (top < 0 && "Your apps".equals(desc)) {
                top = b[1];
                bottom = b[3];
                continue;
            }
            if (top >= 0 && !desc.isEmpty() && b[1] >= top && b[3] <= bottom + 2 && b[2] - b[0] < 400) {
                boxes.add(b);
                labels.add(clean(desc));
            }
        }
        Integer[] idx = new Integer[labels.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, c) -> Integer.compare(boxes.get(a)[0], boxes.get(c)[0]));
        List<String> out = new ArrayList<>();
        for (int i : idx) if (!out.contains(labels.get(i))) out.add(labels.get(i));
        return out;
    }

    /** Adds the names of the next view of the row after the ones already read (the part both views show once). */
    static void mergeInOrder(List<String> all, List<String> view) {
        for (String name : view) if (!all.contains(name)) all.add(name);
    }

    private static int[] bounds(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(text);
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4))};
    }

    /** A label as uiautomator writes it: direction marks dropped, &amp; and friends decoded. */
    static String clean(String label) {
        return label.replaceAll("[\\u200e\\u200f\\u202a-\\u202e]", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    /** The packages of the apps with these names, in the same order (names that match no app are left out). */
    private List<String> packagesFor(List<String> names) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        java.util.Map<String, String> byLabel = new java.util.HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                byLabel.putIfAbsent(clean(String.valueOf(ri.loadLabel(pm))).toLowerCase(Locale.ROOT), ri.activityInfo.packageName);
                byLabel.putIfAbsent(clean(String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm))).toLowerCase(Locale.ROOT),
                        ri.activityInfo.packageName);
            }
        }
        List<String> out = new ArrayList<>();
        for (String name : names) {
            String p = byLabel.get(name.toLowerCase(Locale.ROOT));
            if (p != null && !out.contains(p) && !p.equals(PKG)) out.add(p);
        }
        return out;
    }

    /**
     * Troubleshooting from afar (the launcher's Troubleshooting card): the TV's ADB listening on port 5555 on every
     * network, so the computer reaches it through Tailscale wherever the TV is. True when it listens (now or already).
     */
    boolean openRemoteAdb() {
        try {
            if (!connectNow()) return false;
            if (remoteAdbOpen()) return true;
            Adb.get(context).tcpip(5555);
            for (int i = 0; i < 8; i++) {
                Thread.sleep(1500);
                try {
                    if (connectNow() && remoteAdbOpen()) return true;
                } catch (Exception ignored) {
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the TV's ADB listens on port 5555 for everyone (0.0.0.0 or ::), not only on this TV itself. */
    private boolean remoteAdbOpen() throws Exception {
        return !sh("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -iE ' (00000000|00000000000000000000000000000000):15B3 .* 0A '")
                .trim().isEmpty();
    }

    /**
     * The troubleshooting report: what the TV is, what of ours runs on it, its volume and who changed it (the
     * system's own volume history names the app behind every change), its input devices and the recent log lines
     * that matter. Written on the TV (nothing long comes back over this connection) to Download/tv-report.txt and
     * into the launcher's files, where the phone page offers it for download. Nothing leaves the TV by itself.
     */
    boolean writeReport() {
        try {
            if (!connectNow()) return false;
            String f = "/sdcard/Download/tv-report.txt";
            sh("{ echo \"== tv report $(date)\"; getprop ro.product.manufacturer; getprop ro.product.model;"
                    + " echo \"Android $(getprop ro.build.version.release)\";"
                    + " dumpsys package com.gathin.tvhome | grep -E 'versionName|lastUpdateTime' | head -2;"
                    + " dumpsys package com.gathin.kit | grep -E 'versionName|lastUpdateTime' | head -2;"
                    + " echo '== settings'; settings get secure enabled_accessibility_services;"
                    + " cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1;"
                    + " echo '-- disabled'; pm list packages -d;"
                    + " echo '== volume'; cmd media_session volume --stream 3 --get | tail -1;"
                    + " dumpsys audio | grep -iE 'fixed volume|mFixedVolume|safe media|isMasterMute|full volume' | head -8;"
                    + " echo '-- who changed the volume (newest last)';"
                    + " dumpsys audio | grep -iE 'adjustStreamVolume|setStreamVolume|adjustSuggestedStreamVolume|volumeKey|setMasterMute' | tail -80;"
                    + " echo '== watcher'; ps -A | grep -E ' getevent$| nc$'; cat /data/local/tmp/tvhome-volhold.pid 2>/dev/null;"
                    + " echo '== input devices'; getevent -i 2>/dev/null | grep -E 'add device|name:' | head -40;"
                    + " echo '== log'; logcat -d -t 1500 | grep -iE 'gathin|VolumeHold|VolumeMeter|VolumeKeys|audio_hw|AudioService|ProjectorIR|AndroidRuntime|FATAL' | tail -250;"
                    // Through a pipe: the system's "cmd" commands refuse to write straight into a file on the storage.
                    + " } 2>&1 | cat > " + f + "; content write --uri " + URI + "/tv-report.txt < " + f, 60_000, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * "Update the current pack": the launcher inside this installer installed over the one on the TV (pm install -r,
     * no pm clear), so the owner's name, place, photos, app order, theme choice and every setting stay; the theme
     * files are left as they are (the owner's editor changes too). Then Home restarts and the volume watcher starts.
     */
    private void updateOnly() throws Exception {
        if (!connectNow()) throw new IllegalStateException("לא מחובר לטלוויזיה");
        if (!sh("pm path " + PKG).contains("package:")) {
            throw new IllegalStateException("מסך הבית לא מותקן בטלוויזיה הזאת: ללחוץ \"התקן עכשיו\"");
        }
        progress = 3;
        stepTo("מעדכן את מסך הבית (כל ההגדרות נשמרות)");
        pushAsset("launcher.apk", "/data/local/tmp/kit.apk", 3, 70);
        step = "מתקין את הגרסה החדשה… אם קפץ חלון אישור (Play Protect), לאשר אותו בשלט";
        String result = sh("timeout 180 pm install -r -g /data/local/tmp/kit.apk; rm -f /data/local/tmp/kit.apk", 200_000, 2);
        if (!result.contains("Success")) throw new IllegalStateException("העדכון נכשל: " + result.trim());
        progress = 85;
        stepTo("מפעיל מחדש את מסך הבית");
        sh(VOLUME_WATCHER, 20_000, 1);
        restartHome();
        backInFront();
        progress = 98;
    }

    /** Starts (or restarts) the volume watcher; false when this TV's ADB does not answer. */
    boolean startVolumeWatcher() {
        try {
            if (!connectNow()) return false;
            sh(VOLUME_WATCHER, 20_000, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether this TV's own ADB answers now (paired before, or its network debugging port is open). */
    boolean connectNow() {
        try {
            if (Adb.get(context).isConnected()) return true;
        } catch (Throwable ignored) {
        }
        // A new connection, with its own time limit (a stuck one is let go, never waited on).
        return Adb.fresh(context);
    }

    private synchronized void start(JSONObject options, boolean restore) {
        if (running) throw new IllegalStateException("כבר רץ");
        running = true;
        progress = 1;
        done = false;
        error = null;
        log.clear();
        unsaved.clear();
        // A foreground service keeps this app running while the run goes on: Android TV freezes an app that is not
        // in front, and the install stood still the moment the new home came forward.
        try {
            context.startForegroundService(new android.content.Intent(context, InstallService.class));
        } catch (Throwable ignored) {
        }
        new Thread(() -> {
            try {
                say("מתחיל: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE, "info");
                removed = options.optBoolean("remove");
                updated = options.optBoolean("update");
                if (removed) removeAll();
                else if (updated) updateOnly();
                else if (restore) restore();
                else install(options);
                done = true;
                progress = 100;
                say(updated ? "הערכה עודכנה: השם, המקום, התמונות, סדר האפליקציות וההגדרות נשמרו"
                        : options.optBoolean("remove") ? "הערכה הוסרה, הטלוויזיה חזרה למסך הבית המקורי"
                        : restore ? "המקרן הוחזר למצב שלפני ההתקנה" : "ההתקנה הסתיימה", "ok");
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                say(error, "error");
            } finally {
                running = false;
                flushLog();
                try {
                    context.stopService(new android.content.Intent(context, InstallService.class));
                } catch (Throwable ignored) {
                }
            }
        }, "kit-run").start();
    }

    synchronized void say(String text, String kind) {
        String[] line = {new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()), kind, text};
        log.add(line);
        unsaved.add(line);
    }

    /** Log lines not yet written to the USB stick. */
    private final List<String[]> unsaved = new ArrayList<>();

    /**
     * The log so far onto every USB stick in the TV (tv-install-log.txt) and into Downloads, through the shell user,
     * which may write there: when a run stops somewhere, the stick brings back where and why. Never stops the run.
     */
    void flushLog() {
        StringBuilder text = new StringBuilder();
        synchronized (this) {
            if (unsaved.isEmpty()) return;
            for (String[] l : unsaved) text.append(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()))
                    .append(' ').append(l[0]).append("  ").append(l[1]).append("  ").append(l[2]).append('\n');
            unsaved.clear();
        }
        try {
            if (!Adb.get(context).isConnected()) return;
            String b64 = android.util.Base64.encodeToString(text.toString().getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            Adb.get(context).sh("for d in /storage/*-* /sdcard/Download; do [ -d \"$d\" ] && echo '" + b64
                    + "' | base64 -d >> \"$d/tv-install-log.txt\" 2>/dev/null; done; true", 15_000, 1);
        } catch (Throwable ignored) {
            // no stick, or no access: the screen still shows it
        }
    }

    private void stepTo(String name) throws Exception {
        step = name;
        say(name, "step");
        sh("input keyevent WAKEUP");   // the projector sleeps after 10 idle minutes and drops off Wi-Fi
    }

    /** This app back in front: the new home came forward to load its themes, and the install screen goes on top. */
    private void backInFront() {
        try {
            sh("am start -n " + context.getPackageName() + "/.MainActivity", 20_000);
        } catch (Throwable ignored) {
        }
    }

    private String sh(String command) throws Exception {
        return Adb.get(context).sh(command);
    }

    private String sh(String command, long limitMs, int tries) throws Exception {
        return Adb.get(context).sh(command, limitMs, tries);
    }

    private String sh(String command, long limitMs) throws Exception {
        return Adb.get(context).sh(command, limitMs);
    }

    private void install(JSONObject o) throws Exception {
        boolean dry = o.optBoolean("dry");
        redirect = "";
        direct = false;
        if (!connectNow()) throw new IllegalStateException("לא מחובר: קודם לצמד עם הקוד (שלב 1)");
        tcl = sh("getprop ro.product.brand; getprop ro.product.manufacturer").matches("(?is).*\\bTCL\\b.*");
        if (tcl) say("מצב TCL: השלט של TCL (גלגל השיניים, YouTube) ומסך הבית של Google TV מסודרים לבד", "ok");
        List<String> all = slots();
        List<String> chosen = new ArrayList<>();
        JSONArray picked = o.optJSONArray("themes");
        for (int i = 0; picked != null && i < picked.length(); i++) if (all.contains(picked.getString(i))) chosen.add(picked.getString(i));
        if (chosen.isEmpty()) chosen.addAll(all);
        String def = chosen.contains(o.optString("default")) ? o.optString("default") : chosen.get(0);
        JSONObject place = o.optJSONObject("place");

        progress = 2;
        stepTo("1. גיבוי של המצב הנוכחי");
        File backup = new File(context.getExternalFilesDir(null), "backups/" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(new Date()));
        if (!backup.mkdirs() && !backup.isDirectory()) throw new IOException("אין מקום לגיבוי");
        write(new File(backup, "packages.txt"), sh("pm list packages"));
        write(new File(backup, "disabled-before.txt"), sh("pm list packages -d"));
        String home0 = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1");
        String saver0 = sh("settings get secure screensaver_components");
        String dns0 = sh("settings get global private_dns_mode") + "|" + sh("settings get global private_dns_specifier");
        JSONObject state = new JSONObject().put("home", home0).put("screensaver", saver0).put("dns", dns0).put("lists", new JSONArray());
        write(new File(backup, "state.json"), state.toString(2));
        say("נשמר", "ok");

        // The app order to keep: ours from before (an update starts the launcher clean), or the order the TV's own
        // Google TV home had. Handed to the launcher after the install (order-import.txt, OrderImport there).
        String order = "";
        if (!dry) {
            order = sh("cat " + FILES + "/order-backup.txt 2>/dev/null").trim();
            if (!order.isEmpty()) say("סדר האפליקציות שלך נשמר: " + order.split("\n").length + " אפליקציות", "ok");
            else order = googleTvOrder();
        }

        progress = 5;
        stepTo("2. התקנת מסך הבית");
        if (dry) say("[ניסיון] מתקין את מסך הבית", "dry");
        else {
            // Already on this TV: an update from the stick starts clean, so the old settings and themes go and the
            // ones from the stick replace them (Android keeps the app's permissions; they are granted again below).
            if (sh("pm path " + PKG).contains("package:")) {
                sh("am force-stop " + PKG + "; pm clear " + PKG);
                say("מסך הבית כבר מותקן: ההגדרות הישנות נמחקו ומוחלפות בחדשות", "info");
            }
            pushAsset("launcher.apk", "/data/local/tmp/kit.apk", 5, 28);
            // The TV installs it now; Google Play Protect (or a maker's own check) may put up a question first and
            // wait for it. Say so on the screen, creep the bar so it does not look frozen, and stop after 3 minutes
            // instead of hanging forever.
            step = "2. מתקין את מסך הבית… אם קפץ חלון אישור (Play Protect), לאשר אותו בשלט";
            say(step, "info");
            Thread creep = new Thread(() -> {
                try {
                    while (progress < 31) {
                        Thread.sleep(6000);
                        progress++;
                    }
                } catch (InterruptedException ignored) {
                }
            }, "kit-creep");
            creep.start();
            String result;
            try {
                result = sh("timeout 180 pm install -r -g /data/local/tmp/kit.apk; rm -f /data/local/tmp/kit.apk", 200_000, 2);
            } finally {
                creep.interrupt();
            }
            if (!result.contains("Success")) {
                if (result.trim().isEmpty()) throw new IllegalStateException("ההתקנה לא הסתיימה תוך 3 דקות. כנראה הטלוויזיה "
                        + "מחכה לאישור (Play Protect): ללחוץ אחורה, לאשר את החלון, ואז לנסות שוב");
                throw new IllegalStateException("ההתקנה נכשלה: " + result);
            }
            step = "2. התקנת מסך הבית";
            // The fast route for the themes: this app may now write them straight through the launcher's provider.
            sh("pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS", 20_000);
            direct = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            say(direct ? "ערכות הנושא נכתבות ישירות (מהיר)" : "ערכות הנושא נשלחות דרך ADB", "info");
        }
        progress = 32;
        String service = PKG + "/.HomeKeyService";
        String[] setup = {
                "pm grant " + PKG + " android.permission.BLUETOOTH_CONNECT",
                "pm grant " + PKG + " android.permission.WRITE_SECURE_SETTINGS",
                "pm grant " + PKG + " android.permission.ACCESS_FINE_LOCATION",
                "cmd notification allow_listener " + PKG + "/" + PKG + ".GuardRevival",
                "s=$(settings get secure enabled_accessibility_services); case \"$s\" in *HomeKeyService*) ;; null|\"\") settings put secure "
                        + "enabled_accessibility_services " + service + " ;; *) settings put secure enabled_accessibility_services "
                        + "\"$s:" + service + "\" ;; esac; settings put secure accessibility_enabled 1",
                // TCL firmware (TclAppBoot) kills apps that are not allowed to start by themselves.
                "cmd appops set " + PKG + " AUTO_START allow; cmd appops set " + PKG + " APP_AUTO_START allow; appops write-settings",
                "cmd package set-home-activity --user 0 " + PKG + "/.MainActivity"};
        for (String command : setup) {
            if (dry) say("[ניסיון] " + command.split(";")[0], "dry");
            else sh(command);
        }
        if (!dry) {
            String now = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1");
            String stock = now.split("/")[0].trim();
            if (now.contains(PKG)) say("מסך הבית החדש פעיל", "ok");
            else if (stock.matches("^[a-z][a-zA-Z0-9_.]*[.][a-zA-Z0-9_.]+$")) {
                // Google TV (TCL and others) keeps its own Home at a higher priority, whatever set-home-activity says:
                // instead of disabling it (on TCL its setup app also owns the Wi-Fi), the Home key helper sends it back.
                redirect = stock;
                say("מסך הבית של היצרן (" + stock + ") נשאר ברירת המחדל: מסך הבית החדש יחזיר אותו אליו אוטומטית", "warn");
            } else throw new IllegalStateException("מסך הבית החדש לא נקבע כברירת מחדל (" + now + "). עוצרים כאן.");
        }

        progress = 36;
        stepTo("3. ערכות הנושא");
        filesDone = 0;
        filesTotal = 0;
        for (String slot : chosen) filesTotal += themeFiles(slot).size() + 1;
        filesTotal += themeFiles(def).size() + 1;
        for (String slot : chosen) {
            if (dry) say("[ניסיון] שולח את " + slot, "dry");
            else pushTheme(slot, slot, o, place);
        }
        if (dry) say("[ניסיון] " + def + " גם כערכה הראשית", "dry");
        else pushTheme(def, "", o, place);
        if (!dry) {
            if (!order.isEmpty()) put("order-import.txt", (order + "\n").getBytes(StandardCharsets.UTF_8), FILES, URI);
            restartHome();
            backInFront();
            sh(VOLUME_WATCHER, 20_000, 1);   // holding a volume key repeats quickly (the launcher's VolumeHold)
        }
        progress = 92;

        stepTo("4. השבתת אפליקציות (לא מחיקה)");
        JSONArray lists = o.optJSONArray("lists");
        if (lists != null && lists.length() > 0 && !isFormovie()) {
            // The lists were written for the Formovie projector; on other TVs the same names can be the Wi-Fi setup.
            say("רשימות ההשבתה נכתבו למקרן Formovie: בדגם הזה לא משביתים כלום", "warn");
            lists = null;
        }
        for (int i = 0; lists != null && i < lists.length(); i++) {
            String file = lists.getString(i);
            if (file.startsWith("3-") && !redirect.isEmpty()) {
                say("מסך הבית של היצרן נשאר ברירת המחדל: לא משביתים אותו", "warn");
                continue;
            }
            List<String> packages = new ArrayList<>();
            for (String[] p : listPackages(file)) packages.add(p[0]);
            if (file.startsWith("3-") && sh("dumpsys wifi | grep -o 'cname=[^ ]*' | head -3").contains("setupwraith")) {
                packages.removeIf(p -> p.contains("setupwraith"));
                say("setupwraith מחזיק את רשת ה-Wi-Fi: הוא נשאר פעיל", "warn");
            }
            write(new File(backup, file), String.join("\n", packages) + "\n");
            for (String p : packages) {
                if (PROTECTED.matcher(p).matches()) {
                    say(p + " מוגן, לא מושבת", "warn");
                    continue;
                }
                if (dry) say("[ניסיון] משבית " + p, "dry");
                else say(p + ": " + sh("pm disable-user --user 0 " + p), "info");
            }
            state.getJSONArray("lists").put(file);
            write(new File(backup, "state.json"), state.toString(2));
        }

        progress = 95;
        stepTo("5. שומר מסך ו-DNS");
        if (o.optBoolean("screensaver", true)) {
            if (dry) say("[ניסיון] שומר המסך שלנו", "dry");
            else sh("settings put secure screensaver_components " + PKG + "/.ClockDream; settings put secure screensaver_enabled 1");
            if (o.optBoolean("block_google", true)) {
                String dreamx = "com.google.android.apps.tv.dreamx";
                if (dry) say("[ניסיון] משבית את שומר המסך של Google", "dry");
                else sh("pm disable-user --user 0 " + dreamx);
                write(new File(backup, "4-google-screensaver.txt"), dreamx + "\n");
                state.getJSONArray("lists").put("4-google-screensaver.txt");
                write(new File(backup, "state.json"), state.toString(2));
            }
        }
        if (o.optBoolean("dns")) {
            if (dry) say("[ניסיון] חוסם פרסומות", "dry");
            else sh("settings put global private_dns_specifier dns.adguard-dns.com; settings put global private_dns_mode hostname");
        }
        if (!dry && setup().getBoolean("saved", false)) {
            // An update: the launcher started clean, so the name, place and photos from last time go back on.
            progress = 98;
            stepTo("6. השם, המקום והתמונות מהפעם הקודמת");
            applySetup();
        }
        // The install screen stays in front: it goes on to the setup (name, place, photos), which opens Home at the end.
        if (!dry) backInFront();
    }

    /** A theme from the assets onto the projector, with the owner's details; slot "" is the base theme. */
    private void pushTheme(String slot, String target, JSONObject o, JSONObject place) throws Exception {
        String base = FILES + (target.isEmpty() ? "" : "/themes/" + target);
        String uri = URI + (target.isEmpty() ? "" : "/themes/" + target);
        // Folders the shell made can't be written by the launcher on Android 12 (EACCES): they go, and the launcher
        // makes its own on the first file. Everything in them is sent again below.
        String stale = base + "/wallpapers " + base + "/art" + (target.isEmpty() ? "" : " " + FILES + "/themes " + base);
        if (direct) {
            try {
                context.getContentResolver().delete(themeUri((target.isEmpty() ? "" : "themes/" + target + "/") + "wallpapers"), null, null);
            } catch (Exception ignored) {
            }
        } else {
            sh("for d in " + stale + "; do [ -d \"$d\" ] && [ \"$(stat -c %U \"$d\")\" = shell ] && rm -rf \"$d\"; done; "
                    + "rm -f " + base + "/wallpapers/* " + base + "/art/* 2>/dev/null; content delete --uri " + uri + "/wallpapers 2>/dev/null; true");
        }
        List<String> items = themeFiles(slot);
        String prefix = target.isEmpty() ? "" : "themes/" + target + "/";
        for (String rel : items) {
            byte[] data = read("library/" + slot + "/" + rel);
            if (rel.equals("config.json")) data = personalize(data, o, place);
            put(prefix + rel, data, base, uri);
            themeFileDone();
        }
        put(prefix + "theme_revision", UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8), base, uri);
        themeFileDone();
        say("   " + (target.isEmpty() ? "ראשית" : target) + ": " + (items.size() + 1) + " קבצים", "info");
    }

    // ---- the fast route: the launcher's theme provider, straight from this app ------------------------------
    /** This app holds WRITE_SECURE_SETTINGS (granted over ADB), so it may use the launcher's theme provider itself. */
    private volatile boolean direct;

    private android.net.Uri themeUri(String path) {
        return android.net.Uri.parse(URI + "/" + path);
    }

    /** One theme file: through the provider when allowed (a local call, milliseconds), else through ADB. */
    private void put(String path, byte[] data, String base, String uri) throws Exception {
        if (direct) {
            try {
                providerWrite(path, data);
                return;
            } catch (Exception e) {
                say("כתיבה ישירה נכשלה (" + e.getMessage() + "), עובר ל-ADB", "warn");
                direct = false;
            }
        }
        String rel = path.startsWith("themes/") ? path.substring(path.indexOf('/', 7) + 1) : path;
        pushBytes(data, TMP);
        sh(writeCommand(base, uri, rel));
    }

    private void providerWrite(String path, byte[] data) throws Exception {
        try (OutputStream out = context.getContentResolver().openOutputStream(themeUri(path), "wt")) {
            if (out == null) throw new IOException("no provider");
            out.write(data);
        }
    }

    private byte[] providerRead(String path) {
        try (InputStream in = context.getContentResolver().openInputStream(themeUri(path))) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean providerHas(String path) {
        try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(themeUri(path), "r")) {
            return fd != null && fd.getStatSize() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** A theme's files in the assets: its settings, fonts and sound, then its wallpapers and art. */
    private List<String> themeFiles(String slot) throws IOException {
        List<String> items = new ArrayList<>();
        for (String name : THEME_FILES) if (exists("library/" + slot + "/" + name)) items.add(name);
        for (String sub : new String[]{"wallpapers", "art"}) {
            String[] names = assets.list("library/" + slot + "/" + sub);
            if (names != null) for (String n : names) items.add(sub + "/" + listed(n));
        }
        return items;
    }

    /** One theme file sent: the bar moves through 36 to 90 while the themes go on. */
    private void themeFileDone() {
        filesDone++;
        if (filesTotal > 0) progress = Math.max(progress, 36 + Math.min(54, filesDone * 54 / filesTotal));
    }

    private static String writeCommand(String base, String uri, String rel) {
        return writeFrom(base, uri, rel, TMP) + "; rm -f " + TMP;
    }

    /** The file {@code src} on the TV into a theme, through the launcher's provider (else a plain copy); src is kept. */
    private static String writeFrom(String base, String uri, String rel, String src) {
        return "rm -f " + base + "/" + rel + " 2>/dev/null; { o=$(content write --uri " + uri + "/" + rel + " < " + src
                + " 2>&1); case \"$o\" in *Exception*|*Error*|*'Unsupported operation'*) false ;; esac; } || { mkdir -p "
                + (base + "/" + rel).replaceAll("/[^/]*$", "") + " 2>/dev/null; cp " + src + " " + base + "/" + rel + "; }";
    }

    /** stock_home_redirect: the stock launcher that kept Home (Google TV), or "" when ours is Home. */
    private String redirect = "";

    /** TCL mode (same as the PC wizard's TCL_PROFILE): the TCL remote's gear and YouTube keys, Live TV, TCL extras hidden.
     *  Netflix, Prime Video, Disney+, Input and the mic are handled by TCL's own firmware. */
    private boolean tcl;
    private static final String[][] TCL_PROFILE = {
            {"settings_key_keycodes", "83"},
            {"app_keys", "190:com.google.android.youtube.tv", "4065:com.google.android.youtube.tv"},
            {"hidden_apps", "com.tcl.dashboard", "com.tcl.partnercustomizer", "com.tcl.suspension"},
            {"app_labels", "com.tcl.tv=Live TV"}};

    private static void tclMode(JSONObject cfg) throws Exception {
        for (String[] entry : TCL_PROFILE) {
            JSONArray have = cfg.optJSONArray(entry[0]);
            if (have == null) have = new JSONArray();
            for (int i = 1; i < entry.length; i++) {
                Object value = entry[0].equals("settings_key_keycodes") ? (Object) Integer.parseInt(entry[i]) : entry[i];
                boolean found = false;
                for (int j = 0; j < have.length(); j++) if (String.valueOf(have.get(j)).equals(entry[i])) found = true;
                if (!found) have.put(value);
            }
            cfg.put(entry[0], have);
        }
        cfg.put("live_tv_package", "com.tcl.tv");
    }

    private boolean isFormovie() throws Exception {
        return sh("getprop ro.product.model; getprop ro.product.device").matches("(?is).*(C045|Formovie|tianshan).*");
    }

    private byte[] personalize(byte[] data, JSONObject o, JSONObject place) throws Exception {
        JSONObject cfg = new JSONObject(new String(data, StandardCharsets.UTF_8));
        for (String key : PERSONAL) cfg.remove(key);
        cfg.put("greeting_name", o.optString("name")).put("greeting_name_he", o.optString("name_he"))
                .put("own_screensaver", o.optBoolean("screensaver", true)).put("keep_key_helper", true);
        cfg.remove("home_idle_clock_minutes");
        if (tcl) tclMode(cfg);
        if (redirect.isEmpty()) cfg.remove("stock_home_redirect");
        else cfg.put("stock_home_redirect", redirect);
        if (place != null) {
            cfg.put("weather_lat", place.optDouble("lat")).put("weather_lon", place.optDouble("lon"))
                    .put("location_label", place.optString("en")).put("location_label_he", place.optString("he"));
        }
        return cfg.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    private void restartHome() throws Exception {
        String services = sh("settings get secure enabled_accessibility_services");
        StringBuilder others = new StringBuilder();
        for (String s : services.split(":")) {
            if (s.isEmpty() || s.equals("null") || s.contains("HomeKeyService")) continue;
            if (others.length() > 0) others.append(':');
            others.append(s);
        }
        sh("am force-stop " + PKG);
        String full = (others.length() > 0 ? others + ":" : "") + PKG + "/.HomeKeyService";
        sh("settings put secure enabled_accessibility_services " + full + "; settings put secure accessibility_enabled 1");
        // With Google TV's Home kept, a Home intent would show launcherx: ours opens directly.
        // Decided by what Android says now: the redirect field is lost if this app restarted since the install.
        String home = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1");
        if (redirect.isEmpty() && home.contains(PKG)) sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        else sh("am start -n " + PKG + "/.MainActivity");
    }

    /** Back to before the install, from the latest backup: apps on again, the old Home, screensaver and DNS. */
    private void restore() throws Exception {
        if (!Adb.get(context).connectSelf()) throw new IllegalStateException("לא מחובר: קודם לצמד עם הקוד");
        File backup = latestBackup();
        if (backup == null) throw new IllegalStateException("אין גיבוי במקרן הזה");
        stepTo("מחזיר מהגיבוי " + backup.getName());
        JSONObject state = new JSONObject(new String(Files.readAllBytes(new File(backup, "state.json").toPath()), StandardCharsets.UTF_8));
        String before = new String(Files.readAllBytes(new File(backup, "disabled-before.txt").toPath()), StandardCharsets.UTF_8);
        JSONArray lists = state.optJSONArray("lists");
        for (int i = 0; lists != null && i < lists.length(); i++) {
            File list = new File(backup, lists.getString(i));
            if (!list.isFile()) continue;
            for (String p : new String(Files.readAllBytes(list.toPath()), StandardCharsets.UTF_8).split("\n")) {
                p = p.trim();
                if (p.isEmpty() || before.contains("package:" + p + "\n") || before.endsWith("package:" + p)) continue;
                say(p + ": " + sh("pm enable " + p), "info");
            }
        }
        String saver = state.optString("screensaver");
        if (!saver.isEmpty() && !saver.equals("null")) sh("settings put secure screensaver_components " + saver);
        String[] dns = state.optString("dns").split("\\|", -1);
        if (dns.length > 0 && !dns[0].isEmpty() && !dns[0].equals("null")) sh("settings put global private_dns_mode " + dns[0]);
        else sh("settings delete global private_dns_mode");
        if (dns.length > 1 && !dns[1].isEmpty() && !dns[1].equals("null")) sh("settings put global private_dns_specifier " + dns[1]);
        String home = state.optString("home");
        if (home.contains("/") && !home.contains(PKG)) sh("cmd package set-home-activity --user 0 " + home);
    }

    /**
     * The red button: our launcher off the TV and the TV as it was. From this installer's backup when it has one
     * (the apps it switched off back on, the old screensaver, DNS and home); without one, the TV's own home, setup and
     * screensaver apps are switched on again. Then our Settings-button helper is taken off the accessibility list,
     * the launcher is uninstalled and the TV's own home is made the home. Nothing else of the TV is touched.
     */
    private void removeAll() throws Exception {
        if (!connectNow()) throw new IllegalStateException("לא מחובר לטלוויזיה");
        progress = 5;
        stepTo("מסיר את מסך הבית ומחזיר את הטלוויזיה למצב המקורי");
        if (latestBackup() != null) {
            try {
                restore();
            } catch (Exception e) {
                say("הגיבוי לא הוחזר במלואו: " + e.getMessage(), "warn");
            }
        }
        progress = 35;
        // Whatever the backup says, the TV's own home, its setup app and Google's screensaver are on again.
        for (String p : new String[]{"com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
                "com.google.android.tungsten.setupwraith", "com.google.android.apps.tv.dreamx"}) {
            if (sh("pm list packages " + p).contains("package:" + p)) sh("pm enable " + p);
        }
        String saver = sh("settings get secure screensaver_components");
        if (saver.contains(PKG)) sh("settings delete secure screensaver_components");
        progress = 55;
        String services = sh("settings get secure enabled_accessibility_services");
        StringBuilder others = new StringBuilder();
        for (String s : services.split(":")) {
            if (s.isEmpty() || s.equals("null") || s.startsWith(PKG + "/")) continue;
            if (others.length() > 0) others.append(':');
            others.append(s);
        }
        sh(others.length() > 0 ? "settings put secure enabled_accessibility_services " + others
                : "settings delete secure enabled_accessibility_services");
        progress = 70;
        String result = sh("pm uninstall " + PKG, 60_000);
        say("מסך הבית: " + (result.contains("Success") ? "הוסר" : result), result.contains("Success") ? "ok" : "warn");
        progress = 85;
        // The TV's own home becomes the home again (the first HOME activity that is not ours or a setup screen).
        for (String line : sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME").split("\n")) {
            String home = line.trim();
            if (home.contains("/") && !home.contains(PKG) && !home.contains("setup") && !home.contains("Settings")) {
                sh("cmd package set-home-activity --user 0 " + home);
                break;
            }
        }
        // Our saved name, place and photos go too: an install after this starts fresh.
        setup().edit().clear().commit();
        String[] photos = photoDir().list();
        if (photos != null) for (String n : photos) new File(photoDir(), n).delete();
        sh("input keyevent HOME");
    }

    private File latestBackup() {
        File root = new File(context.getExternalFilesDir(null), "backups");
        File[] all = root.listFiles(File::isDirectory);
        if (all == null || all.length == 0) return null;
        Arrays.sort(all);
        return all[all.length - 1];
    }

    // ---- after the install: the owner's name, place and photos --------------------------------------------------
    // The TV's setup screens (MainActivity) ask for the name and the place; the phone's photo page (www/photos.html,
    // reached by the QR code) sends pictures. Everything is kept in this app as well, so the next update from the
    // stick (which starts the launcher clean) puts the same name, place and photos back by itself.

    private static final String SETUP = "setup";
    /** Serialises the setup's work on the TV (photos arrive from the phone while the TV screen waits). */
    private final Object work = new Object();

    SharedPreferences setup() {
        return context.getSharedPreferences(SETUP, Context.MODE_PRIVATE);
    }

    private File photoDir() {
        File dir = new File(context.getFilesDir(), "photos");
        if (!dir.isDirectory() && !dir.mkdirs()) return context.getFilesDir();
        return dir;
    }

    int photoCount() {
        String[] names = photoDir().list((d, n) -> n.endsWith(".jpg"));
        return names == null ? 0 : names.length;
    }

    /**
     * Where the phone's photos go: the launcher's own photos theme, themes/images ("Images" / "תמונות"), which Home
     * makes from the Apple theme the first time it finds photos there (ImagesTheme in the launcher).
     */
    private static final List<String> PHOTO_THEMES = java.util.Collections.singletonList("images");

    /** The launcher's theme folders on the TV: the base one ("") and every themes/NAME. */
    private List<String> targets() throws Exception {
        List<String> out = new ArrayList<>();
        out.add("");
        for (String n : sh("ls -1 " + FILES + "/themes 2>/dev/null").split("\n")) {
            n = n.trim();
            if (n.matches("[A-Za-z0-9_.-]+")) out.add(n);
        }
        return out;
    }

    /** A picture from the phone: checked, made at most 1920x1080, kept here, and put in every theme's wallpapers. */
    void addPhoto(byte[] data) throws Exception {
        if (data == null || data.length == 0 || data.length > 16_000_000) throw new IllegalArgumentException("התמונה גדולה מדי (עד 16MB)");
        data = toWallpaper(data);
        synchronized (work) {
            String name = String.format(Locale.ROOT, "90-photo-%d.jpg", System.currentTimeMillis());
            Files.write(new File(photoDir(), name).toPath(), data);
            if (done && connectNow()) {
                pushPhoto(PHOTO_THEMES, name, data);
                say("תמונה נוספה: " + name, "ok");
            }
        }
    }

    /** A photo to these themes: sent to the TV once, then put into each theme from there. */
    /**
     * Any picture the TV can read (JPEG, PNG, WebP, and HEIC/HEIF from iPhones and Samsungs, which phone browsers often
     * cannot open themselves) made into a 1920x1080 JPEG, cropped from the middle as a wallpaper fills the screen.
     * Already 1920x1080 JPEG (the page did it): sent on as it is.
     */
    static byte[] toWallpaper(byte[] data) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("הטלוויזיה לא מצליחה לקרוא את התמונה הזאת");
        boolean jpeg = data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
        if (jpeg && bounds.outWidth == 1920 && bounds.outHeight == 1080) return data;
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1920 && bounds.outHeight / (opts.inSampleSize * 2) >= 1080) opts.inSampleSize *= 2;
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) throw new IllegalArgumentException("הטלוויזיה לא מצליחה לקרוא את התמונה הזאת");
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888);
        float k = Math.max(1920f / src.getWidth(), 1080f / src.getHeight());
        float w = src.getWidth() * k, h = src.getHeight() * k;
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawBitmap(src, null, new android.graphics.RectF((1920 - w) / 2, (1080 - h) / 2, (1920 + w) / 2, (1080 + h) / 2),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, bytes);
        out.recycle();
        return bytes.toByteArray();
    }

    private void pushPhoto(List<String> targets, String name, byte[] data) throws Exception {
        if (targets.isEmpty()) return;
        if (direct) {
            for (String target : targets) providerWrite((target.isEmpty() ? "" : "themes/" + target + "/") + "wallpapers/" + name, data);
            return;
        }
        String src = "/data/local/tmp/kit-photo";
        pushBytes(data, src);
        for (String target : targets) {
            String base = FILES + (target.isEmpty() ? "" : "/themes/" + target);
            String uri = URI + (target.isEmpty() ? "" : "/themes/" + target);
            sh(writeFrom(base, uri, "wallpapers/" + name, src));
        }
        sh("rm -f " + src);
    }

    /**
     * The setup's answers into every theme on the TV: the greeting in English and Hebrew, the place and its weather,
     * and the phone's photos in the rotation (in every time-of-day group, so they show all day). Then Home restarts.
     */
    void finishSetup(String nameEn, String nameHe, String placeEn, String placeHe, double lat, double lon) throws Exception {
        synchronized (work) {
            setup().edit().putString("name_en", nameEn).putString("name_he", nameHe).putString("place_en", placeEn)
                    .putString("place_he", placeHe).putString("lat", String.valueOf(lat)).putString("lon", String.valueOf(lon))
                    .putBoolean("saved", true).commit();
            say("שומר את השם, המקום והתמונות…", "step");
            try {
                if (!connectNow()) throw new IllegalStateException("לא מחובר לטלוויזיה");
                applySetup();
            } catch (Exception e) {
                say("לא נשמר: " + (e.getMessage() == null ? e.toString() : e.getMessage()), "error");
                throw e;
            } finally {
                flushLog();
            }
        }
    }

    /** Puts the saved answers and photos on the TV (after the setup screens, and again after an update). */
    private void applySetup() throws Exception {
        SharedPreferences p = setup();
        direct = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String[] photos = photoDir().list((d, n) -> n.endsWith(".jpg"));
        if (photos == null) photos = new String[0];
        Arrays.sort(photos);
        List<String> themes = targets();
        themes.remove("images");   // made by Home from the Apple theme, with the name and place already in it
        for (String photo : photos) {
            List<String> missing = new ArrayList<>();
            for (String t : PHOTO_THEMES) {
                String base = FILES + (t.isEmpty() ? "" : "/themes/" + t);
                boolean has = direct ? providerHas((t.isEmpty() ? "" : "themes/" + t + "/") + "wallpapers/" + photo)
                        : sh("ls " + base + "/wallpapers/" + photo + " 2>/dev/null").contains(photo);
                if (!has) missing.add(t);
            }
            if (!missing.isEmpty()) {
                step = "התמונות שלך: " + photo;
                pushPhoto(missing, photo, Files.readAllBytes(new File(photoDir(), photo).toPath()));
            }
        }
        for (String t : themes) {
            String base = FILES + (t.isEmpty() ? "" : "/themes/" + t);
            String uri = URI + (t.isEmpty() ? "" : "/themes/" + t);
            step = "ההגדרות שלך: " + (t.isEmpty() ? "ראשית" : t);
            String prefix = t.isEmpty() ? "" : "themes/" + t + "/";
            byte[] read = direct ? providerRead(prefix + "config.json") : null;
            String text = read != null ? new String(read, StandardCharsets.UTF_8) : sh("cat " + base + "/config.json 2>/dev/null");
            if (!text.trim().startsWith("{")) continue;
            JSONObject cfg = new JSONObject(text);
            if (!p.getString("name_en", "").isEmpty()) cfg.put("greeting_name", p.getString("name_en", ""));
            if (!p.getString("name_he", "").isEmpty()) cfg.put("greeting_name_he", p.getString("name_he", ""));
            if (!p.getString("place_en", "").isEmpty()) {
                cfg.put("location_label", p.getString("place_en", "")).put("location_label_he", p.getString("place_he", ""))
                        .put("weather_lat", Double.parseDouble(p.getString("lat", "0")))
                        .put("weather_lon", Double.parseDouble(p.getString("lon", "0")));
            }
            put(prefix + "config.json", cfg.toString(2).getBytes(StandardCharsets.UTF_8), base, uri);
            put(prefix + "theme_revision", UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8), base, uri);
        }
        restartHome();
        say("השם, המקום והתמונות נשמרו", "ok");
    }

    // ---- files ----------------------------------------------------------------------------------------------

    /** Bytes into a file on the TV through the shell user, so the shell can read them back. */
    private void pushBytes(byte[] data, String remote) throws Exception {
        pushBytes(data, remote, -1, -1);
    }

    /**
     * As pushBytes; with from/to >= 0 the bar moves from {@code from} to {@code to} as the bytes go.
     * <p>
     * The file goes over in pieces of 32 KB, each one a short shell command ({@code echo <base64> | base64 -d >>}),
     * not through one long stream: a long stream could lock up the whole connection (seen on a TCL: 0 bytes arrived and
     * nothing after it answered). Every piece has its own time limit and up to 3 tries on a fresh connection, and the
     * size on the TV is checked at the end; the whole file is sent again (up to 3 times) if it is not right.
     */
    /** This TV takes 192 KB pieces (until one fails). */
    private volatile boolean bigPieces = false;   // 192 KB pieces were refused on a Formovie (10 s lost): 32 KB from the start

    private void pushBytes(byte[] data, String remote, int from, int to) throws Exception {
        String problem = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            // 192 KB pieces where the TV takes them (fewer commands, much faster); 32 KB after a failure.
            final int piece = bigPieces ? 192 * 1024 : 32 * 1024;
            try {
                sh("rm -f " + remote + "; : > " + remote, 20_000);
                for (int at = 0; at < data.length; at += piece) {
                    int n = Math.min(piece, data.length - at);
                    String b64 = android.util.Base64.encodeToString(data, at, n, android.util.Base64.NO_WRAP);
                    sh("echo '" + b64 + "' | base64 -d >> " + remote, bigPieces ? 10_000 : 20_000, bigPieces ? 1 : 3);
                    if (from >= 0) progress = from + (int) ((long) (to - from) * (at + n) / Math.max(1, data.length));
                }
                String size = sh("stat -c %s " + remote + " 2>/dev/null", 20_000).trim();
                if (size.equals(String.valueOf(data.length))) {
                    if (from >= 0) progress = to;
                    return;
                }
                problem = "הגיעו " + size + " מתוך " + data.length + " בתים";
            } catch (IllegalStateException e) {
                problem = e.getMessage();
            }
            if (bigPieces) {
                bigPieces = false;   // this TV wants smaller pieces: the same file again at once, small
                Adb.fresh(context);
                continue;
            }
            if (attempt < 3) {
                say("העתקה לא הצליחה (" + problem + "), מתחבר מחדש ומנסה שוב", "warn");
                Adb.fresh(context);
            }
        }
        throw new IllegalStateException("העתקת קובץ לטלוויזיה לא הצליחה 3 פעמים (" + problem + ")");
    }

    private void pushAsset(String asset, String remote, int from, int to) throws Exception {
        pushBytes(read(asset), remote, from, to);
    }

    List<String> slots() throws Exception {
        List<String> out = new ArrayList<>();
        try {
            JSONArray order = new JSONObject(new String(read("library/index.json"), StandardCharsets.UTF_8)).getJSONArray("slots");
            for (int i = 0; i < order.length(); i++) if (exists("library/" + order.getString(i) + "/config.json")) out.add(order.getString(i));
        } catch (IOException e) {
            String[] names = assets.list("library");
            if (names != null) for (String n : names) if (exists("library/" + n + "/config.json")) out.add(n);
        }
        return out;
    }

    private byte[] preview(String slot) throws Exception {
        if (slot == null || !slots().contains(slot)) return null;
        String[] names = assets.list("library/" + slot + "/wallpapers");
        if (names == null) return null;
        Arrays.sort(names);
        for (String n : names) {
            String name = listed(n);
            if (!name.endsWith(".fg.png")) return read("library/" + slot + "/wallpapers/" + name);
        }
        return null;
    }

    private List<String[]> listPackages(String file) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : new String(read("debloat/" + file), StandardCharsets.UTF_8).split("\n")) {
            int hash = line.indexOf('#');
            String name = (hash >= 0 ? line.substring(0, hash) : line).trim();
            String note = hash >= 0 ? line.substring(hash + 1).trim() : "";
            if (PACKAGE.matcher(name).matches()) out.add(new String[]{name, note});
        }
        return out;
    }

    /** A file in the assets, or its NAME.same pointer (pack.py keeps each shared file once). */
    private boolean exists(String path) {
        for (String p : new String[]{path, path + SAME}) {
            try (InputStream ignored = assets.open(p)) {
                return true;
            } catch (IOException e) {
                // not this one
            }
        }
        return false;
    }

    /** pack.py stores a file that several themes share once; the other copies are NAME.same, naming it. */
    static final String SAME = ".same";

    /** A name from assets.list without the .same of a shared file's pointer. */
    static String listed(String name) {
        return name.endsWith(SAME) ? name.substring(0, name.length() - SAME.length()) : name;
    }

    private byte[] read(String path) throws IOException {
        try {
            return readAsset(path);
        } catch (java.io.FileNotFoundException notHere) {
            // a shared file: this copy points at the one stored
            String target = new String(readAsset(path + SAME), StandardCharsets.UTF_8).trim();
            return readAsset(target);
        }
    }

    private byte[] readAsset(String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}

package com.gathin.kit;

import android.content.Context;
import android.content.res.AssetManager;
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
                .put("step", step).put("log", lines);
    }

    // ---- the run --------------------------------------------------------------------------------------------

    private synchronized void start(JSONObject options, boolean restore) {
        if (running) throw new IllegalStateException("כבר רץ");
        running = true;
        done = false;
        error = null;
        log.clear();
        new Thread(() -> {
            try {
                if (restore) restore();
                else install(options);
                done = true;
                say(restore ? "המקרן הוחזר למצב שלפני ההתקנה" : "ההתקנה הסתיימה", "ok");
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                say(error, "error");
            } finally {
                running = false;
            }
        }, "kit-run").start();
    }

    synchronized void say(String text, String kind) {
        log.add(new String[]{new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()), kind, text});
    }

    private void stepTo(String name) throws Exception {
        step = name;
        say(name, "step");
        sh("input keyevent WAKEUP");   // the projector sleeps after 10 idle minutes and drops off Wi-Fi
    }

    private String sh(String command) throws Exception {
        return Adb.get(context).sh(command);
    }

    private void install(JSONObject o) throws Exception {
        boolean dry = o.optBoolean("dry");
        redirect = "";
        tcl = sh("getprop ro.product.brand; getprop ro.product.manufacturer").matches("(?is).*\\bTCL\\b.*");
        if (tcl) say("מצב TCL: השלט של TCL (גלגל השיניים, YouTube) ומסך הבית של Google TV מסודרים לבד", "ok");
        Adb adb = Adb.get(context);
        if (!adb.connectSelf()) throw new IllegalStateException("לא מחובר: קודם לצמד עם הקוד (שלב 1)");
        List<String> all = slots();
        List<String> chosen = new ArrayList<>();
        JSONArray picked = o.optJSONArray("themes");
        for (int i = 0; picked != null && i < picked.length(); i++) if (all.contains(picked.getString(i))) chosen.add(picked.getString(i));
        if (chosen.isEmpty()) chosen.addAll(all);
        String def = chosen.contains(o.optString("default")) ? o.optString("default") : chosen.get(0);
        JSONObject place = o.optJSONObject("place");

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

        stepTo("2. התקנת מסך הבית");
        if (dry) say("[ניסיון] מתקין את מסך הבית", "dry");
        else {
            pushAsset("launcher.apk", "/data/local/tmp/kit.apk");
            String result = sh("pm install -r -g /data/local/tmp/kit.apk; rm -f /data/local/tmp/kit.apk");
            if (!result.contains("Success")) throw new IllegalStateException("ההתקנה נכשלה: " + result);
        }
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

        stepTo("3. ערכות הנושא");
        for (String slot : chosen) {
            if (dry) say("[ניסיון] שולח את " + slot, "dry");
            else pushTheme(slot, slot, o, place);
        }
        if (dry) say("[ניסיון] " + def + " גם כערכה הראשית", "dry");
        else pushTheme(def, "", o, place);
        if (!dry) restartHome();

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
        if (!dry) sh("input keyevent HOME");
    }

    /** A theme from the assets onto the projector, with the owner's details; slot "" is the base theme. */
    private void pushTheme(String slot, String target, JSONObject o, JSONObject place) throws Exception {
        String base = FILES + (target.isEmpty() ? "" : "/themes/" + target);
        String uri = URI + (target.isEmpty() ? "" : "/themes/" + target);
        sh("mkdir -p " + base + "/wallpapers " + base + "/art; rm -f " + base + "/wallpapers/* " + base + "/art/* 2>/dev/null; "
                + "content delete --uri " + uri + "/wallpapers 2>/dev/null");
        List<String> items = new ArrayList<>();
        for (String name : THEME_FILES) if (exists("library/" + slot + "/" + name)) items.add(name);
        for (String sub : new String[]{"wallpapers", "art"}) {
            String[] names = assets.list("library/" + slot + "/" + sub);
            if (names != null) for (String n : names) items.add(sub + "/" + n);
        }
        for (String rel : items) {
            byte[] data = read("library/" + slot + "/" + rel);
            if (rel.equals("config.json")) data = personalize(data, o, place);
            pushBytes(data, TMP);
            sh(writeCommand(base, uri, rel));
        }
        pushBytes(UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8), TMP);
        sh(writeCommand(base, uri, "theme_revision"));
        say("   " + (target.isEmpty() ? "ראשית" : target) + ": " + (items.size() + 1) + " קבצים", "info");
    }

    private static String writeCommand(String base, String uri, String rel) {
        return "rm -f " + base + "/" + rel + " 2>/dev/null; { o=$(content write --uri " + uri + "/" + rel + " < " + TMP
                + " 2>&1) && case \"$o\" in *'Unsupported operation'*) false ;; esac; } || cp " + TMP + " " + base + "/" + rel
                + "; rm -f " + TMP;
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
        if (redirect.isEmpty()) sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
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

    private File latestBackup() {
        File root = new File(context.getExternalFilesDir(null), "backups");
        File[] all = root.listFiles(File::isDirectory);
        if (all == null || all.length == 0) return null;
        Arrays.sort(all);
        return all[all.length - 1];
    }

    // ---- files ----------------------------------------------------------------------------------------------

    /** Bytes into a file on the projector through the shell user (exec:cat), so the shell can read them back. */
    private void pushBytes(byte[] data, String remote) throws Exception {
        AdbStream stream = Adb.get(context).openStream("exec:cat > " + remote);
        try (OutputStream out = stream.openOutputStream()) {
            out.write(data);
            out.flush();
        }
        try (InputStream in = stream.openInputStream()) {
            while (in.read() >= 0) {
                // wait until cat is done and the stream closes
            }
        } catch (IOException ignored) {
        }
        stream.close();
    }

    private void pushAsset(String asset, String remote) throws Exception {
        pushBytes(read(asset), remote);
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
        for (String n : names) if (!n.endsWith(".fg.png")) return read("library/" + slot + "/wallpapers/" + n);
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

    private boolean exists(String path) {
        try (InputStream ignored = assets.open(path)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private byte[] read(String path) throws IOException {
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

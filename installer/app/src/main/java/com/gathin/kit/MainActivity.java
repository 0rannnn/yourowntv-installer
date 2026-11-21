package com.gathin.kit;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * The TV's screen while the kit runs. One big button, "Install now": the kit connects to this TV's own ADB and puts
 * everything on by itself, with a bar from 1 to 100. The first time, the TV has to allow the connection once: if it
 * is not allowed yet, the screen shows the QR code for the phone (served by WebServer on this TV), which pairs it with
 * the code from Wireless debugging, and the install then starts by itself.
 */
public class MainActivity extends Activity {
    private static WebServer server;
    private static Kit kit;
    /** "Install now" was pressed but the TV is not connected yet: start as soon as it is (after the phone pairs). */
    private static volatile boolean waiting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, percent, title;
    private ProgressBar bar;
    private View barRow;
    private Button go, remove, update;
    private LinearLayout pairing;
    private volatile boolean checking;
    private long lastCheck;
    /** The value the bar is sliding to, 0 to 100; the bar itself counts in tenths so it glides. */
    private int shown = -1;
    private ObjectAnimator slide;
    /** The setup after the install (name, place, photos) has been opened. */
    private static boolean setupStarted;
    /** Seconds before the update starts by itself on a TV that already has the launcher; 0 when none is pending. */
    private int autoLeft;
    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            if (autoLeft <= 0 || kit.running || kit.done || isFinishing()) return;
            status.setText("התקנה מלאה בעוד " + autoLeft + "…  (למטה: עדכון ששומר הכול, או הסרה)");
            if (--autoLeft == 0) {
                pressed();
                return;
            }
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * During the countdown any press but OK stops the update that was about to start (the first press on a TV may only
     * wake the focus, so focus alone is not enough); Down goes straight to the red button.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        if (autoLeft > 0 && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && key != android.view.KeyEvent.KEYCODE_DPAD_CENTER && key != android.view.KeyEvent.KEYCODE_ENTER) {
            autoLeft = 0;
            handler.removeCallbacks(countdown);
            status.setText("ההתקנה המלאה בוטלה. הכחול מעדכן ושומר הכול, \"התקן עכשיו\" מתקין מחדש, האדום מסיר.");
            if (key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                (update.getVisibility() == View.VISIBLE ? update : remove).requestFocus();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Whether our launcher is on this TV already (then opening this app updates it, after a short countdown). */
    private boolean launcherInstalled() {
        try {
            getPackageManager().getPackageInfo("com.gathin.tvhome", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private String nameEn, nameHe, placeEn, placeHe;
    private double lat, lon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (kit == null) kit = new Kit(getApplicationContext());
        if (server == null) {
            server = new WebServer(kit);
            try {
                server.start();
            } catch (Exception e) {
                server = null;
            }
        }
        String url = "http://" + Adb.ownAddress(this) + ":" + WebServer.PORT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101218);
        root.setPadding(px(80), px(40), px(80), px(40));

        title = line("התקנת מסך הבית", 36, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = line("הכול מותקן לבד, תוך כמה שניות. לא לכבות את הטלוויזיה עד הסוף.", 18, 0xFFC9CDD6, false);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        go = new Button(this);
        go.setText("התקן עכשיו");
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setAllCaps(false);
        go.setTextColor(0xFF101218);
        go.setBackground(pill(0xFFFFFFFF));
        go.setPadding(px(48), px(14), px(48), px(14));
        go.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(150).start());
        go.setOnClickListener(v -> pressed());
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        goLp.topMargin = px(28);
        goLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(go, goLp);

        // "Update the current pack": only on a TV that already has our launcher. The newest launcher over the one
        // there, every setting of the owner's kept (Kit.updateOnly).
        update = new Button(this);
        update.setText("עדכון הערכה הנוכחית");
        update.setAllCaps(false);
        update.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        update.setTypeface(Typeface.DEFAULT_BOLD);
        update.setTextColor(Color.WHITE);
        GradientDrawable blue = pill(0xFF0A60D6);
        update.setBackground(blue);
        update.setPadding(px(40), px(12), px(40), px(12));
        update.setOnFocusChangeListener((v, focused) -> {
            if (focused && autoLeft > 0) {
                autoLeft = 0;
                handler.removeCallbacks(countdown);
                status.setText("ההתקנה המלאה בוטלה. \"עדכון הערכה הנוכחית\" מעדכן ושומר את כל ההגדרות.");
            }
            blue.setColor(focused ? 0xFF2F80FF : 0xFF0A60D6);
            blue.setStroke(px(3), focused ? Color.WHITE : 0x00FFFFFF);
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(150).start();
        });
        update.setOnClickListener(v -> updatePressed());
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        updateLp.topMargin = px(16);
        updateLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(update, updateLp);
        update.setVisibility(launcherInstalled() ? View.VISIBLE : View.GONE);

        // The bar: 1 to 100, green on a dim track, with the number beside it.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressDrawable(barDrawable());
        row.addView(bar, new LinearLayout.LayoutParams(0, px(14), 1f));
        percent = line("", 22, Color.WHITE, true);
        percent.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams percentLp = new LinearLayout.LayoutParams(px(110), LinearLayout.LayoutParams.WRAP_CONTENT);
        percentLp.leftMargin = px(16);
        row.addView(percent, percentLp);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(px(820), LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = px(32);
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(row, barLp);
        row.setVisibility(View.INVISIBLE);
        barRow = row;

        status = line("", 18, 0xFF30D158, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        // The red button: our home screen off this TV and the TV as it was (asks first).
        remove = new Button(this);
        remove.setText("הסרת הערכה לחלוטין");
        remove.setAllCaps(false);
        remove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        remove.setTypeface(Typeface.DEFAULT_BOLD);
        remove.setTextColor(Color.WHITE);
        GradientDrawable red = pill(0xFFD70015);
        remove.setBackground(red);
        remove.setPadding(px(36), px(10), px(36), px(10));
        remove.setOnFocusChangeListener((v, focused) -> {
            // Choosing the red button stops the update that was about to start by itself.
            if (focused && autoLeft > 0) {
                autoLeft = 0;
                handler.removeCallbacks(countdown);
                status.setText("העדכון בוטל. לחיצה על הכפתור האדום מסירה את הערכה.");
            }
            red.setColor(focused ? 0xFFFF3B30 : 0xFFD70015);
            red.setStroke(px(3), focused ? Color.WHITE : 0x00FFFFFF);
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(150).start();
        });
        remove.setOnClickListener(v -> confirmRemove());
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        removeLp.topMargin = px(26);
        removeLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(remove, removeLp);
        if (server == null) status.setText("לא הצלחתי לפתוח את השרת. לסגור את האפליקציה ולפתוח שוב.");

        // Only when the TV has not allowed this kit yet: the phone pairs it once.
        pairing = new LinearLayout(this);
        pairing.setOrientation(LinearLayout.HORIZONTAL);
        pairing.setGravity(Gravity.CENTER);
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.addView(line("הטלוויזיה עוד לא אישרה את החיבור", 22, Color.WHITE, true));
        steps.addView(line("הכי פשוט: הגדרות > מערכת > אפשרויות למפתחים > להדליק \"ניפוי באגים ב-USB\". "
                + "יקפוץ חלון \"לאפשר?\": לסמן \"אפשר תמיד\" ולאשר. ההתקנה מתחילה לבד.", 17, 0xFFC9CDD6, false));
        steps.addView(line("אם החלון לא קופץ (רק בחלק מהטלוויזיות): סורקים את הקוד בטלפון, ובטלוויזיה "
                + "ניפוי באגים אלחוטי > צימוד עם קוד, ומקלידים בטלפון את 6 הספרות.", 15, 0xFF9AA0AD, false));
        pairing.addView(steps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrCode(url, 512));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(px(210), px(210));
        qrLp.rightMargin = px(36);
        pairing.addView(qr, 0, qrLp);
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pairLp.topMargin = px(28);
        root.addView(pairing, pairLp);
        pairing.setVisibility(waiting ? View.VISIBLE : View.GONE);

        setContentView(root);
        // The TV must not go to sleep (and drop Wi-Fi, and with it the connection) in the middle of an install.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The screen eases in rather than appearing at once.
        root.setAlpha(0f);
        root.setTranslationY(px(16));
        root.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(new DecelerateInterpolator(2f)).start();
        go.requestFocus();
        handler.post(tick);
        // Opening the app is the press: the install starts by itself a moment after the screen is up (the button stays
        // for trying again).
        boolean carryOnUpdate = getSharedPreferences("kit_ui", MODE_PRIVATE).getBoolean("pending_update", false);
        if (carryOnUpdate) getSharedPreferences("kit_ui", MODE_PRIVATE).edit().putBoolean("pending_update", false).commit();
        if (carryOnUpdate && !kit.running && !kit.done && launcherInstalled()) {
            // This app was just replaced by its newer version in the middle of "Update the current pack": go on.
            update.requestFocus();
            handler.postDelayed(this::updatePressed, 500);
        } else if (!kit.running && !kit.done && !waiting) {
            status.setTextColor(0xFFC9CDD6);
            if (launcherInstalled()) {
                // Already on this TV: a few seconds to choose the red button instead of the update.
                autoLeft = 6;
                handler.post(countdown);
            } else {
                status.setText("ההתקנה מתחילה מעצמה…");
                handler.postDelayed(() -> {
                    if (!kit.running && !kit.done && !isFinishing()) pressed();
                }, 700);
            }
        }
    }

    /** "Install now": connect, and go; not connected yet, show the one-time pairing and start once it is done. */
    private void pressed() {
        autoLeft = 0;
        handler.removeCallbacks(countdown);
        if (kit.running) return;
        if (kit.done && kit.removed) {
            kit.removed = false;
            kit.done = false;
            setupStarted = false;
        }
        if (kit.done) {
            // Done: the button opens the new home.
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        status.setText("מתחבר לטלוויזיה… אם קופץ חלון \"לאפשר ניפוי באגים?\": לסמן \"אפשר תמיד\" וללחוץ אישור");
        status.setTextColor(0xFFC9CDD6);
        new Thread(() -> {
            boolean ok = kit.connectNow();
            // A newer installer online (every commit publishes one): it replaces this app and starts again by itself.
            if (ok && kit.updateIfNewer(text -> handler.post(() -> status.setText(text)))) return;
            handler.post(() -> {
                if (ok) {
                    waiting = false;
                    pairing.setVisibility(View.GONE);
                    kit.startAuto();
                } else {
                    waiting = true;
                    show(pairing);
                    status.setText("מחכה לאישור הטלוויזיה (פעם אחת בלבד)…");
                    status.setTextColor(0xFFFFD60A);
                }
            });
        }, "kit-go").start();
    }

    /**
     * "Update the current pack": this app's own newer version first (from the link; it reopens and carries on by
     * itself), then the newest launcher over the one on the TV, every setting kept.
     */
    private void updatePressed() {
        autoLeft = 0;
        handler.removeCallbacks(countdown);
        if (kit.running) return;
        if (kit.done) {
            kit.done = false;
            kit.updated = false;
            kit.removed = false;
        }
        title.setText("מעדכן את הערכה…");
        status.setText("מתחבר לטלוויזיה…");
        status.setTextColor(0xFFC9CDD6);
        new Thread(() -> {
            boolean ok = kit.connectNow();
            getSharedPreferences("kit_ui", MODE_PRIVATE).edit().putBoolean("pending_update", true).commit();
            if (ok && kit.updateIfNewer(text -> handler.post(() -> status.setText(text)))) return;
            getSharedPreferences("kit_ui", MODE_PRIVATE).edit().putBoolean("pending_update", false).commit();
            handler.post(() -> {
                if (ok) {
                    waiting = false;
                    pairing.setVisibility(View.GONE);
                    kit.startUpdate();
                } else {
                    show(pairing);
                    status.setText("צריך קודם לאשר את החיבור בטלוויזיה, ואז ללחוץ שוב");
                    status.setTextColor(0xFFFFD60A);
                }
            });
        }, "kit-update").start();
    }

    /** The red button asks once more: it takes our home screen off the TV and puts the TV's own back. */
    private void confirmRemove() {
        if (kit.running) return;
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("להסיר את הערכה לחלוטין?")
                .setMessage("מסך הבית שלנו יוסר מהטלוויזיה, וחוזרים מסך הבית המקורי, האפליקציות ששבתו ושומר המסך. "
                        + "השם, המקום והתמונות שנשמרו יימחקו.")
                .setPositiveButton("הסר", (d, w) -> {
                    title.setText("מסיר את הערכה…");
                    status.setText("מתחבר לטלוויזיה…");
                    new Thread(() -> {
                        boolean ok = kit.connectNow();
                        handler.post(() -> {
                            if (ok) kit.startRemove();
                            else {
                                waiting = false;
                                pairing.setVisibility(View.VISIBLE);
                                status.setText("צריך קודם לאשר את החיבור בטלוויזיה, ואז ללחוץ שוב");
                            }
                        });
                    }, "kit-remove").start();
                })
                .setNegativeButton("ביטול", null)
                .create();
        // Cancel is where the focus starts: removing takes a second, deliberate press.
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).requestFocus());
        dialog.show();
    }

    /** The bar and the line under it, twice a second; while waiting for the pairing, a look every 3 s. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String last = "";
            synchronized (kit) {
                if (!kit.log.isEmpty()) last = kit.log.get(kit.log.size() - 1)[2];
            }
            if (kit.running || kit.done || kit.error != null) {
                if (barRow.getVisibility() != View.VISIBLE) show(barRow);
                int p = Math.max(1, Math.min(100, kit.progress));
                if (p != shown) {
                    // Glide to the new value instead of jumping.
                    shown = p;
                    if (slide != null) slide.cancel();
                    slide = ObjectAnimator.ofInt(bar, "progress", bar.getProgress(), p * 10);
                    slide.setDuration(450);
                    slide.setInterpolator(new DecelerateInterpolator(1.6f));
                    slide.start();
                    percent.setText(p + "/100");
                }
            }
            if (kit.running) {
                if (pairing.getVisibility() == View.VISIBLE) pairing.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> pairing.setVisibility(View.GONE)).start();
                go.setText("מתקין…");
                status.setTextColor(0xFFC9CDD6);
                status.setText(kit.step + "\n" + last);
            } else if (kit.done && kit.updated) {
                title.setText("✓ הערכה עודכנה");
                go.setText("למסך הבית");
                status.setTextColor(0xFF30D158);
                status.setText("השם, המקום, התמונות, סדר האפליקציות וההגדרות נשמרו.");
            } else if (kit.done && kit.removed) {
                title.setText("✓ הערכה הוסרה");
                go.setText("התקן מחדש");
                status.setTextColor(0xFF30D158);
                status.setText("הטלוויזיה חזרה למסך הבית המקורי. אפשר להסיר גם את האפליקציה הזאת.");
            } else if (kit.done) {
                if (!setupStarted) {
                    // A moment on 100/100, then the setup: name, place, photos.
                    setupStarted = true;
                    handler.postDelayed(MainActivity.this::showName, 700);
                }
                title.setText("✓ ההתקנה הסתיימה");
                go.setText("למסך הבית החדש");
                status.setTextColor(0xFF30D158);
                status.setText("אפשר להוציא את ה-USB.");
            } else if (kit.error != null) {
                go.setText("לנסות שוב");
                status.setTextColor(0xFFFF453A);
                status.setText("נעצר: " + kit.error);
            }
            long now = System.currentTimeMillis();
            if (waiting && !kit.running && !checking && now - lastCheck > 3000) {
                lastCheck = now;
                checking = true;
                new Thread(() -> {
                    boolean ok = kit.connectNow();
                    handler.post(() -> {
                        checking = false;
                        if (ok && waiting) {
                            waiting = false;
                            kit.startAuto();
                        }
                    });
                }, "kit-wait").start();
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    // ---- the setup after the install ------------------------------------------------------------------------

    /** One setup screen: a title and a line under it, centred, easing in where the last one was. */
    private LinearLayout screen(String heading, String note) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        page.setBackgroundColor(0xFF101218);
        page.setPadding(px(120), px(28), px(120), px(28));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        TextView h = line(heading, 30, Color.WHITE, true);
        h.setGravity(Gravity.CENTER);
        page.addView(h);
        TextView n = line(note, 17, 0xFFC9CDD6, false);
        n.setGravity(Gravity.CENTER);
        page.addView(n);
        return page;
    }

    /** A page on screen, in a scroller so nothing is ever cut off: the focused control is always scrolled into view. */
    private void present(View page) {
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(0xFF101218);
        scroller.setClipChildren(false);
        scroller.addView(page, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
        page.setAlpha(0f);
        page.setTranslationX(-px(24));
        page.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private EditText field(String hint, String value, boolean hebrew) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x66FFFFFF);
        e.setGravity(hebrew ? Gravity.RIGHT : Gravity.LEFT);
        e.setTextDirection(hebrew ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        e.setPadding(px(22), px(14), px(22), px(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1F2330);
        bg.setCornerRadius(px(16));
        e.setBackground(bg);
        e.setOnFocusChangeListener((v, focused) -> bg.setStroke(px(2), focused ? 0xFFFFFFFF : 0x00FFFFFF));
        e.setId(View.generateViewId());
        return e;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF101218 : Color.WHITE);
        GradientDrawable bg = pill(primary ? 0xFFFFFFFF : 0xFF2A2F3D);
        b.setBackground(bg);
        b.setPadding(px(40), px(12), px(40), px(12));
        b.setOnFocusChangeListener((v, focused) -> {
            if (!primary) {
                bg.setColor(focused ? 0xFFFFFFFF : 0xFF2A2F3D);
                ((Button) v).setTextColor(focused ? 0xFF101218 : Color.WHITE);
            }
            v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(150).start();
        });
        b.setId(View.generateViewId());
        return b;
    }

    private void add(LinearLayout page, View v, int topDp, int widthDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthDp > 0 ? px(widthDp) : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(topDp);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(v, lp);
    }

    /** Screen 1: the name, in English and in Hebrew (the home greets in the TV's language). */
    private void showName() {
        if (isFinishing()) return;
        SharedPreferences saved = kit.setup();
        LinearLayout page = screen("איך קוראים לך?", "השם שמופיע בברכה במסך הבית, באנגלית ובעברית.");
        EditText en = field("Name in English", saved.getString("name_en", ""), false);
        EditText he = field("השם בעברית", saved.getString("name_he", ""), true);
        Button next = button("המשך", true);
        en.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        en.setNextFocusDownId(he.getId());
        en.setNextFocusForwardId(he.getId());
        he.setImeOptions(EditorInfo.IME_ACTION_DONE);
        he.setNextFocusDownId(next.getId());
        he.setOnEditorActionListener((v, action, ev) -> {
            next.requestFocus();
            return false;   // the keyboard closes as usual
        });
        next.setNextFocusUpId(he.getId());
        add(page, en, 28, 560);
        add(page, he, 14, 560);
        add(page, next, 28, 0);
        next.setOnClickListener(v -> {
            nameEn = en.getText().toString().trim();
            nameHe = he.getText().toString().trim();
            showPlace();
        });
        present(page);
        en.requestFocus();
    }

    /** Screen 2: the place, for the weather and the line under the clock; looked up by name. */
    private void showPlace() {
        SharedPreferences saved = kit.setup();
        placeEn = saved.getString("place_en", "");
        placeHe = saved.getString("place_he", "");
        lat = Double.parseDouble(saved.getString("lat", "0"));
        lon = Double.parseDouble(saved.getString("lon", "0"));
        LinearLayout page = screen("איפה הבית?", "בשביל מזג האוויר. כותבים עיר או יישוב ולוחצים חיפוש.");
        EditText city = field("עיר או יישוב", placeHe.split(",")[0], true);
        TextView chosen = line(placeHe.isEmpty() ? "עוד לא נבחר מקום (אפשר גם בלי)" : "נבחר: " + placeHe, 20,
                placeHe.isEmpty() ? 0xFF9AA0AD : 0xFF30D158, true);
        chosen.setGravity(Gravity.CENTER);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        results.setPadding(px(16), 0, px(16), px(8));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        buttons.setPadding(px(10), px(6), px(10), px(6));
        Button search = button("חיפוש", false);
        Button next = button("המשך", true);
        city.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        city.setNextFocusDownId(search.getId());
        search.setNextFocusUpId(city.getId());
        next.setNextFocusUpId(city.getId());
        city.setOnEditorActionListener((v, action, ev) -> {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(v.getWindowToken(), 0);
            search.performClick();
            return true;
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = px(16);
        buttons.addView(next);
        buttons.addView(search, gap);
        add(page, city, 28, 560);
        add(page, buttons, 18, 0);
        add(page, chosen, 18, 0);
        add(page, results, 10, 640);
        search.setOnClickListener(v -> {
            String q = city.getText().toString().trim();
            if (q.isEmpty()) return;
            chosen.setText("מחפש…");
            chosen.setTextColor(0xFFC9CDD6);
            new Thread(() -> {
                JSONArray found = geocode(q);
                handler.post(() -> {
                    results.removeAllViews();
                    if (found == null || found.length() == 0) {
                        chosen.setText(found == null ? "אין חיבור לאינטרנט כרגע" : "לא נמצא. לנסות שם אחר");
                        chosen.setTextColor(0xFFFF453A);
                        return;
                    }
                    chosen.setText("בוחרים את המקום הנכון:");
                    for (int i = 0; i < found.length() && i < 4; i++) {
                        JSONObject r = found.optJSONObject(i);
                        Button pick = button(r.optString("he_full"), false);
                        pick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                        pick.setPadding(px(24), px(6), px(24), px(6));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = px(8);
                        results.addView(pick, lp);
                        pick.setOnClickListener(x -> {
                            placeEn = r.optString("en");
                            placeHe = r.optString("he");
                            lat = r.optDouble("lat");
                            lon = r.optDouble("lon");
                            results.removeAllViews();
                            chosen.setText("נבחר: " + placeHe);
                            chosen.setTextColor(0xFF30D158);
                            next.requestFocus();
                        });
                        if (i == 0) pick.requestFocus();
                    }
                });
            }, "kit-geocode").start();
        });
        next.setOnClickListener(v -> showPhotos());
        present(page);
        // Nothing chosen yet: the city box first, so typing a name and Search is the way on.
        if (placeHe.isEmpty()) city.requestFocus(); else next.requestFocus();
    }

    /**
     * Open-Meteo's free place search (as the PC wizard's), in Hebrew and in English: up to five places, each
     * {he, en, he_full, lat, lon}. Null when there is no internet.
     */
    private static JSONArray geocode(String name) {
        try {
            // Typed in Hebrew, only the Hebrew search finds it (and the other way round): the places come from
            // whichever search found some, and each one's name in the other language is looked up by its id.
            JSONArray he = search(name, "he"), en = search(name, "en");
            JSONArray first = he.length() > 0 ? he : en;
            JSONArray out = new JSONArray();
            for (int i = 0; i < first.length() && i < 4; i++) {
                long id = first.getJSONObject(i).optLong("id");
                JSONObject h = byId(he, id), e = byId(en, id);
                if (h == null) h = get(id, "he");
                if (e == null) e = get(id, "en");
                if (h == null) h = first.getJSONObject(i);
                if (e == null) e = h;
                String heName = h.optString("name") + (h.has("country") ? ", " + h.optString("country") : "");
                String enName = e.optString("name") + (e.has("country") ? ", " + e.optString("country") : "");
                String area = h.optString("admin1", "");
                out.put(new JSONObject().put("he", heName).put("en", enName)
                        .put("he_full", heName + (area.isEmpty() ? "" : " (" + area + ")"))
                        .put("lat", Math.round(h.optDouble("latitude") * 1000) / 1000.0)
                        .put("lon", Math.round(h.optDouble("longitude") * 1000) / 1000.0));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject byId(JSONArray list, long id) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && o.optLong("id") == id) return o;
        }
        return null;
    }

    /** One place by its id, named in this language; null if it cannot be had. */
    private static JSONObject get(long id, String language) {
        try {
            return new JSONObject(fetch("https://geocoding-api.open-meteo.com/v1/get?id=" + id + "&language=" + language));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray search(String name, String language) throws Exception {
        JSONArray results = new JSONObject(fetch("https://geocoding-api.open-meteo.com/v1/search?count=5&language="
                + language + "&name=" + URLEncoder.encode(name, "UTF-8"))).optJSONArray("results");
        return results != null ? results : new JSONArray();
    }

    private static String fetch(String address) throws Exception {
        URL url = new URL(address);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    /** Screen 3: photos from the phone, by a QR code to this app's photo page; then everything goes on the TV. */
    private void showPhotos() {
        String url = "http://" + Adb.ownAddress(this) + ":" + WebServer.PORT + "/photos";
        LinearLayout page = screen("תמונות מהטלפון", "סורקים את הקוד בטלפון (על אותה רשת Wi-Fi) ובוחרים תמונות. הן נשמרות בטלוויזיה כערכה משלהן, \"תמונות\", שבוחרים מחלונית ההגדרות.");
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrCode(url, 512));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(px(170), px(170));
        qrLp.topMargin = px(16);
        qrLp.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(qr, qrLp);
        TextView count = line("", 20, 0xFF30D158, true);
        count.setGravity(Gravity.CENTER);
        page.addView(count);
        Button finish = button("סיום", true);
        add(page, finish, 14, 0);
        TextView state = line("אפשר גם בלי תמונות: לוחצים סיום.", 16, 0xFF9AA0AD, false);
        state.setGravity(Gravity.CENTER);
        page.addView(state);
        Runnable counter = new Runnable() {
            @Override
            public void run() {
                int n = kit.photoCount();
                count.setText(n == 0 ? "" : "נוספו " + n + " תמונות");
                if (!isFinishing() && page.isAttachedToWindow()) handler.postDelayed(this, 700);
            }
        };
        handler.post(counter);
        finish.setOnClickListener(v -> {
            finish.setEnabled(false);
            state.setText("שומר את השם, המקום והתמונות…");
            state.setTextColor(0xFFC9CDD6);
            new Thread(() -> {
                String error = null;
                try {
                    kit.finishSetup(nameEn, nameHe, placeEn, placeHe, lat, lon);
                } catch (Throwable e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                String failed = error;
                handler.post(() -> {
                    if (failed != null) {
                        finish.setEnabled(true);
                        state.setText("לא נשמר: " + failed);
                        state.setTextColor(0xFFFF453A);
                        return;
                    }
                    present(screen("✓ הכול מוכן", "מסך הבית שלך נפתח. אפשר להוציא את ה-USB."));
                    handler.postDelayed(() -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_MAIN)
                                    .setComponent(new ComponentName(Kit.PKG, Kit.PKG + ".MainActivity"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ignored) {
                        }
                        finish();
                    }, 2200);
                });
            }, "kit-finish").start();
        });
        present(page);
        finish.requestFocus();
    }

    /** A part of the screen fades and rises in. */
    private void show(View v) {
        if (v.getVisibility() == View.VISIBLE && v.getAlpha() == 1f) return;
        v.setAlpha(0f);
        v.setTranslationY(px(10));
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(40));
        return g;
    }

    private LayerDrawable barDrawable() {
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x33FFFFFF);
        track.setCornerRadius(px(7));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xFF30D158);
        fill.setCornerRadius(px(7));
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{track,
                new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private TextView line(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(6), 0, 0);
        return t;
    }

    private static Bitmap qrCode(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            }
            return bmp;
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
        }
    }
}

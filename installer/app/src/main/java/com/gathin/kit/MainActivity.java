package com.gathin.kit;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * The projector's screen while the kit runs: a QR code the phone scans to open the install wizard (served by WebServer
 * on this projector), the address to type instead, and what the install is doing right now.
 */
public class MainActivity extends Activity {
    private static WebServer server;
    private static Kit kit;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;

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
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101218);
        root.setPadding(Math.round(48 * d), Math.round(32 * d), Math.round(48 * d), Math.round(32 * d));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.RIGHT);
        text.addView(line("התקנת מסך הבית מהטלפון", 34, Color.WHITE, true));
        text.addView(line("1. סורקים את הקוד עם המצלמה של הטלפון (iPhone או Android).", 18, 0xFFC9CDD6, false));
        text.addView(line("2. הטלפון צריך להיות על אותה רשת Wi-Fi כמו המקרן.", 18, 0xFFC9CDD6, false));
        text.addView(line("3. ממשיכים בטלפון: הוא מסביר כל שלב.", 18, 0xFFC9CDD6, false));
        text.addView(line("או כותבים בדפדפן של הטלפון:", 16, 0xFF9AA0AD, false));
        TextView address = line(url, 26, 0xFF64D2FF, true);
        address.setTextDirection(TextView.TEXT_DIRECTION_LTR);
        text.addView(address);
        status = line("", 18, 0xFF30D158, false);
        text.addView(status);
        if (server == null) status.setText("לא הצלחתי לפתוח את השרת. להפעיל את האפליקציה מחדש.");

        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrCode(url, 640));
        int side = Math.round(300 * d);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(side, side);
        qrLp.leftMargin = Math.round(48 * d);
        root.addView(qr, qrLp);
        root.addView(text, 0, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        setContentView(root);
        handler.post(tick);
    }

    /** What the install is doing, once a second. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (kit != null && status != null) {
                String last = "";
                synchronized (kit) {
                    if (!kit.log.isEmpty()) last = kit.log.get(kit.log.size() - 1)[2];
                }
                if (kit.running) status.setText("מתקין… " + kit.step + "\n" + last);
                else if (kit.done) status.setText("✓ " + last);
                else if (kit.error != null) status.setText("נעצר: " + kit.error);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    private TextView line(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, Math.round(6 * getResources().getDisplayMetrics().density), 0, 0);
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

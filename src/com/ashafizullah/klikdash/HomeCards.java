package com.ashafizullah.klikdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;
import static com.ashafizullah.klikdash.HomeApps.SLOTS;

/**
 * Home bergaya widget iOS di atas wallpaper: dock ikon di kiri, lalu empat kartu kaca (kecepatan + mobil, sholat dengan
 * langit dan masjid, Zlink + perjalanan + cuaca per jam, pemutar musik) dan slider volume/kecerahan gaya Control
 * Center. Kartu digambar sendiri sehingga mengikuti ukurannya: susunan diatur {@link Grid} (biasa, layar lebar, potret,
 * mode menyetir, dan urutan/sembunyi pilihan pengguna). Bagian lama dipakai ulang: TextView jam/tanggal/Hijriah/
 * kecepatan milik {@link HomeActivity} (diisi updateTime dan HomeLocation, di sini hanya dibaca teksnya), slot ubin
 * ({@link HomeViews.Tile}), sumber media ({@link MediaNotch#current}), dan volume/kecerahan dari {@link QuickPanel}.
 */
final class HomeCards {
    final HomeActivity h;
    final Typeface regular, medium, bold;

    HomeCards(HomeActivity h) {
        this.h = h;
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
    }

    FrameLayout root;
    Grid grid;
    SpeedCard speed;
    PrayerCard prayer;
    DriveCard drive;
    MediaCard media;
    Slider volume, brightness;
    EditLayer edit;

    /** Jarak trip sejak headunit menyala/bangun (meter); statis: tetap saat home dibangun ulang. */
    static double tripM;
    private static double lastLat = Double.NaN, lastLon;
    private static long lastFixAt;
    /** Lama mobil bergerak (ms) dan kecepatan tertinggi trip ini. */
    static long tripMovingMs;
    static int tripMaxKmh;

    static void resetTrip() {
        tripM = 0;
        tripMovingMs = 0;
        tripMaxKmh = 0;
        lastLat = Double.NaN;
    }

    // ---------------------------------------------------------------- warna

    /** Tema siang (Theme.light): widget putih kabur dengan teks gelap. Tema otomatis menggantinya saat matahari terbit/terbenam. */
    boolean day() { return Theme.light(); }

    /** Warna aksen dari sampul lagu yang diputar (lihat MediaCard.dominant), atau aksen tema bila tidak ada. */
    int albumAcc;

    int acc() { return albumAcc != 0 ? albumAcc : AMBER; }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    // ---------------------------------------------------------------- tata letak

    View build() {
        root = new FrameLayout(h);
        root.setBackground(new Wallpaper());
        loadWallpaper();

        LinearLayout row = new LinearLayout(h);
        row.setOrientation(h.portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.addView(row, MATCH, MATCH);

        grid = new Grid();
        speed = new SpeedCard();
        prayer = new PrayerCard();
        h.prayerView = prayer;
        drive = new DriveCard();
        media = new MediaCard();
        volume = new Slider(true);
        brightness = new Slider(false);
        for (View v : new View[]{speed, prayer, drive, media, volume, brightness}) {
            glass(v);
            grid.addView(v);
        }
        View dock = dock();
        if (h.portrait) {
            row.addView(grid, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            row.addView(dock, new LinearLayout.LayoutParams(MATCH, h.px(92)));
        } else {
            row.addView(dock, new LinearLayout.LayoutParams(h.px(dockDp()), MATCH));
            row.addView(grid, new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        edit = new EditLayer();
        root.addView(edit, MATCH, MATCH);

        // TextView milik HomeActivity yang diisi updateTime/HomeLocation; tidak dipasang, kartu kecepatan membaca teksnya.
        h.speed = h.text("--", regular, 20, TEXT);
        h.clock = h.text("00:00", regular, 20, TEXT);
        h.ampm = h.text("", regular, 12, DIM);
        h.day = h.text("", regular, 12, DIM);
        h.date = h.text("", regular, 12, DIM);
        h.hijri = h.text("", regular, 12, AMBER);

        // Muncul hanya bila KlikDash belum jadi tampilan utama (lihat HomeActivity.refreshDefaultHome).
        h.makeDefault = new HomeViews.Button(h, h.getString(R.string.home_make_default), true);
        h.makeDefault.setOnClickListener(v -> HomeSwitch.makeDefault(h, h.prefs, h::refreshDefaultHome));
        h.makeDefault.setVisibility(View.GONE);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(WRAP, h.px(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ml.bottomMargin = h.px(24);
        root.addView(h.makeDefault, ml);
        h.reservedDefault = true;

        // Kondisi headunit (HomeStatus) tidak ditampilkan di home kartu, tapi tetap diisi (asisten AI, screensaver).
        h.status.diskLabel = h.getString(R.string.home_disk_short);
        h.status.temp = new TextView(h);
        h.status.ram = new TextView(h);
        h.status.disk = new TextView(h);
        loadOrder();
        return root;
    }

    /** Lebar dock: lebih sempit di layar pendek; di layar sangat pendek (≤ 500 dp) hanya ikon. */
    float dockDp() { return h.hDp <= 500 ? 72 : h.hDp <= 640 ? 86 : 100; }

    void glass(View v) {
        boolean d = day();
        v.setBackground(new Scenery.Glass(v, root, () -> blurred, h.px(24), Math.max(1, h.px(1)),
                d ? 0x94FFFFFF : 0x6B26262C, d ? 0xB3FFFFFF : 0x24FFFFFF));
        v.setClipToOutline(true);
    }

    // ---------------------------------------------------------------- susunan kartu

    static final String[] CARD_IDS = {"speed", "prayer", "drive", "media"};
    /** Urutan empat kartu di petak kiri atas, kanan atas, kiri bawah, kanan bawah; dan kartu yang disembunyikan. */
    final List<String> order = new ArrayList<>(Arrays.asList(CARD_IDS));
    final Set<String> hidden = new HashSet<>();
    /** Mode menyetir: kecepatan memenuhi kolom kiri, kartu perjalanan disembunyikan (lihat onFix). */
    boolean driving;

    void loadOrder() {
        String s = h.prefs.getString("cardOrder", null);
        if (s != null) {
            List<String> o = new ArrayList<>(Arrays.asList(s.split(",")));
            if (o.size() == 4 && new HashSet<>(o).equals(new HashSet<>(Arrays.asList(CARD_IDS)))) {
                order.clear();
                order.addAll(o);
            }
        }
        hidden.clear();
        String hs = h.prefs.getString("cardHidden", "");
        for (String x : hs.split(",")) if (Arrays.asList(CARD_IDS).contains(x)) hidden.add(x);
    }

    void saveOrder() {
        h.prefs.edit().putString("cardOrder", TextUtils.join(",", order)).putString("cardHidden", TextUtils.join(",", hidden)).apply();
    }

    View card(String id) {
        switch (id) {
            case "speed": return speed;
            case "prayer": return prayer;
            case "drive": return drive;
            default: return media;
        }
    }

    String idOf(View v) {
        return v == speed ? "speed" : v == prayer ? "prayer" : v == drive ? "drive" : v == media ? "media" : null;
    }

    /**
     * Penata kartu: menghitung kotak tiap kartu dari ukuran layar dan mode. Biasa (landscape): kolom 42/58, baris 40/60,
     * slider di bawah kartu kanan bawah. Lebar (rasio > 2,2, mis. 1280×480): empat kartu sebaris + slider di bawah.
     * Potret: bertumpuk. Menyetir: kecepatan setinggi layar di kiri, sholat + musik di kanan.
     */
    final class Grid extends ViewGroup {
        final Map<View, Rect> rects = new HashMap<>();

        Grid() { super(h); }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), ht = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, ht);
            compute(w, ht);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                Rect r = rects.get(c);
                if (r == null || r.isEmpty()) {
                    c.setVisibility(GONE);
                    continue;
                }
                c.setVisibility(VISIBLE);
                c.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY));
            }
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                Rect x = rects.get(c);
                if (x != null && !x.isEmpty()) c.layout(x.left, x.top, x.right, x.bottom);
            }
        }

        private void put(View v, float l, float t, float r, float b) {
            rects.put(v, new Rect(Math.round(l), Math.round(t), Math.round(r), Math.round(b)));
        }

        void compute(int w, int ht) {
            rects.clear();
            float pad = h.px(14), gap = h.px(14), W = w - pad * 2, H = ht - pad * 2, x0 = pad, y0 = pad;
            if (h.portrait) {
                y0 = pad;
                float sl = h.px(76);
                List<View> stack = new ArrayList<>();
                for (String id : new String[]{"speed", "prayer", "media", "drive"}) if (!hidden.contains(id)) stack.add(card(id));
                float[] wt = new float[stack.size()];
                float sum = 0;
                for (int i = 0; i < wt.length; i++) {
                    View v = stack.get(i);
                    wt[i] = v == drive ? 1.2f : v == media ? 1f : .9f;
                    sum += wt[i];
                }
                float avail = H - sl - gap * stack.size(), y = y0;
                for (int i = 0; i < wt.length; i++) {
                    float hh = avail * wt[i] / sum;
                    put(stack.get(i), x0, y, x0 + W, y + hh);
                    y += hh + gap;
                }
                put(volume, x0, y, x0 + (W - gap) / 2, y + sl);
                put(brightness, x0 + (W + gap) / 2, y, x0 + W, y + sl);
                return;
            }
            boolean wide = w / (float) ht > 2.2f;
            if (wide) {
                float sl = h.px(64), top = H - sl - gap, x = x0;
                float[] wt = {1.05f, 1.1f, 1f, 1.25f};
                List<View> row = new ArrayList<>();
                List<Float> ws = new ArrayList<>();
                float sum = 0;
                for (int i = 0; i < 4; i++) {
                    if (hidden.contains(order.get(i))) continue;
                    row.add(card(order.get(i)));
                    ws.add(wt[i]);
                    sum += wt[i];
                }
                float avail = W - gap * Math.max(0, row.size() - 1);
                for (int i = 0; i < row.size(); i++) {
                    float cw = avail * ws.get(i) / sum;
                    put(row.get(i), x, y0, x + cw, y0 + top);
                    x += cw + gap;
                }
                float sy = y0 + top + gap;
                put(volume, x0, sy, x0 + (W - gap) / 2, sy + sl);
                put(brightness, x0 + (W + gap) / 2, sy, x0 + W, sy + sl);
                return;
            }
            float sl = h.px(Math.min(84, h.hDp * .12f));
            if (driving) {
                float colL = (W - gap) * .44f, xr = x0 + colL + gap, rowT = (H - gap) * .34f;
                put(speed, x0, y0, x0 + colL, y0 + H);
                put(prayer, xr, y0, x0 + W, y0 + rowT);
                put(media, xr, y0 + rowT + gap, x0 + W, y0 + H - sl - gap);
                put(volume, xr, y0 + H - sl, xr + (x0 + W - xr - gap) / 2, y0 + H);
                put(brightness, xr + (x0 + W - xr + gap) / 2, y0 + H - sl, x0 + W, y0 + H);
                return;
            }
            float colL = (W - gap) * .42f, xr = x0 + colL + gap, rowT = (H - gap) * .40f, yb = y0 + rowT + gap;
            float botR = y0 + H - sl - gap;
            String tl = order.get(0), tr = order.get(1), bl = order.get(2), br = order.get(3);
            boolean htl = hidden.contains(tl), htr = hidden.contains(tr), hbl = hidden.contains(bl), hbr = hidden.contains(br);
            boolean leftEmpty = htl && hbl;
            float right0 = leftEmpty ? x0 : xr, left1 = (htr && hbr) ? x0 + W : x0 + colL;
            // Kolom kiri: petak yang disembunyikan diambil tetangganya di kolom yang sama.
            if (!htl) put(card(tl), x0, y0, left1, hbl ? y0 + H : y0 + rowT);
            if (!hbl) put(card(bl), x0, htl ? y0 : yb, left1, y0 + H);
            if (!htr) put(card(tr), right0, y0, x0 + W, hbr ? botR : y0 + rowT);
            if (!hbr) put(card(br), right0, htr ? y0 : yb, x0 + W, botR);
            put(volume, right0, y0 + H - sl, right0 + (x0 + W - right0 - gap) / 2, y0 + H);
            put(brightness, right0 + (x0 + W - right0 + gap) / 2, y0 + H - sl, x0 + W, y0 + H);
        }
    }

    void relayout() {
        grid.requestLayout();
        for (View v : new View[]{speed, prayer, drive, media, volume, brightness}) v.invalidate();
    }

    // ---------------------------------------------------------------- dock (gaya iOS)

    View dock() {
        boolean port = h.portrait, iconOnly = !port && h.hDp <= 500;
        LinearLayout side = new LinearLayout(h);
        side.setOrientation(port ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setPadding(h.px(6), h.px(12), h.px(6), h.px(12));
        side.setBackgroundColor(day() ? 0x66FFFFFF : 0x5A1E1E24);

        // Kunci layar (PIN di Pengaturan): gembok muncul hanya bila PIN sudah diatur, lihat refreshLock.
        h.lockIcon = new ScreenLock.Icon(h);
        h.lockIcon.setOnClickListener(v -> h.toggleLock());
        side.addView(h.lockIcon, port ? new LinearLayout.LayoutParams(h.px(48), MATCH) : new LinearLayout.LayoutParams(MATCH, h.px(40)));

        float item = iconOnly ? 52 : h.hDp <= 640 ? 70 : 80, iconDp = iconOnly ? 44 : h.hDp <= 640 ? 46 : 54;
        DockButton home = new DockButton(h.getString(R.string.home_side_home), DockButton.HOME, iconDp, iconOnly);
        home.selected = true;
        home.setOnClickListener(v -> {
            if (h.quick.isOpen()) h.quick.close();
            if (h.drawer.isOpen()) h.drawer.close();
        });
        side.addView(home, cell(port, item));

        // Slot aplikasi sebanyak yang muat (paling banyak sesuai jumlah ubin di Pengaturan); sisanya di APLIKASI.
        float span = port ? h.wDp - 12 - 48 : h.hDp - 24 - 40 - 24;
        int fit = (int) (span / item) - 3;
        int slots = Math.max(1, Math.min(h.shown, fit));
        for (int i = 0; i < slots; i++) {
            DockTile t = new DockTile(i, iconDp, iconOnly);
            h.tiles[i] = t;
            side.addView(t, cell(port, item));
        }
        DockTile all = new DockTile(SLOTS, iconDp, iconOnly);
        h.tiles[SLOTS] = all;
        side.addView(all, cell(port, item));
        side.addView(new View(h), port ? new LinearLayout.LayoutParams(0, 1, 1f) : new LinearLayout.LayoutParams(1, 0, 1f));
        DockButton settings = new DockButton(h.getString(R.string.home_side_settings), DockButton.GEAR, iconDp, iconOnly);
        settings.setContentDescription(h.getString(R.string.quick_title));
        settings.setOnClickListener(v -> h.quick.open());
        settings.setOnLongClickListener(v -> {
            h.openSettings();
            return true;
        });
        h.gear = settings;
        side.addView(settings, cell(port, item));
        return side;
    }

    private LinearLayout.LayoutParams cell(boolean port, float dp) {
        return port ? new LinearLayout.LayoutParams(h.px(dp), MATCH) : new LinearLayout.LayoutParams(MATCH, h.px(dp));
    }

    /** Ikon aplikasi iOS: dipotong ke kotak bulat (squircle) sekali per aplikasi dan ukuran, lalu dipakai ulang. */
    private final Map<String, Bitmap> iconCache = new HashMap<>();

    Bitmap squircle(String key, Drawable d, int size) {
        String k = key + "@" + size;
        Bitmap b = iconCache.get(k);
        if (b != null) return b;
        b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size * .235f;
        boolean adaptive = android.os.Build.VERSION.SDK_INT >= 26 && d instanceof android.graphics.drawable.AdaptiveIconDrawable;
        Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(layer);
        if (adaptive) {
            // Lapisan adaptif 108 dp dengan 72 dp terlihat: diperbesar 1,5× supaya memenuhi kotak seperti ikon iOS.
            int ext = Math.round(size * .25f);
            d.setBounds(-ext, -ext, size + ext, size + ext);
            d.draw(lc);
        } else {
            Paint bg = new Paint();
            bg.setShader(new LinearGradient(0, 0, 0, size, 0xFFFFFFFF, 0xFFE5E5EA, Shader.TileMode.CLAMP));
            lc.drawRect(0, 0, size, size, bg);
            int in = Math.round(size * .14f);
            d.setBounds(in, in, size - in, size - in);
            d.draw(lc);
        }
        p.setShader(new BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, size, size, r, r, p);
        layer.recycle();
        iconCache.put(k, b);
        return b;
    }

    /** Kilau tipis di separuh atas ikon, seperti ikon iOS. */
    void gloss(Canvas c, Paint p, RectF box) {
        float r = box.width() * .235f;
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, box.top, 0, box.centerY(), 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, p);
        p.setShader(null);
    }

    abstract class DockBase extends View {
        final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();
        final float iconDp;
        final boolean iconOnly;
        boolean selected;

        DockBase(float iconDp, boolean iconOnly) {
            super(h);
            this.iconDp = iconDp;
            this.iconOnly = iconOnly;
            text.setTypeface(medium);
            text.setTextSize(h.sp(iconDp < 50 ? 10 : 10.5f));
            text.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        /** Kotak ikon: di tengah atas, dengan ruang label di bawahnya. */
        RectF iconBox() {
            float s = h.px(iconDp), cx = getWidth() / 2f, top = iconOnly ? (getHeight() - s) / 2f : (getHeight() - s - h.px(16)) / 2f;
            box.set(cx - s / 2f, top, cx + s / 2f, top + s);
            if (isPressed()) box.inset(s * .04f, s * .04f);
            return box;
        }

        void label(Canvas c, String s, RectF icon) {
            if (iconOnly) return;
            text.setColor(TEXT);
            if (s.equals(s.toUpperCase(Locale.ROOT))) s = title(s);
            CharSequence e = TextUtils.ellipsize(s, text, getWidth() - h.px(4), TextUtils.TruncateAt.END);
            c.drawText(e, 0, e.length(), getWidth() / 2f, icon.bottom + h.px(5) + text.getTextSize(), text);
        }

        void ring(Canvas c, RectF icon) {
            if (!selected) return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(h.px(2.5f));
            paint.setColor(day() ? 0xFF1C1C1E : 0xFFFFFFFF);
            float r = icon.width() * .235f + h.px(2);
            c.drawRoundRect(icon.left - h.px(2.5f), icon.top - h.px(2.5f), icon.right + h.px(2.5f), icon.bottom + h.px(2.5f), r, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        void shadow(Canvas c, RectF icon) {
            paint.setColor(0x40000000);
            float r = icon.width() * .235f;
            c.drawRoundRect(icon.left, icon.top + h.px(2), icon.right, icon.bottom + h.px(3), r, r, paint);
        }
    }

    final class DockButton extends DockBase {
        static final int HOME = 0, GEAR = 1;
        final String name;
        final int kind;
        final Path path = new Path();

        DockButton(String name, int kind, float iconDp, boolean iconOnly) {
            super(iconDp, iconOnly);
            this.name = name;
            this.kind = kind;
            setContentDescription(name);
        }

        @Override protected void onDraw(Canvas c) {
            RectF b = iconBox();
            shadow(c, b);
            float r = b.width() * .235f;
            paint.setColor(0xFF000000);
            paint.setShader(kind == HOME ? new LinearGradient(0, b.top, 0, b.bottom, 0xFFFFC94D, 0xFFFF9500, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, b.top, 0, b.bottom, 0xFFA1A1A8, 0xFF6C6C73, Shader.TileMode.CLAMP));
            c.drawRoundRect(b, r, r, paint);
            paint.setShader(null);
            gloss(c, paint, b);
            float cx = b.centerX(), cy = b.centerY(), s = b.width() * .26f;
            paint.setColor(0xFFFFFFFF);
            if (kind == HOME) {
                path.rewind();
                path.moveTo(cx, cy - s);
                path.lineTo(cx + s * 1.05f, cy - s * .05f);
                path.lineTo(cx + s * .75f, cy - s * .05f);
                path.lineTo(cx + s * .75f, cy + s * .9f);
                path.lineTo(cx + s * .25f, cy + s * .9f);
                path.lineTo(cx + s * .25f, cy + s * .3f);
                path.lineTo(cx - s * .25f, cy + s * .3f);
                path.lineTo(cx - s * .25f, cy + s * .9f);
                path.lineTo(cx - s * .75f, cy + s * .9f);
                path.lineTo(cx - s * .75f, cy - s * .05f);
                path.lineTo(cx - s * 1.05f, cy - s * .05f);
                path.close();
                c.drawPath(path, paint);
            } else {
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, cx, cy);
                    c.drawRoundRect(cx - s * .2f, cy - s * 1.05f, cx + s * .2f, cy - s * .55f, s * .06f, s * .06f, paint);
                    c.restore();
                }
                c.drawCircle(cx, cy, s * .72f, paint);
                paint.setColor(0xFF8A8A90);
                c.drawCircle(cx, cy, s * .32f, paint);
            }
            ring(c, b);
            label(c, name, b);
        }
    }

    /** Slot aplikasi di dock: logika ketuk, tekan lama, dan seret milik {@link HomeViews.Tile}. */
    final class DockTile extends HomeViews.Tile {
        final DockBase look;

        DockTile(int idx, float iconDp, boolean iconOnly) {
            super(HomeCards.this.h, idx);
            look = new DockBase(iconDp, iconOnly) { };
        }

        @Override protected void onDraw(Canvas c) {
            look.layout(0, 0, getWidth(), getHeight());
            look.setPressed(down);
            RectF b = look.iconBox();
            Paint paint = look.paint;
            look.shadow(c, b);
            String name;
            HomeApps apps = h.apps;
            if (idx == SLOTS) {
                float r = b.width() * .235f;
                paint.setColor(0xFF000000);
                paint.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xFF5E5E66, 0xFF3A3A40, Shader.TileMode.CLAMP));
                c.drawRoundRect(b, r, r, paint);
                paint.setShader(null);
                float cell = b.width() * .26f, g = b.width() * .08f, sx = b.centerX() - cell - g / 2, sy = b.centerY() - cell - g / 2;
                int[] cols = {0xFFFFCC00, 0xFF34C759, 0xFF0A84FF, 0xFFFF375F};
                for (int i = 0; i < 4; i++) {
                    paint.setColor(cols[i]);
                    float x = sx + (i % 2) * (cell + g), y = sy + (i / 2) * (cell + g);
                    c.drawRoundRect(x, y, x + cell, y + cell, cell * .28f, cell * .28f, paint);
                }
                gloss(c, paint, b);
                name = h.getString(R.string.home_side_apps);
            } else {
                HomeApps.App a = apps.appFor(idx);
                if (a != null) {
                    c.drawBitmap(squircle(a.cn.flattenToString(), apps.iconOf(a), Math.round(b.width())), b.left, b.top, null);
                    gloss(c, paint, b);
                    // Titik di samping ikon: aplikasi ini sedang memutar musik.
                    MediaController mc = MediaListener.current;
                    if (mc != null && MediaListener.playing(mc) && a.cn.getPackageName().equals(mc.getPackageName())) {
                        paint.setColor(TEXT);
                        c.drawCircle(b.left - h.px(7), b.centerY(), h.px(2.5f), paint);
                    }
                } else {
                    float r = b.width() * .235f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(h.px(1.5f));
                    paint.setColor(alpha(TEXT, 0x66));
                    c.drawRoundRect(b, r, r, paint);
                    c.drawLine(b.centerX(), b.top + b.height() * .3f, b.centerX(), b.bottom - b.height() * .3f, paint);
                    c.drawLine(b.left + b.width() * .3f, b.centerY(), b.right - b.width() * .3f, b.centerY(), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                name = apps.slotLabel[idx] != null ? apps.labelText(apps.slotLabel[idx]) : a != null ? a.label : h.tEmpty;
                if (name != null && name.equals(name.toUpperCase(Locale.ROOT))) name = title(name);
            }
            if (target) {
                look.selected = true;
                look.ring(c, b);
                look.selected = false;
            }
            look.label(c, name != null ? name : "", b);
            if (dragging) {
                paint.setColor(0x99000000);
                float r = b.width() * .235f;
                c.drawRoundRect(b, r, r, paint);
            }
        }

        @Override void drawDragState(Canvas c) { }
    }

    // ---------------------------------------------------------------- bantuan menggambar

    static String title(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean up = true;
        for (char ch : s.toCharArray()) {
            b.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            up = !Character.isLetter(ch) && ch != '\'';
        }
        return b.toString();
    }

    /** Kepala widget iOS: kotak ikon kecil berwarna dan judul tebal. Mengembalikan garis dasar teks. */
    float header(Canvas c, TextPaint t, float x, float y, int color, String glyph, String text, float avail) {
        return header(c, t, x, y, color, glyph, text, avail, TEXT);
    }

    float header(Canvas c, TextPaint t, float x, float y, int color, String glyph, String text, float avail, int fg) {
        float s = h.px(22);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawRoundRect(x, y, x + s, y + s, h.px(6), h.px(6), p);
        t.setTypeface(bold);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(h.sp(12));
        t.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(glyph, x + s / 2, y + s / 2 - (fm.ascent + fm.descent) / 2, t);
        t.setTextAlign(Paint.Align.LEFT);
        t.setTypeface(medium);
        t.setTextSize(h.sp(14));
        t.setColor(fg);
        fm = t.getFontMetrics();
        float base = y + s / 2 - (fm.ascent + fm.descent) / 2;
        CharSequence e = TextUtils.ellipsize(text, t, avail - s - h.px(8), TextUtils.TruncateAt.END);
        c.drawText(e, 0, e.length(), x + s + h.px(8), base, t);
        return base;
    }

    static void ellipsized(Canvas c, TextPaint t, String s, float x, float y, float avail) {
        if (s == null || s.isEmpty() || avail <= 0) return;
        CharSequence e = TextUtils.ellipsize(s, t, avail, TextUtils.TruncateAt.END);
        c.drawText(e, 0, e.length(), x, y, t);
    }

    /** Kotak bulat setengah tembus untuk tombol/area di dalam kartu (lebih terang saat ditekan). */
    void chip(Canvas c, Paint p, RectF r, float radius, boolean down) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(down ? alpha(acc(), 0x59) : day() ? 0x8CFFFFFF : 0x14FFFFFF);
        c.drawRoundRect(r, radius, radius, p);
    }

    // ---------------------------------------------------------------- kartu kecepatan + mobil

    /** Di atas ini angka dan jarum kecepatan merah (batas lebuh raya 110 km/j). */
    static final int SPEED_HOT = 110;

    /**
     * Speedometer jarum 0–180 (zona merah dari 110) dengan angka besar, lalu tanggal, jam, dan Hijriah di sampingnya;
     * sedan hologram di latar kanan bawah. Mode menyetir (kartu tinggi): speedometer besar di tengah, hanya jam kecil.
     */
    final class SpeedCard extends View {
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();
        final Path needle = new Path();
        private Bitmap car;
        private int carW, carAcc;

        SpeedCard() {
            super(h);
            setOnLongClickListener(v -> {
                edit.start();
                return true;
            });
        }

        Bitmap car(int w) {
            if (car == null || carW != w || carAcc != acc()) {
                carW = w;
                carAcc = acc();
                Bitmap b = Scenery.car(w, carAcc);
                // Memudar ke kiri supaya tidak menabrak speedometer.
                Canvas c = new Canvas(b);
                Paint m = new Paint();
                m.setShader(new LinearGradient(0, 0, w * .4f, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
                m.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
                c.drawRect(0, 0, w, b.getHeight(), m);
                car = b;
            }
            return car;
        }

        int kmh() {
            boolean live = SystemClock.elapsedRealtime() - HomeLocation.lastKmhAt < 5000;
            return live ? HomeLocation.lastKmh : 0;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), ht = getHeight();
            boolean tall = ht > w * .9f, wide = !tall && w < ht * 1.25f;
            float pad = h.px(14);
            // Mobil.
            float carW = tall ? w * .78f : wide ? w * .86f : w * .6f;
            Bitmap cb = car(Math.round(carW));
            c.drawBitmap(cb, tall ? (w - carW) / 2f : w - carW - h.px(8), ht - cb.getHeight() - h.px(2), null);

            // Speedometer.
            float gs = tall ? Math.min(w - pad * 2, ht * .62f) : wide ? Math.min(w * .8f, ht * .7f) : Math.min(ht - pad * 1.6f, w * .5f);
            float gx = tall ? (w - gs) / 2f : wide ? (w - gs) / 2f : pad, gy = tall ? ht * .1f : wide ? pad * 1.5f : (ht - gs) / 2f;
            gauge(c, gx, gy, gs);

            // Tanggal, jam, Hijriah.
            Calendar cal = Calendar.getInstance();
            String date = title(Lang.days[cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY]) + ", " + cal.get(Calendar.DAY_OF_MONTH)
                    + " " + title(Lang.months[cal.get(Calendar.MONTH)]).substring(0, 3);
            String clock = h.clock.getText() + (h.ampm.getText().length() > 0 ? " " + h.ampm.getText() : "");
            t.setTextAlign(Paint.Align.LEFT);
            if (tall) {
                t.setTypeface(bold);
                t.setTextSize(h.sp(22));
                t.setColor(TEXT);
                c.drawText(clock, pad + h.px(8), pad + t.getTextSize(), t);
                return;
            }
            if (wide) {
                t.setTextAlign(Paint.Align.RIGHT);
                t.setTypeface(medium);
                t.setTextSize(h.sp(11));
                t.setColor(DIM);
                c.drawText(date, w - pad, pad + t.getTextSize(), t);
                t.setTypeface(bold);
                t.setTextSize(h.sp(20));
                t.setColor(TEXT);
                c.drawText(clock, w - pad, pad + h.px(36), t);
                return;
            }
            float x = gx + gs + h.px(16), avail = w - x - pad, y = pad + h.px(10);
            t.setTypeface(medium);
            t.setTextSize(Math.min(h.sp(15), ht * .07f));
            t.setColor(DIM);
            y += t.getTextSize();
            ellipsized(c, t, date, x, y, avail);
            t.setTypeface(bold);
            t.setTextSize(Math.min(h.sp(34), Math.min(ht * .16f, avail / 3.2f)));
            t.setColor(TEXT);
            y += t.getTextSize() + h.px(4);
            ellipsized(c, t, clock, x, y, avail);
            t.setTypeface(medium);
            t.setTextSize(Math.min(h.sp(13), ht * .06f));
            t.setColor(acc());
            y += t.getTextSize() + h.px(8);
            ellipsized(c, t, title(h.hijri.getText().toString()).replace(" H", " H"), x, y, avail);
        }

        void gauge(Canvas c, float gx, float gy, float gs) {
            float cx = gx + gs / 2f, cy = gy + gs / 2f, rad = gs / 2f * .88f, k = gs / 210f;
            boolean d = day();
            p.setStyle(Paint.Style.FILL);
            p.setColor(d ? 0x59FFFFFF : 0x40000000);
            c.drawCircle(cx, cy, gs / 2f * .95f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(3 * k);
            box.set(cx - rad - 4 * k, cy - rad - 4 * k, cx + rad + 4 * k, cy + rad + 4 * k);
            p.setColor(0xFFFF453A);
            c.drawArc(box, 135 + 110 / 180f * 270, 70 / 180f * 270, false, p);
            int kmh = kmh();
            if (kmh > 0) {
                p.setColor(kmh > SPEED_HOT ? 0xFFFF453A : acc());
                c.drawArc(box, 135, Math.min(180, kmh) / 180f * 270, false, p);
            }
            t.setTypeface(medium);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(12 * k * 1.05f);
            for (int v = 0; v <= 180; v += 10) {
                boolean major = v % 20 == 0;
                double a = Math.toRadians(135 + v / 180f * 270);
                float r2 = rad - (major ? 14 : 7) * k;
                p.setStrokeWidth((major ? 2.6f : 1.4f) * k);
                p.setColor(v >= SPEED_HOT ? 0xFFFF453A : major ? (d ? 0xFF1C1C1E : 0xFFFFFFFF) : (d ? 0x66000000 : 0x80FFFFFF));
                c.drawLine(cx + (float) Math.cos(a) * rad, cy + (float) Math.sin(a) * rad, cx + (float) Math.cos(a) * r2,
                        cy + (float) Math.sin(a) * r2, p);
                if (v % 40 == 0) {
                    float rl = rad - 30 * k;
                    t.setColor(d ? 0xB3000000 : 0xBFFFFFFF);
                    c.drawText(String.valueOf(v), cx + (float) Math.cos(a) * rl, cy + (float) Math.sin(a) * rl + 4 * k, t);
                }
            }
            // Jarum.
            p.setStyle(Paint.Style.FILL);
            p.setColor(kmh > SPEED_HOT ? 0xFFFF453A : acc());
            c.save();
            c.rotate(135 + Math.min(180, kmh) / 180f * 270, cx, cy);
            needle.rewind();
            needle.moveTo(cx, cy - 3.5f * k);
            needle.lineTo(cx + rad * .92f, cy - .8f * k);
            needle.lineTo(cx + rad * .92f, cy + .8f * k);
            needle.lineTo(cx, cy + 3.5f * k);
            needle.close();
            c.drawPath(needle, p);
            c.restore();
            p.setColor(d ? 0xFFFFFFFF : 0xFF1A1A1F);
            c.drawCircle(cx, cy, 9 * k, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * k);
            p.setColor(kmh > SPEED_HOT ? 0xFFFF453A : acc());
            c.drawCircle(cx, cy, 9 * k, p);
            p.setStyle(Paint.Style.FILL);
            // Angka digital.
            String sp = h.speed.getText().toString();
            t.setTypeface(bold);
            t.setTextSize(46 * k);
            t.setColor(kmh > SPEED_HOT ? 0xFFFF453A : TEXT);
            c.drawText(sp, cx, cy + rad * .62f, t);
            t.setTypeface(medium);
            t.setTextSize(12 * k);
            t.setColor(DIM);
            c.drawText(h.getString(R.string.home_speed_unit).toLowerCase(Lang.locale()), cx, cy + rad * .62f + 16 * k, t);
        }
    }

    /** Fix GPS (tiap detik selama home tampil, dari HomeLocation): jarak trip, speedometer, dan mode menyetir. */
    void onFix(Location l, int kmh) {
        long now = SystemClock.elapsedRealtime();
        if (l.hasAccuracy() && l.getAccuracy() <= 50) {
            if (!Double.isNaN(lastLat) && kmh >= 3 && now - lastFixAt < 10_000) {
                tripMovingMs += now - lastFixAt;
                float[] d = new float[1];
                Location.distanceBetween(lastLat, lastLon, l.getLatitude(), l.getLongitude(), d);
                if (d[0] < 120) tripM += d[0]; // loncatan GPS dibuang
            }
            lastLat = l.getLatitude();
            lastLon = l.getLongitude();
            lastFixAt = now;
        }
        if (kmh > tripMaxKmh && kmh < 250) tripMaxKmh = kmh;
        speed.invalidate();
        // Kartu perjalanan: hanya bila angka yang tampil berubah (jarak per 100 m, masa per menit, maks).
        String shown = Math.round(tripM / 100) + "/" + tripMovingMs / 60_000 + "/" + tripMaxKmh;
        if (!shown.equals(driveShown)) {
            driveShown = shown;
            drive.invalidate();
        }
        trackDriving(kmh, now);
    }

    private String driveShown = "";
    private long fastSince, slowSince;

    /** Mode menyetir: ≥ 10 km/j selama 5 detik masuk, < 3 km/j selama 15 detik (atau GPS hilang) keluar. */
    void trackDriving(int kmh, long now) {
        if (h.portrait || edit.on) return;
        if (kmh >= 10) {
            slowSince = 0;
            if (fastSince == 0) fastSince = now;
            if (!driving && now - fastSince > 5000) setDriving(true);
        } else {
            fastSince = 0;
            if (kmh < 3) {
                if (slowSince == 0) slowSince = now;
                if (driving && now - slowSince > 15_000) setDriving(false);
            }
        }
    }

    void setDriving(boolean on) {
        if (driving == on) return;
        driving = on;
        relayout();
    }

    /** Kecepatan basi (GPS hilang, lihat HomeLocation.speedStale). */
    void speedStale() {
        if (speed != null) speed.invalidate();
        if (driving && SystemClock.elapsedRealtime() - HomeLocation.lastKmhAt > 20_000) setDriving(false);
    }

    // ---------------------------------------------------------------- kartu sholat

    /**
     * Waktu sholat berikutnya di atas langit sesuai waktunya (malam berbintang + bulan, fajar, siang + matahari, senja)
     * dengan siluet masjid; lima waktu dalam jalur kaca di bawah. 10 menit sebelum masuk waktu kartu bercahaya; saat
     * azan berkumandang ({@link Azan}) kartu berganti menjadi pemutar azan.
     */
    final class PrayerCard extends HomeViews.PrayerView {
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF(), stop = new RectF();
        private Bitmap sky;
        private String skyKey = "";
        int fg = 0xFFFFFFFF, dim = 0x99EBEBF5;

        PrayerCard() {
            super(HomeCards.this.h, 1);
            setOnLongClickListener(v -> {
                edit.start();
                return true;
            });
            setOnClickListener(v -> { if (Azan.playing && stop.contains(lastX, lastY)) Azan.stop(h); });
        }

        private float lastX, lastY;

        @Override public boolean onTouchEvent(MotionEvent e) {
            lastX = e.getX();
            lastY = e.getY();
            return super.onTouchEvent(e);
        }

        /** Hitung mundur per menit (updateTime), tiap detik hanya saat azan berkumandang (progres). */
        final Runnable azanTick = new Runnable() {
            @Override public void run() {
                invalidate();
                if (Azan.playing) postDelayed(this, 1000);
            }
        };

        @Override void startTicking() {
            invalidate();
            removeCallbacks(azanTick);
            if (Azan.playing) post(azanTick);
        }

        @Override void stopTicking() { removeCallbacks(azanTick); }

        /** 0 malam, 1 fajar, 2 siang, 3 senja. */
        int phase(int now, int[] pray) {
            if (pray == null) return 0;
            if (now >= pray[0] - 20 && now < pray[0] + 60) return 1;
            if (now >= pray[0] + 60 && now < pray[3] - 40) return 2;
            if (now >= pray[3] - 40 && now < pray[4]) return 3;
            return 0;
        }

        /** Langit, bintang, bulan/matahari, dan masjid: digambar sekali per fase dan ukuran. */
        Bitmap sky(int w, int ht, int phase) {
            String key = w + "x" + ht + ":" + phase + ":" + day();
            if (sky != null && key.equals(skyKey)) return sky;
            skyKey = key;
            Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, ht), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            int[][] cols = {{0x8C121A3C, 0x73281E46, 0x735A2C3C}, {0x80283C6E, 0x80B4646E, 0x8CF0A064},
                    {0x8C78B4F0, 0x80C8E1FA, 0x8CFAE6C8}, {0x8C463C78, 0x80B4505A, 0x8CF08246}};
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean azan = phase == 4;
            if (azan) phase = 3;
            q.setShader(new LinearGradient(0, 0, 0, ht, cols[phase], new float[]{0, .7f, 1}, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, w, ht, h.px(24), h.px(24), q);
            q.setShader(null);
            float k = h.dp;
            if (phase == 0) {
                q.setColor(0xFFFFFFFF);
                float[][] st = {{.06f, .12f}, {.19f, .23f}, {.33f, .08f}, {.47f, .18f}, {.59f, .07f}, {.73f, .27f}, {.81f, .1f},
                        {.94f, .22f}, {.14f, .42f}, {.4f, .36f}};
                for (int i = 0; i < st.length; i++) {
                    q.setAlpha(110 + (i * 37) % 120);
                    c.drawCircle(st[i][0] * w, st[i][1] * ht, (1 + (i % 3) * .3f) * k, q);
                }
                q.setAlpha(255);
            }
            // Bulan (malam/senja) atau matahari (fajar/siang), kanan atas.
            float mx = w - h.px(46), my = h.px(40), mr = h.px(20);
            if (azan) {
                // tanpa bulan: tombol henti di kanan atas
            } else if (phase == 0 || phase == 3) {
                Path m = new Path();
                m.addCircle(mx, my, mr, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(mx + mr * .45f, my - mr * .35f, mr * .82f, Path.Direction.CW);
                m.op(cut, Path.Op.DIFFERENCE);
                q.setColor(0xFFF5DEB0);
                c.drawPath(m, q);
            } else {
                q.setColor(0xFFFFC14D);
                c.drawCircle(mx, my, mr * .55f, q);
                q.setStrokeWidth(h.px(3));
                q.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(mx + (float) Math.cos(a) * mr * .8f, my + (float) Math.sin(a) * mr * .8f,
                            mx + (float) Math.cos(a) * mr * 1.1f, my + (float) Math.sin(a) * mr * 1.1f, q);
                }
            }
            boolean light = phase == 2;
            Scenery.mosque(c, w - h.px(10), ht, ht * .78f, light ? 0x593A4A66 : 0xF00A0D18, light ? 0x00000000 : 0x8CFFD38A);
            sky = b;
            return b;
        }

        @Override protected void onDraw(Canvas c) {
            HomeLocation loc = h.loc;
            int[] pray = loc.pray;
            int w = getWidth(), ht = getHeight();
            Calendar cal = Calendar.getInstance();
            int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int ph = Azan.playing ? 4 : phase(now, pray);
            c.drawBitmap(sky(w, ht, ph), 0, 0, null);
            // Teks mengikuti langit: gelap di langit siang yang terang, putih di langit malam/fajar/senja.
            fg = ph == 2 ? 0xFF1C1C1E : 0xFFFFFFFF;
            dim = ph == 2 ? 0xA63C3C43 : 0x99EBEBF5;
            if (pray == null) return;
            int next = loc.nextPrayer(now), shown = next >= 0 ? next : 0;
            int target = next >= 0 ? pray[next] : 1440 + loc.prayTomorrow[0];
            int left = Math.max(0, target - now);
            float pad = h.px(20), x = pad;
            boolean wide = w < ht * 1.3f, compact = ht < h.px(170);
            String place = loc.city != null ? title(loc.city) : String.format(Locale.US, "%.2f, %.2f", loc.lat, loc.lon);
            float base = header(c, t, x, pad, 0xFF34C759, "☾", h.getString(R.string.home_card_prayer) + " · " + place, w - pad * 2 - h.px(56), fg);

            if (Azan.playing) {
                drawAzan(c, w, ht, pad, base);
                return;
            }
            boolean soon = left > 0 && left <= 10;
            if (soon) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(h.px(2.5f));
                p.setColor(alpha(acc(), 0xCC));
                box.set(h.px(1.5f), h.px(1.5f), w - h.px(1.5f), ht - h.px(1.5f));
                c.drawRoundRect(box, h.px(23), h.px(23), p);
                p.setStyle(Paint.Style.FILL);
            }
            String name = title(Lang.prayers[shown]);
            String time = next >= 0 ? loc.prayText[next] : Clock.hm(Clock.twelve(h.prefs), loc.prayTomorrow[0]);
            t.setTextAlign(Paint.Align.LEFT);
            float u = Math.min(1.2f, Math.max(.7f, ht / (float) h.px(260)));
            float y = base + h.px(14);
            if (wide) {
                t.setTypeface(medium);
                t.setTextSize(h.sp(26) * u);
                t.setColor(fg);
                y += t.getTextSize();
                c.drawText(name, x, y, t);
                t.setTypeface(bold);
                t.setTextSize(h.sp(44) * u);
                y += t.getTextSize();
                c.drawText(time, x, y, t);
            } else {
                t.setTypeface(medium);
                t.setTextSize(h.sp(34) * u);
                t.setColor(fg);
                float bigSize = h.sp(56) * u;
                y += bigSize * .9f;
                c.drawText(name, x, y, t);
                float nw = t.measureText(name);
                t.setTypeface(bold);
                t.setTextSize(bigSize);
                c.drawText(time, x + nw + h.px(16), y, t);
            }
            String in = soon ? h.getString(R.string.home_prayer_soon, name, left)
                    : (left >= 60 ? h.getString(R.string.home_left_hm, left / 60, left % 60) : h.getString(R.string.home_left_m, left));
            t.setTypeface(medium);
            t.setTextSize(h.sp(20) * u);
            t.setColor(acc());
            y += t.getTextSize() + h.px(6);
            String lagi = soon ? "" : " " + h.getString(R.string.home_left_suffix);
            c.drawText(in, x, y, t);
            if (!lagi.isEmpty()) {
                float iw = t.measureText(in);
                t.setTypeface(regular);
                t.setTextSize(h.sp(14) * u);
                t.setColor(dim);
                c.drawText(lagi, x + iw, y, t);
            }
            if (driving || compact) return;
            // Jalur lima waktu.
            float stripW = wide ? w - pad * 1.2f : Math.min(w * .66f, h.px(460)), chipH = Math.min(h.px(60), ht * .26f);
            float sx = wide ? pad * .6f : pad - h.px(4), sy = ht - pad * .8f - chipH;
            box.set(sx, sy, sx + stripW, sy + chipH);
            p.setColor(fg == 0xFFFFFFFF ? 0x40000000 : 0x73FFFFFF);
            c.drawRoundRect(box, h.px(18), h.px(18), p);
            float col = (stripW - h.px(8)) / 5f;
            for (int i = 0; i < 5; i++) {
                float cx0 = sx + h.px(4) + i * col;
                if (i == shown) {
                    p.setColor(fg == 0xFFFFFFFF ? 0x29FFFFFF : 0xBFFFFFFF);
                    box.set(cx0, sy + h.px(4), cx0 + col, sy + chipH - h.px(4));
                    c.drawRoundRect(box, h.px(14), h.px(14), p);
                }
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(medium);
                t.setTextSize(Math.min(h.sp(11), col / 6.2f));
                t.setColor(i == shown ? fg : dim);
                c.drawText(title(Lang.prayers[i]), cx0 + col / 2, sy + chipH * .4f, t);
                t.setTypeface(i == shown ? bold : medium);
                t.setTextSize(Math.min(h.sp(19), col / 3.6f));
                t.setColor(pray[i] <= now && i != shown ? dim : fg);
                c.drawText(loc.prayText[i], cx0 + col / 2, sy + chipH * .78f, t);
            }
            t.setTextAlign(Paint.Align.LEFT);
        }

        void drawAzan(Canvas c, int w, int ht, float pad, float base) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(h.px(2.5f));
            p.setColor(0x99FFAA6E);
            box.set(h.px(1.5f), h.px(1.5f), w - h.px(1.5f), ht - h.px(1.5f));
            c.drawRoundRect(box, h.px(23), h.px(23), p);
            p.setStyle(Paint.Style.FILL);
            float y = base + h.px(18), r = h.px(28), cx = pad + r, cy = y + r;
            p.setColor(0x2EFFFFFF);
            c.drawCircle(cx, cy, r + h.px(8), p);
            p.setColor(0x40FFFFFF);
            c.drawCircle(cx, cy, r, p);
            // Pengeras suara.
            p.setColor(0xFFFFFFFF);
            float s = r * .42f;
            Path sp = new Path();
            sp.moveTo(cx - s, cy - s * .45f);
            sp.lineTo(cx - s * .35f, cy - s * .45f);
            sp.lineTo(cx + s * .35f, cy - s);
            sp.lineTo(cx + s * .35f, cy + s);
            sp.lineTo(cx - s * .35f, cy + s * .45f);
            sp.lineTo(cx - s, cy + s * .45f);
            sp.close();
            c.drawPath(sp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(h.px(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            box.set(cx - s * .1f, cy - s * .8f, cx + s * 1.3f, cy + s * .8f);
            c.drawArc(box, -45, 90, false, p);
            p.setStyle(Paint.Style.FILL);
            float tx = cx + r + h.px(18);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(bold);
            t.setTextSize(h.sp(28));
            t.setColor(0xFFFFFFFF);
            c.drawText(h.getString(R.string.azan_now, title(Lang.prayers[Math.max(0, Math.min(4, Azan.prayer))])), tx, cy - h.px(2), t);
            t.setTypeface(medium);
            t.setTextSize(h.sp(14));
            t.setColor(0xFFFFD9B0);
            c.drawText(mmss(Azan.position()) + " / " + mmss(Azan.duration()), tx, cy + t.getTextSize() + h.px(4), t);
            // Gelombang bunyi (statis, bergeser mengikuti detik).
            float wy = cy + r + h.px(34), bx = pad;
            long sec = Azan.position() / 250;
            for (int i = 0; i < 22 && bx < w * .62f; i++) {
                float hh = h.px(8) + (float) Math.abs(Math.sin((i + sec) * .9) * Math.cos(i * .37)) * h.px(30);
                p.setColor(0xD9FFE6C8);
                c.drawRoundRect(bx, wy - hh / 2, bx + h.px(5), wy + hh / 2, h.px(3), h.px(3), p);
                bx += h.px(10);
            }
            // Tombol henti.
            t.setTypeface(medium);
            t.setTextSize(h.sp(14));
            String label = "■  " + h.getString(R.string.azan_stop);
            float bw = t.measureText(label) + h.px(36), bh = h.px(42);
            stop.set(w - pad - bw, pad - h.px(4), w - pad, pad - h.px(4) + bh);
            p.setColor(0x59000000);
            c.drawRoundRect(stop, bh / 2, bh / 2, p);
            t.setColor(0xFFFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = t.getFontMetrics();
            c.drawText(label, stop.centerX(), stop.centerY() - (fm.ascent + fm.descent) / 2, t);
            t.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ---------------------------------------------------------------- cuaca (Open-Meteo, tanpa kunci API)

    static final long WEATHER_MS = 30 * 60_000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static boolean weatherBusy;

    /**
     * Cuaca sekarang + 6 jam ke depan dari Open-Meteo. Disimpan di prefs: "wxTemp/wxCode/wxDay" sekarang, "wxHourly"
     * berisi baris "jam|kode|suhu|peluang hujan|siang" dipisah titik koma (jam lokal headunit, 0–23).
     */
    void refreshWeather(boolean force) {
        SharedPreferences pr = h.prefs;
        double lat = h.loc.lat, lon = h.loc.lon;
        if (lat == 0 && lon == 0) return;
        float[] d = new float[1];
        Location.distanceBetween(lat, lon, pr.getFloat("wxLat", 0), pr.getFloat("wxLon", 0), d);
        boolean fresh = System.currentTimeMillis() - pr.getLong("wxAt", 0) < WEATHER_MS && d[0] < 10_000 && pr.contains("wxHourly");
        if ((fresh && !force) || weatherBusy) return;
        weatherBusy = true;
        NET.execute(() -> {
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,"
                        + "precipitation_probability,is_day&forecast_hours=8&timezone=auto", lat, lon);
                org.json.JSONObject root = new org.json.JSONObject(get(url));
                org.json.JSONObject cur = root.getJSONObject("current");
                org.json.JSONObject hr = root.getJSONObject("hourly");
                org.json.JSONArray time = hr.getJSONArray("time"), temp = hr.getJSONArray("temperature_2m"),
                        code = hr.getJSONArray("weather_code"), prob = hr.getJSONArray("precipitation_probability"),
                        isDay = hr.getJSONArray("is_day");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < time.length(); i++) {
                    String ts = time.getString(i); // "2026-11-07T22:00"
                    int hour = Integer.parseInt(ts.substring(ts.indexOf('T') + 1, ts.indexOf('T') + 3));
                    if (sb.length() > 0) sb.append(';');
                    sb.append(hour).append('|').append(code.optInt(i)).append('|').append(Math.round(temp.optDouble(i)))
                            .append('|').append(prob.optInt(i)).append('|').append(isDay.optInt(i, 1));
                }
                pr.edit().putFloat("wxTemp", (float) cur.getDouble("temperature_2m")).putInt("wxCode", cur.getInt("weather_code"))
                        .putBoolean("wxDay", cur.optInt("is_day", 1) == 1).putString("wxHourly", sb.toString())
                        .putLong("wxAt", System.currentTimeMillis()).putFloat("wxLat", (float) lat).putFloat("wxLon", (float) lon)
                        .apply();
                h.runOnUiThread(() -> { if (drive != null) drive.invalidate(); });
            } catch (Exception ignored) { // tanpa internet: nilai terakhir tetap tampil
            } finally {
                weatherBusy = false;
            }
        });
    }

    private static String get(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("User-Agent", "KlikDash (Android headunit launcher)");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Satu jam ramalan. */
    static final class Hour {
        int hour, code, temp, prob;
        boolean day;
    }

    /** Ramalan mulai jam sekarang ("Kini" = cuaca sekarang), paling banyak {@code n} jam. */
    List<Hour> hours(int n) {
        List<Hour> out = new ArrayList<>();
        SharedPreferences pr = h.prefs;
        if (!pr.contains("wxTemp")) return out;
        int nowH = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        Hour first = new Hour();
        first.hour = -1;
        first.code = pr.getInt("wxCode", 0);
        first.temp = Math.round(pr.getFloat("wxTemp", 0));
        first.day = pr.getBoolean("wxDay", true);
        String hs = pr.getString("wxHourly", "");
        List<Hour> all = new ArrayList<>();
        for (String row : hs.split(";")) {
            String[] f = row.split("\\|");
            if (f.length < 5) continue;
            Hour x = new Hour();
            x.hour = Integer.parseInt(f[0]);
            x.code = Integer.parseInt(f[1]);
            x.temp = Integer.parseInt(f[2]);
            x.prob = Integer.parseInt(f[3]);
            x.day = "1".equals(f[4]);
            all.add(x);
        }
        int start = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).hour == nowH) { start = i; break; }
        if (start >= 0) first.prob = all.get(start).prob;
        out.add(first);
        if (start >= 0) for (int i = start + 1; i < all.size() && out.size() < n; i++) out.add(all.get(i));
        return out;
    }

    /** Ikon cuaca sederhana yang digambar sendiri: matahari/bulan, awan, kabut, hujan, petir. */
    static void drawWeatherIcon(Canvas c, Paint p, int code, boolean day, float cx, float cy, float r, float dp) {
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.ROUND);
        boolean clear = code == 0, partly = code == 1 || code == 2, fog = code == 45 || code == 48;
        boolean rain = code >= 51 && code <= 67 || code >= 80 && code <= 82, storm = code >= 95;
        if (clear || partly) {
            float sx = partly ? cx - r * .3f : cx, sy = partly ? cy - r * .3f : cy, sr = r * (partly ? .38f : .5f);
            if (day) {
                p.setColor(0xFFFFC14D);
                c.drawCircle(sx, sy, sr, p);
            } else {
                Path m = new Path();
                m.addCircle(sx, sy, sr, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(sx + sr * .5f, sy - sr * .35f, sr * .8f, Path.Direction.CW);
                m.op(cut, Path.Op.DIFFERENCE);
                p.setColor(0xFFF5DEB0);
                c.drawPath(m, p);
            }
            if (clear) return;
        }
        int cloud = fog ? 0xFFB8BCC4 : rain || storm ? 0xFFC9CED8 : 0xFFE6E8EC;
        float top = fog ? cy - r * .55f : cy - r * .35f;
        p.setColor(cloud);
        c.drawCircle(cx - r * .35f, top + r * .35f, r * .32f, p);
        c.drawCircle(cx + r * .05f, top + r * .2f, r * .42f, p);
        c.drawCircle(cx + r * .45f, top + r * .38f, r * .3f, p);
        c.drawRect(cx - r * .35f, top + r * .35f, cx + r * .45f, top + r * .67f, p);
        p.setStrokeWidth(2 * dp);
        if (fog) {
            for (int i = 0; i < 3; i++) {
                float y = cy + r * (.3f + i * .25f);
                c.drawLine(cx - r * .7f, y, cx + r * .7f, y, p);
            }
        } else if (rain || storm) {
            p.setColor(0xFF7FB8FF);
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * r * .32f;
                c.drawLine(x, cy + r * .45f, x - r * .1f, cy + r * .8f, p);
            }
            if (storm) {
                p.setColor(0xFFFFC14D);
                c.drawLine(cx + r * .1f, cy + r * .35f, cx - r * .1f, cy + r * .65f, p);
                c.drawLine(cx - r * .1f, cy + r * .65f, cx + r * .1f, cy + r * .65f, p);
                c.drawLine(cx + r * .1f, cy + r * .65f, cx - r * .05f, cy + r * .95f, p);
            }
        }
    }

    // ---------------------------------------------------------------- kartu Zlink, perjalanan, cuaca

    void openIntent(Intent i) {
        try {
            h.userLaunch = true;
            h.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            h.userLaunch = false;
            Notice.info(h, h.getString(R.string.home_cannot_open));
        }
    }

    static final String ZLINK = "com.zjinnova.zlink";

    /**
     * Pengganti peta (headunit tidak kuat menjalankan Waze/Maps; navigasi lewat Zlink di HP): tombol besar Zlink,
     * ringkasan perjalanan dari GPS (tekan lama: atur ulang), dan cuaca per jam dengan peringatan hujan.
     */
    final class DriveCard extends View {
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF zlink = new RectF(), stats = new RectF(), fc = new RectF(), box = new RectF();
        RectF pressed;
        private float downX, downY;
        private boolean longDone;
        private final Runnable longPress = () -> {
            if (pressed != stats) return;
            longDone = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            resetTrip();
            invalidate();
            Notice.ok(h, h.getString(R.string.home_drive_reset));
        };

        DriveCard() {
            super(h);
            setClickable(true);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    longDone = false;
                    pressed = zlink.contains(downX, downY) ? zlink : stats.contains(downX, downY) ? stats : null;
                    if (pressed == stats) postDelayed(longPress, 700);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pressed != null && Math.hypot(e.getX() - downX, e.getY() - downY) > h.px(14)) {
                        removeCallbacks(longPress);
                        pressed = null;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    RectF hit = pressed;
                    pressed = null;
                    invalidate();
                    if (longDone || hit == null) return true;
                    if (hit == zlink) openZlink();
                    else Notice.info(h, h.getString(R.string.home_drive_reset_hint));
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    pressed = null;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        void openZlink() {
            Intent i = h.getPackageManager().getLaunchIntentForPackage(ZLINK);
            if (i == null) Notice.info(h, h.getString(R.string.home_zlink_missing));
            else openIntent(i);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), ht = getHeight();
            boolean narrow = w < h.px(340);
            float pad = h.px(14), gap = h.px(10);
            float zh = Math.min(h.px(80), ht * (narrow ? .22f : .2f));
            zlink.set(pad, pad, w - pad, pad + zh);
            chip(c, p, zlink, h.px(20), pressed == zlink);
            // Ikon Zlink: HP dengan sinyal, di kotak bulat oranye.
            float ic = Math.min(h.px(48), zh * .66f), ix = zlink.left + h.px(14), iy = zlink.centerY() - ic / 2;
            box.set(ix, iy, ix + ic, iy + ic);
            p.setColor(0xFF000000);
            p.setShader(new LinearGradient(0, iy, 0, iy + ic, 0xFFFFB27A, 0xFFFF7A45, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, ic * .235f, ic * .235f, p);
            p.setShader(null);
            float k = ic / 48f;
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(ix + 13 * k, iy + 9 * k, ix + 28 * k, iy + 37 * k, 3.5f * k, 3.5f * k, p);
            p.setColor(0xFFFF8A5B);
            c.drawRect(ix + 15.5f * k, iy + 12.5f * k, ix + 25.5f * k, iy + 31.5f * k, p);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.6f * k);
            p.setStrokeCap(Paint.Cap.ROUND);
            box.set(ix + 24 * k, iy + 15 * k, ix + 40 * k, iy + 31 * k);
            c.drawArc(box, -50, 100, false, p);
            box.set(ix + 22.5f * k, iy + 10 * k, ix + 48.5f * k, iy + 36 * k);
            c.drawArc(box, -50, 100, false, p);
            p.setStyle(Paint.Style.FILL);
            float tx = ix + ic + h.px(14), tAvail = zlink.right - tx - h.px(30);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(bold);
            t.setTextSize(Math.min(h.sp(20), zh * .28f));
            t.setColor(TEXT);
            c.drawText(h.getString(R.string.home_zlink), tx, zlink.centerY() - h.px(1), t);
            t.setTypeface(regular);
            t.setTextSize(Math.min(h.sp(13), zh * .18f));
            t.setColor(DIM);
            ellipsized(c, t, h.getString(R.string.home_zlink_sub), tx, zlink.centerY() + t.getTextSize() + h.px(4), tAvail);
            t.setTextAlign(Paint.Align.RIGHT);
            t.setTextSize(Math.min(h.sp(28), zh * .4f));
            Paint.FontMetrics fm = t.getFontMetrics();
            c.drawText("›", zlink.right - h.px(16), zlink.centerY() - (fm.ascent + fm.descent) / 2, t);
            t.setTextAlign(Paint.Align.LEFT);

            float y = zlink.bottom + gap;
            if (!narrow) {
                // Perjalanan.
                float sh = Math.min(h.px(78), ht * .22f);
                stats.set(pad, y, w - pad, y + sh);
                if (pressed == stats) chip(c, p, stats, h.px(14), true);
                float hb = header(c, t, pad, y, 0xFF0A84FF, "➜", title(h.getString(R.string.home_drive)), w - pad * 2);
                long ms = tripMovingMs;
                double hours = ms / 3_600_000.0;
                String[] names = {h.getString(R.string.home_drive_dist), h.getString(R.string.home_drive_time),
                        h.getString(R.string.home_drive_avg), h.getString(R.string.home_drive_max)};
                String unitKmh = h.getString(R.string.home_speed_unit).toLowerCase(Lang.locale());
                String[] vals = {String.format(Locale.US, "%.1f", tripM / 1000), String.format(Locale.US, "%d:%02d", ms / 3_600_000,
                        ms / 60_000 % 60), hours > 0.01 ? String.valueOf(Math.round(tripM / 1000 / hours)) : "0", String.valueOf(tripMaxKmh)};
                String[] units = {"km", "", unitKmh, unitKmh};
                float col = (w - pad * 2) / 4f, ly = hb + h.px(8);
                for (int i = 0; i < 4; i++) {
                    float x = pad + i * col;
                    t.setTypeface(medium);
                    t.setTextSize(h.sp(11));
                    t.setColor(DIM);
                    c.drawText(title(names[i]), x, ly + t.getTextSize(), t);
                    t.setTypeface(bold);
                    t.setTextSize(Math.min(h.sp(26), col * .3f));
                    t.setColor(TEXT);
                    float vy = ly + h.px(14) + t.getTextSize();
                    c.drawText(vals[i], x, vy, t);
                    float vw = t.measureText(vals[i]);
                    t.setTypeface(medium);
                    t.setTextSize(h.sp(11));
                    t.setColor(DIM);
                    if (!units[i].isEmpty() && vw + h.px(4) + t.measureText(units[i]) < col) c.drawText(units[i], x + vw + h.px(3), vy, t);
                }
                y = stats.bottom + gap;
            } else {
                stats.setEmpty();
            }

            // Cuaca per jam.
            fc.set(pad, y, w - pad, ht - pad);
            if (fc.height() < h.px(70)) return;
            p.setColor(day() ? 0x73FFFFFF : 0x2E000000);
            c.drawRoundRect(fc, h.px(20), h.px(20), p);
            String place = h.loc.city != null ? title(h.loc.city) : "";
            List<Hour> hs = hours(narrow ? 4 : 6);
            Hour rain = null;
            for (int i = 1; i < hs.size(); i++) if (hs.get(i).prob >= 50) { rain = hs.get(i); break; }
            String pill = rain == null ? null : (narrow ? "☂ " : "☂ " + h.getString(R.string.home_rain) + " ") + two(rain.hour) + ":00";
            float pillW = 0;
            if (pill != null) {
                t.setTypeface(bold);
                t.setTextSize(h.sp(12));
                pillW = t.measureText(pill) + h.px(18);
            }
            float hb = header(c, t, fc.left + h.px(12), fc.top + h.px(10), 0xFF5AC8FA, "☁",
                    h.getString(R.string.home_card_weather) + (narrow || place.isEmpty() ? "" : " · " + place), fc.width() - h.px(30) - pillW);
            if (pill != null) {
                float px = fc.right - h.px(12) - pillW, py = fc.top + h.px(10);
                box.set(px, py, px + pillW, py + h.px(22));
                p.setColor(0xFF0A84FF);
                c.drawRoundRect(box, h.px(11), h.px(11), p);
                t.setColor(0xFFFFFFFF);
                t.setTextAlign(Paint.Align.CENTER);
                fm = t.getFontMetrics();
                c.drawText(pill, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2, t);
                t.setTextAlign(Paint.Align.LEFT);
            }
            if (hs.isEmpty()) {
                t.setTypeface(regular);
                t.setTextSize(h.sp(13));
                t.setColor(DIM);
                c.drawText(h.getString(R.string.home_weather_wait), fc.left + h.px(14), hb + h.px(30), t);
                return;
            }
            float top = hb + h.px(10), bottom = fc.bottom - h.px(8), colW = (fc.width() - h.px(16)) / (narrow ? 4 : 6);
            float rowH = bottom - top;
            for (int i = 0; i < hs.size(); i++) {
                Hour x = hs.get(i);
                float cx0 = fc.left + h.px(8) + i * colW, cx = cx0 + colW / 2;
                if (i == 0) {
                    box.set(cx0 + h.px(2), top, cx0 + colW - h.px(2), bottom);
                    p.setColor(day() ? 0xBFFFFFFF : 0x1FFFFFFF);
                    c.drawRoundRect(box, h.px(14), h.px(14), p);
                }
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(i == 0 ? bold : medium);
                t.setTextSize(h.sp(11));
                t.setColor(i == 0 ? TEXT : DIM);
                c.drawText(i == 0 ? h.getString(R.string.home_now) : two(x.hour) + ":00", cx, top + rowH * .16f, t);
                float r = Math.min(h.px(17), Math.min(colW * .3f, rowH * .14f));
                drawWeatherIcon(c, p, x.code, x.day, cx, top + rowH * .4f, r, h.dp);
                t.setTypeface(bold);
                t.setTextSize(Math.min(h.sp(20), colW * .3f));
                t.setColor(TEXT);
                c.drawText(x.temp + "°", cx, top + rowH * .74f, t);
                t.setTypeface(bold);
                t.setTextSize(h.sp(10.5f));
                t.setColor(day() ? 0xFF0062CC : 0xFF0A84FF);
                c.drawText(x.prob + "%", cx, top + rowH * .94f, t);
            }
            t.setTextAlign(Paint.Align.LEFT);
        }
    }

    static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    // ---------------------------------------------------------------- kartu musik

    final class MediaCard extends View {
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF(), prev = new RectF(), play = new RectF(), next = new RectF(), qa = new RectF(), qb = new RectF();
        final Path shape = new Path();
        MediaNotch.Source src0;
        private String iconPkg;
        private Drawable appIcon;
        private float downX, downY;
        private boolean longDone;
        private final Runnable longPress = () -> {
            longDone = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (src0 != null) src0.stop(h);
        };
        /** Progres lagu berjalan: digambar ulang tiap detik selama diputar dan home tampil. */
        final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                if (ticking) postDelayed(this, 1000);
            }
        };
        boolean ticking;

        MediaCard() {
            super(h);
            setClickable(true);
        }

        void refresh() {
            src0 = MediaNotch.current();
            MediaMetadata md = src0 == MediaListener.SOURCE && MediaListener.current != null ? MediaListener.current.getMetadata() : null;
            boolean want = h.resumed && md != null && src0.playing() && md.getLong(MediaMetadata.METADATA_KEY_DURATION) > 0;
            if (want != ticking) {
                ticking = want;
                removeCallbacks(tick);
                if (want) postDelayed(tick, 1000);
            }
            // Warna aksen dari sampul: dihitung sekali per gambar; kartu lain digambar ulang bila berubah.
            Bitmap cover = md != null ? cover(md) : null;
            int a = cover == null ? 0 : accentOf(cover);
            if (a != albumAcc) {
                albumAcc = a;
                speed.invalidate();
                prayer.invalidate();
                drive.invalidate();
                volume.invalidate();
                brightness.invalidate();
            }
            invalidate();
        }

        void stop() {
            ticking = false;
            removeCallbacks(tick);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    longDone = false;
                    if (src0 != null && !inButtons(downX, downY)) postDelayed(longPress, 600);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > h.px(12)) removeCallbacks(longPress);
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (!longDone && Math.hypot(e.getX() - downX, e.getY() - downY) <= h.px(12)) tap(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    return true;
            }
            return super.onTouchEvent(e);
        }

        boolean inButtons(float x, float y) {
            return grow(prev).contains(x, y) || grow(play).contains(x, y) || grow(next).contains(x, y);
        }

        private final RectF hit = new RectF();

        private RectF grow(RectF r) {
            hit.set(r);
            hit.inset(-h.px(8), -h.px(8));
            return hit;
        }

        void tap(float x, float y) {
            MediaNotch.Source s = src0;
            if (grow(play).contains(x, y)) {
                if (s != null) s.toggle(h); else mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            } else if (grow(next).contains(x, y)) {
                if (s != null) s.next(h); else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            } else if (grow(prev).contains(x, y)) {
                if (s == MediaListener.SOURCE && MediaListener.current != null) {
                    MediaListener.current.getTransportControls().skipToPrevious();
                } else if (s == MediaNotch.QURAN) QuranService.send(h, QuranService.ACTION_PREV);
                else if (s == MediaNotch.DZIKIR) DzikirService.send(h, DzikirService.ACTION_PREV);
                else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            } else if (s == null && qa.contains(x, y)) {
                openIntent(new Intent(h, DzikirActivity.class).putExtra("period", h.loc.dzikirPagi() ? DzikirActivity.PAGI : DzikirActivity.PETANG));
            } else if (s == null && qb.contains(x, y)) {
                openIntent(new Intent(h, QuranActivity.class));
            } else if (s != null) {
                h.userLaunch = true;
                s.open(h);
            } else if (!MediaListener.connected()) {
                openIntent(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } else {
                h.drawer.open(-1);
            }
            postDelayed(this::refresh, 300);
        }

        void mediaKey(int code) {
            AudioManager am = (AudioManager) h.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), ht = getHeight();
            boolean stack = w < ht * 1.3f; // kartu sempit (layar lebar): sampul di kanan atas, teks di bawah
            float pad = h.px(stack ? 14 : 20);
            MediaNotch.Source s = src0;
            MediaController mc = s == MediaListener.SOURCE ? MediaListener.current : null;
            MediaMetadata md = mc != null ? mc.getMetadata() : null;
            Bitmap cover = md != null ? cover(md) : null;

            // Latar: warna sampul memudar ke kanan bawah.
            if (cover != null) {
                int tintC = tintOf(cover);
                if (tint == null || tintFor != tintC || tintW != w || tintH != ht) {
                    tintFor = tintC;
                    tintW = w;
                    tintH = ht;
                    tint = new LinearGradient(0, 0, w * .85f, ht, alpha(tintC, 0x8C), alpha(tintC, 0), Shader.TileMode.CLAMP);
                }
                p.setColor(0xFF000000);
                p.setShader(tint);
                c.drawRoundRect(0, 0, w, ht, h.px(24), h.px(24), p);
                p.setShader(null);
            }

            // Sampul.
            float art = stack ? Math.min(w * .34f, ht * .34f) : Math.min(w * .3f, ht - pad * 2);
            if (stack) box.set(w - pad - art, pad, w - pad, pad + art);
            else box.set(pad, (ht - art) / 2f, pad + art, (ht + art) / 2f);
            if (cover != null) c.drawBitmap(rounded(cover, Math.round(art)), box.left, box.top, null);
            else {
                if (placeholder == null || placeholderSize != Math.round(art)) {
                    placeholderSize = Math.round(art);
                    placeholder = new LinearGradient(0, 0, art, art, 0xFF5B4B8A, 0xFF1E2238, Shader.TileMode.CLAMP);
                }
                p.setColor(0xFF000000);
                p.setShader(placeholder);
                c.save();
                c.translate(box.left, box.top);
                c.drawRoundRect(0, 0, art, art, h.px(16), h.px(16), p);
                c.restore();
                p.setShader(null);
                Drawable own = s == MediaNotch.QURAN ? h.getDrawable(R.drawable.ic_quran)
                        : s == MediaNotch.DZIKIR ? h.getDrawable(R.drawable.ic_dzikir_tile) : null;
                if (own != null) {
                    int is = Math.round(art * .42f);
                    own.setBounds(Math.round(box.centerX() - is / 2f), Math.round(box.centerY() - is / 2f),
                            Math.round(box.centerX() + is / 2f), Math.round(box.centerY() + is / 2f));
                    own.setTint(0xFFFFFFFF);
                    own.draw(c);
                } else note(c, box.centerX(), box.centerY(), art * .2f);
            }

            float x = stack ? pad : box.right + h.px(22), right = stack ? (box.left - h.px(10)) : w - pad, avail = right - x;
            float fullRight = w - pad;

            // Aplikasi.
            String app, sub, title, artist;
            if (s == null) {
                app = h.getString(R.string.home_media_none_app);
                boolean ok = MediaListener.connected();
                sub = h.getString(ok ? R.string.home_media_idle : R.string.home_media_allow);
                title = null;
                artist = null;
            } else if (mc != null) {
                app = MediaListener.appName(h, mc.getPackageName());
                sub = h.getString(s.playing() ? R.string.home_media_playing : R.string.home_media_paused);
                String tt = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
                String ar = md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
                if (ar == null && md != null) ar = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                title = tt != null && !tt.isEmpty() ? tt : app;
                artist = ar != null ? ar : "";
            } else {
                app = title(h.getString(s == MediaNotch.QURAN ? R.string.home_tile_quran : R.string.home_tile_dzikir));
                sub = s.playing() ? s.state(h) : h.getString(R.string.home_notch_paused);
                title = s.title(h);
                artist = "";
            }
            float iconS = h.px(30), y = pad;
            Drawable icon = mc != null ? icon(mc.getPackageName()) : h.getDrawable(R.drawable.ic_launcher);
            if (icon != null) {
                c.drawBitmap(squircle(mc != null ? mc.getPackageName() : "klikdash", icon, Math.round(iconS)), x, y, null);
            }
            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(bold);
            t.setTextSize(h.sp(15));
            t.setColor(TEXT);
            float tx = x + iconS + h.px(10);
            ellipsized(c, t, app, tx, y + h.px(13), right - tx);
            t.setTypeface(regular);
            t.setTextSize(h.sp(12.5f));
            t.setColor(DIM);
            ellipsized(c, t, sub, tx, y + iconS, right - tx);

            float btn = Math.min(h.px(72), Math.max(h.px(48), ht * .26f)), by = ht - pad - btn / 2f;
            if (title == null) {
                // Tiada lagu: tombol cepat Zikir (pagi/petang) dan Murattal.
                float qy = Math.max(y + iconS + h.px(16), stack ? box.bottom + h.px(10) : y + iconS + h.px(16));
                float qh = Math.min(h.px(64), by - btn / 2f - qy - h.px(10)), qx = x, qw = (fullRight - (stack ? pad : x) - h.px(10)) / 2f;
                if (stack) qx = pad;
                if (qh > h.px(36)) {
                    qa.set(qx, qy, qx + qw, qy + qh);
                    qb.set(qa.right + h.px(10), qy, qa.right + h.px(10) + qw, qy + qh);
                    quick(c, qa, R.drawable.ic_dzikir_tile, h.getString(h.loc.dzikirPagi() ? R.string.home_quick_dzikir_pagi
                            : R.string.home_quick_dzikir_petang));
                    quick(c, qb, R.drawable.ic_quran, h.getString(R.string.home_quick_murattal));
                } else {
                    qa.setEmpty();
                    qb.setEmpty();
                }
            } else {
                qa.setEmpty();
                qb.setEmpty();
                float ty = stack ? box.bottom + h.px(8) : y + iconS + h.px(16);
                float tAvail = stack ? fullRight - pad : avail;
                t.setTypeface(bold);
                t.setTextSize(fit(title, h.sp(stack ? 22 : 32), tAvail));
                t.setColor(TEXT);
                ty += t.getTextSize();
                ellipsized(c, t, title, stack ? pad : x, ty, tAvail);
                t.setTypeface(regular);
                t.setTextSize(h.sp(stack ? 14 : 17));
                t.setColor(DIM);
                ty += t.getTextSize() + h.px(6);
                ellipsized(c, t, artist, stack ? pad : x, ty, tAvail);

                // Progres.
                long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0, pos = 0;
                PlaybackState ps = mc != null ? mc.getPlaybackState() : null;
                if (ps != null) {
                    pos = ps.getPosition();
                    if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                        pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                    }
                }
                float px0 = stack ? pad : x, barY = by - btn / 2f - h.px(30);
                if (barY > ty + h.px(10) && (dur > 0 || mc != null)) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(day() ? 0x1F000000 : 0x38FFFFFF);
                    box.set(px0, barY - h.px(2.5f), fullRight, barY + h.px(2.5f));
                    c.drawRoundRect(box, h.px(2.5f), h.px(2.5f), p);
                    if (dur > 0) {
                        float f = Math.max(0, Math.min(1, pos / (float) dur));
                        p.setColor(TEXT);
                        box.set(px0, barY - h.px(2.5f), px0 + (fullRight - px0) * f, barY + h.px(2.5f));
                        c.drawRoundRect(box, h.px(2.5f), h.px(2.5f), p);
                    }
                    t.setTypeface(medium);
                    t.setTextSize(h.sp(12));
                    t.setColor(DIM);
                    c.drawText(mmss(pos), px0, barY + h.px(18), t);
                    if (dur > 0) {
                        t.setTextAlign(Paint.Align.RIGHT);
                        c.drawText(mmss(dur), fullRight, barY + h.px(18), t);
                        t.setTextAlign(Paint.Align.LEFT);
                    }
                }
            }

            // Tombol: sebelum/berikut berlatar bulat, main/jeda bulat penuh warna teks.
            float bx0 = stack ? pad : x, cx = bx0 + (fullRight - bx0) / 2f, small = btn * .82f, space = Math.min((fullRight - bx0) / 3f, btn * 1.55f);
            play.set(cx - btn / 2f, by - btn / 2f, cx + btn / 2f, by + btn / 2f);
            prev.set(cx - space - small / 2f, by - small / 2f, cx - space + small / 2f, by + small / 2f);
            next.set(cx + space - small / 2f, by - small / 2f, cx + space + small / 2f, by + small / 2f);
            p.setColor(day() ? 0x99FFFFFF : 0x1FFFFFFF);
            c.drawCircle(prev.centerX(), by, small / 2f, p);
            c.drawCircle(next.centerX(), by, small / 2f, p);
            p.setColor(TEXT);
            c.drawCircle(cx, by, btn / 2f, p);
            float g = btn * .18f;
            boolean playing = s != null && s.playing();
            p.setColor(day() ? 0xFFFFFFFF : 0xFF1B1B1F);
            shape.rewind();
            if (playing) {
                c.drawRoundRect(cx - g * .85f, by - g, cx - g * .25f, by + g, g * .15f, g * .15f, p);
                c.drawRoundRect(cx + g * .25f, by - g, cx + g * .85f, by + g, g * .15f, g * .15f, p);
            } else {
                shape.moveTo(cx - g * .55f, by - g);
                shape.lineTo(cx + g * 1.05f, by);
                shape.lineTo(cx - g * .55f, by + g);
                shape.close();
                c.drawPath(shape, p);
            }
            p.setColor(TEXT);
            skip(c, prev.centerX(), by, g * .85f, false);
            skip(c, next.centerX(), by, g * .85f, true);
        }

        void quick(Canvas c, RectF r, int iconRes, String label) {
            chip(c, p, r, h.px(16), false);
            Drawable d = h.getDrawable(iconRes).mutate();
            float s = Math.min(h.px(28), r.height() * .45f), ix = r.left + h.px(14), iy = r.centerY() - s / 2;
            d.setBounds(Math.round(ix), Math.round(iy), Math.round(ix + s), Math.round(iy + s));
            d.setTint(acc());
            d.draw(c);
            t.setTypeface(bold);
            t.setTextSize(Math.min(h.sp(16), r.height() * .28f));
            t.setColor(TEXT);
            Paint.FontMetrics fm = t.getFontMetrics();
            ellipsized(c, t, label, ix + s + h.px(10), r.centerY() - (fm.ascent + fm.descent) / 2, r.right - ix - s - h.px(20));
        }

        private void skip(Canvas c, float cx, float cy, float g, boolean fwd) {
            shape.rewind();
            float d = fwd ? 1 : -1;
            shape.moveTo(cx - d * g * .8f, cy - g);
            shape.lineTo(cx + d * g * .5f, cy);
            shape.lineTo(cx - d * g * .8f, cy + g);
            shape.close();
            c.drawPath(shape, p);
            c.drawRect(Math.min(cx + d * g * .55f, cx + d * g * .9f), cy - g, Math.max(cx + d * g * .55f, cx + d * g * .9f), cy + g, p);
        }

        private void note(Canvas c, float cx, float cy, float s) {
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx - s * .35f, cy + s * .75f, s * .45f, p);
            c.drawRect(cx - s * .02f, cy - s * 1.1f, cx + s * .12f, cy + s * .75f, p);
            shape.rewind();
            shape.moveTo(cx + s * .12f, cy - s * 1.1f);
            shape.quadTo(cx + s * .75f, cy - s * .7f, cx + s * .6f, cy - s * .1f);
            shape.quadTo(cx + s * .5f, cy - s * .55f, cx + s * .12f, cy - s * .6f);
            shape.close();
            c.drawPath(shape, p);
        }

        private float fit(String s, float size, float avail) {
            t.setTextSize(size);
            float tw = t.measureText(s);
            return tw <= avail ? size : Math.max(size * .72f, size * avail / tw);
        }

        Drawable icon(String pkg) {
            if (!pkg.equals(iconPkg)) {
                iconPkg = pkg;
                try {
                    appIcon = h.getPackageManager().getApplicationIcon(pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    appIcon = null;
                }
            }
            return appIcon;
        }

        private Shader placeholder, tint;
        private int placeholderSize, tintFor, tintW, tintH;
        private Bitmap coverSrc, coverOut, toneSrc;
        private int tone, toneAcc;

        /** Sampul seukuran kotak dengan sudut bulat; dibuat ulang hanya bila gambar atau ukurannya berganti. */
        Bitmap rounded(Bitmap src, int size) {
            if (src == coverSrc && coverOut != null && coverOut.getWidth() == size) return coverOut;
            coverSrc = src;
            Bitmap out = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size), Bitmap.Config.ARGB_8888);
            int bw = src.getWidth(), bh = src.getHeight(), side = Math.min(bw, bh);
            BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float k = size / (float) side;
            m.setScale(k, k);
            m.postTranslate(-(bw - side) / 2f * k, -(bh - side) / 2f * k);
            sh.setLocalMatrix(m);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            q.setShader(sh);
            new Canvas(out).drawRoundRect(0, 0, size, size, h.px(16), h.px(16), q);
            coverOut = out;
            return out;
        }

        /** Warna paling "hidup" di sampul (8×8 piksel), sekali per gambar: latar kartu (gelap) dan aksen (terang). */
        private void tones(Bitmap src) {
            if (src == toneSrc) return;
            toneSrc = src;
            Bitmap tiny = Bitmap.createScaledBitmap(src, 8, 8, true);
            float[] hsv = new float[3], best = null;
            float score = -1;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    Color.colorToHSV(tiny.getPixel(x, y), hsv);
                    float sc = hsv[1] * (hsv[2] < .15f ? 0 : hsv[2]);
                    if (sc > score) {
                        score = sc;
                        best = hsv.clone();
                    }
                }
            }
            if (tiny != src) tiny.recycle();
            if (best == null || best[1] < .12f) {
                tone = 0xFF2A2C30;
                toneAcc = 0;
                return;
            }
            float[] bg = best.clone();
            bg[1] = Math.min(bg[1], .75f);
            bg[2] = Math.min(Math.max(bg[2], .35f), .55f);
            tone = Color.HSVToColor(bg);
            best[1] = Math.max(Math.min(best[1], .85f), .5f);
            best[2] = .97f;
            toneAcc = Color.HSVToColor(best);
        }

        int tintOf(Bitmap src) { tones(src); return tone; }

        int accentOf(Bitmap src) { tones(src); return toneAcc; }

        Bitmap cover(MediaMetadata md) {
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            return b;
        }
    }

    static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    void mediaChanged() {
        if (media != null) media.refresh();
        if (prayer != null) prayer.startTicking();
    }

    // ---------------------------------------------------------------- volume & kecerahan (gaya Control Center)

    /** Jalur tebal yang terisi putih sesuai nilainya; seret di mana saja untuk mengubah. Nilai milik QuickPanel. */
    final class Slider extends View {
        final boolean vol;
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF box = new RectF();
        float downX;
        boolean moved;

        Slider(boolean vol) {
            super(h);
            this.vol = vol;
            setContentDescription(h.getString(vol ? R.string.quick_volume : R.string.quick_brightness));
        }

        QuickPanel.Level level() { return vol ? h.quick.volume : h.quick.brightness; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    moved = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (moved || Math.abs(e.getX() - downX) > h.px(8)) {
                        moved = true;
                        pick(e.getX());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        // Ketuk ikon speaker: bisu. Ketuk di tempat lain: lompat ke posisi itu.
                        if (vol && e.getX() < getHeight()) {
                            h.quick.toggleMute();
                            levels();
                        } else pick(e.getX());
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return super.onTouchEvent(e);
        }

        void pick(float x) {
            QuickPanel.Level l = level();
            float f = Math.max(0, Math.min(1, x / Math.max(1, getWidth())));
            int v = Math.round(f * l.max);
            if (v == l.value) return;
            if (vol) h.quick.setVolume(v); else h.quick.setBrightness(v);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), ht = getHeight();
            QuickPanel.Level l = level();
            float f = l.value / (float) Math.max(1, l.max), fx = w * f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(day() ? 0xF2FFFFFF : 0xEBFFFFFF);
            c.save();
            c.clipRect(0, 0, fx, ht);
            c.drawRoundRect(0, 0, w, ht, h.px(24), h.px(24), p);
            c.restore();
            // Ikon dan label: gelap di atas bagian terisi, terang di luar.
            for (int pass = 0; pass < 2; pass++) {
                c.save();
                if (pass == 0) c.clipRect(0, 0, fx, ht); else c.clipRect(fx, 0, w, ht);
                int col = pass == 0 ? 0xFF1B1B1F : TEXT;
                drawContent(c, w, ht, col, l);
                c.restore();
            }
        }

        void drawContent(Canvas c, int w, int ht, int col, QuickPanel.Level l) {
            float cy = ht / 2f, s = Math.min(h.px(11), ht * .14f), cx = h.px(22) + s;
            p.setColor(col);
            p.setStyle(Paint.Style.FILL);
            if (vol) {
                path.rewind();
                path.moveTo(cx - s, cy - s * .45f);
                path.lineTo(cx - s * .35f, cy - s * .45f);
                path.lineTo(cx + s * .35f, cy - s);
                path.lineTo(cx + s * .35f, cy + s);
                path.lineTo(cx - s * .35f, cy + s * .45f);
                path.lineTo(cx - s, cy + s * .45f);
                path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(h.px(2));
                p.setStrokeCap(Paint.Cap.ROUND);
                if (l.value == 0) {
                    c.drawLine(cx + s * .7f, cy - s * .4f, cx + s * 1.4f, cy + s * .4f, p);
                    c.drawLine(cx + s * .7f, cy + s * .4f, cx + s * 1.4f, cy - s * .4f, p);
                } else {
                    box.set(cx - s * .3f, cy - s * .7f, cx + s * 1.1f, cy + s * .7f);
                    c.drawArc(box, -45, 90, false, p);
                    box.set(cx - s * .5f, cy - s * 1.2f, cx + s * 1.5f, cy + s * 1.2f);
                    c.drawArc(box, -45, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
            } else {
                c.drawCircle(cx, cy, s * .5f, p);
                p.setStrokeWidth(h.px(2));
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(cx + (float) Math.cos(a) * s * .8f, cy + (float) Math.sin(a) * s * .8f,
                            cx + (float) Math.cos(a) * s * 1.2f, cy + (float) Math.sin(a) * s * 1.2f, p);
                }
            }
            t.setColor(col);
            t.setTypeface(bold);
            t.setTextSize(Math.min(h.sp(15), ht * .22f));
            t.setTextAlign(Paint.Align.LEFT);
            Paint.FontMetrics fm = t.getFontMetrics();
            float base = cy - (fm.ascent + fm.descent) / 2;
            c.drawText(title(h.getString(vol ? R.string.quick_volume : R.string.quick_brightness)), cx + s * 2 + h.px(12), base, t);
            t.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Math.round(l.value * 100f / Math.max(1, l.max)) + "%", w - h.px(20), base, t);
        }
    }

    /** Nilai volume/kecerahan berubah (QuickPanel.refresh). */
    void levels() {
        if (volume != null) volume.invalidate();
        if (brightness != null) brightness.invalidate();
    }

    final BroadcastReceiver volumeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { h.quick.refresh(); }
    };
    private final android.database.ContentObserver settingsRx =
            new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean self) { h.quick.refresh(); }
            };
    private boolean listening;

    // ---------------------------------------------------------------- ubah susunan

    /**
     * Mode ubah susunan (tekan lama kartu kecepatan/sholat, atau dari panel cepat): kartu sedikit miring dengan bingkai
     * putus-putus dan tombol −; seret kartu ke kartu lain untuk bertukar tempat. "+ Tambah widget" memunculkan lagi kartu
     * yang disembunyikan, "Selesai" menyimpan. Urutan disimpan di prefs (cardOrder, cardHidden).
     */
    final class EditLayer extends View {
        boolean on;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF done = new RectF(), add = new RectF(), box = new RectF();
        View dragged;
        float dx, dy, startX, startY;
        final int[] gpos = new int[2], mpos = new int[2];

        EditLayer() {
            super(h);
            setVisibility(GONE);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{h.px(8), h.px(6)}, 0));
        }

        void start() {
            if (on || h.portrait) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            on = true;
            setDriving(false);
            setVisibility(VISIBLE);
            tilt(true);
            invalidate();
        }

        void finish() {
            on = false;
            setVisibility(GONE);
            tilt(false);
            saveOrder();
            relayout();
        }

        void tilt(boolean t) {
            int i = 0;
            for (String id : CARD_IDS) {
                View v = card(id);
                v.setRotation(t ? (i++ % 2 == 0 ? -.6f : .6f) : 0);
                v.setScaleX(t ? .985f : 1);
                v.setScaleY(t ? .985f : 1);
            }
        }

        RectF rectOf(View v) {
            grid.getLocationInWindow(gpos);
            getLocationInWindow(mpos);
            float l = gpos[0] - mpos[0] + v.getLeft() + v.getTranslationX(), tp = gpos[1] - mpos[1] + v.getTop() + v.getTranslationY();
            box.set(l, tp, l + v.getWidth(), tp + v.getHeight());
            return box;
        }

        @Override protected void onDraw(Canvas c) {
            if (!on) return;
            for (String id : CARD_IDS) {
                View v = card(id);
                if (v.getVisibility() != VISIBLE) continue;
                RectF r = rectOf(v);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(h.px(2));
                p.setColor(0x80FFFFFF);
                RectF in = new RectF(r);
                in.inset(h.px(6), h.px(6));
                c.drawRoundRect(in, h.px(20), h.px(20), p);
                // Tombol − di kiri atas.
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                q.setColor(0xFF8E8E93);
                float cx = r.left + h.px(18), cy = r.top + h.px(18);
                c.drawCircle(cx, cy, h.px(14), q);
                q.setColor(0xFFFFFFFF);
                q.setStrokeWidth(h.px(3));
                q.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx - h.px(6), cy, cx + h.px(6), cy, q);
            }
            // Tombol atas kanan.
            t.setTypeface(bold);
            t.setTextSize(h.sp(14));
            String sDone = h.getString(R.string.edit_done), sAdd = "＋ " + h.getString(R.string.edit_add);
            float bh = h.px(38), wDone = t.measureText(sDone) + h.px(32), wAdd = t.measureText(sAdd) + h.px(32);
            float top = h.px(10), rx = getWidth() - h.px(20);
            done.set(rx - wDone, top, rx, top + bh);
            add.set(done.left - h.px(10) - wAdd, top, done.left - h.px(10), top + bh);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            q.setColor(0xFFFFFFFF);
            c.drawRoundRect(done, bh / 2, bh / 2, q);
            q.setColor(0x66000000);
            c.drawRoundRect(add, bh / 2, bh / 2, q);
            t.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = t.getFontMetrics();
            float base = top + bh / 2 - (fm.ascent + fm.descent) / 2;
            t.setColor(0xFF111111);
            c.drawText(sDone, done.centerX(), base, t);
            t.setColor(0xFFFFFFFF);
            c.drawText(sAdd, add.centerX(), base, t);
        }

        View cardAt(float x, float y) {
            for (String id : CARD_IDS) {
                View v = card(id);
                if (v.getVisibility() == VISIBLE && v != dragged && rectOf(v).contains(x, y)) return v;
            }
            return null;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!on) return false;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragged = null;
                    if (done.contains(x, y)) { finish(); return true; }
                    if (add.contains(x, y)) { addDialog(); return true; }
                    for (String id : CARD_IDS) {
                        View v = card(id);
                        if (v.getVisibility() != VISIBLE) continue;
                        RectF r = rectOf(v);
                        if (Math.hypot(x - (r.left + h.px(18)), y - (r.top + h.px(18))) < h.px(24)) {
                            hidden.add(id);
                            relayout();
                            invalidate();
                            return true;
                        }
                    }
                    dragged = cardAt(x, y);
                    startX = x;
                    startY = y;
                    if (dragged != null) {
                        dragged.setElevation(h.px(16));
                        dragged.bringToFront();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragged != null) {
                        dragged.setTranslationX(x - startX);
                        dragged.setTranslationY(y - startY);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragged != null) {
                        View target = cardAt(x, y);
                        dragged.setTranslationX(0);
                        dragged.setTranslationY(0);
                        dragged.setElevation(0);
                        if (target != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            int a = order.indexOf(idOf(dragged)), b = order.indexOf(idOf(target));
                            if (a >= 0 && b >= 0) java.util.Collections.swap(order, a, b);
                            relayout();
                        }
                        dragged = null;
                        post(this::invalidate);
                    }
                    return true;
            }
            return true;
        }

        void addDialog() {
            List<String> ids = new ArrayList<>();
            for (String id : CARD_IDS) if (hidden.contains(id)) ids.add(id);
            if (ids.isEmpty()) {
                Notice.info(h, h.getString(R.string.edit_none_hidden));
                return;
            }
            String[] names = new String[ids.size()];
            for (int i = 0; i < ids.size(); i++) names[i] = cardName(ids.get(i));
            new android.app.AlertDialog.Builder(h).setTitle(R.string.edit_add).setItems(names, (d, which) -> {
                hidden.remove(ids.get(which));
                relayout();
                invalidate();
            }).show();
        }

        String cardName(String id) {
            switch (id) {
                case "speed": return h.getString(R.string.edit_card_speed);
                case "prayer": return h.getString(R.string.home_card_prayer);
                case "drive": return h.getString(R.string.edit_card_drive);
                default: return h.getString(R.string.home_media_none_app);
            }
        }
    }

    // ---------------------------------------------------------------- siklus hidup

    /** onResume. */
    void start() {
        h.quick.refresh();
        if (!listening) {
            listening = true;
            h.registerReceiver(volumeRx, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
            android.content.ContentResolver cr = h.getContentResolver();
            cr.registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS), false, settingsRx);
            if (h.quick.mcu) {
                cr.registerContentObserver(android.provider.Settings.System.getUriFor("mcu_system_volume"), false, settingsRx);
                cr.registerContentObserver(android.provider.Settings.System.getUriFor("mcu_mute_state"), false, settingsRx);
            }
        }
        media.refresh();
        refreshWeather(false);
        root.removeCallbacks(weatherTick);
        root.postDelayed(weatherTick, WEATHER_MS);
        drive.invalidate();
        Azan.listener = () -> h.runOnUiThread(() -> {
            prayer.startTicking();
            media.refresh();
        });
    }

    final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            refreshWeather(false);
            root.postDelayed(this, WEATHER_MS);
        }
    };

    /** onPause. */
    void stop() {
        media.stop();
        root.removeCallbacks(weatherTick);
        if (edit.on) edit.finish();
        Azan.listener = null;
        if (listening) {
            listening = false;
            try { h.unregisterReceiver(volumeRx); } catch (IllegalArgumentException ignored) { }
            h.getContentResolver().unregisterContentObserver(settingsRx);
        }
    }

    /** Tiap menit (updateTime). */
    void minute() {
        speed.invalidate();
        drive.invalidate();
        prayer.invalidate();
    }

    // ---------------------------------------------------------------- wallpaper

    static final int PICK_WALLPAPER = 41;

    static File wallpaperFile(Context c) {
        return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "wallpaper.jpg");
    }

    /** Wallpaper tampil (foto pilihan atau pemandangan bawaan) seukuran layar, dan versi kaburnya untuk kartu kaca. */
    private Bitmap wall, blurred;

    void loadWallpaper() {
        File f = wallpaperFile(h);
        int sw = Math.round(h.wDp * h.dp), sh = Math.round(h.hDp * h.dp);
        boolean d = day();
        new Thread(() -> {
            Bitmap b = null;
            if (f.isFile()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= sw && o.outHeight / (s * 2) >= sh) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                b = fitScreen(BitmapFactory.decodeFile(f.getPath(), o), sw, sh);
            }
            if (b == null) b = Scenery.road(sw, sh, d);
            Bitmap bl = Scenery.blur(b);
            Bitmap done = b;
            h.runOnUiThread(() -> {
                wall = done;
                blurred = bl;
                root.invalidate();
                if (root.getBackground() != null) root.getBackground().invalidateSelf();
                for (View v : new View[]{speed, prayer, drive, media, volume, brightness}) if (v != null) v.invalidate();
            });
        }, "wallpaper").start();
    }

    /** Dipotong dan diperkecil tepat seukuran layar (RGB_565: separuh memori); digambar 1:1 tiap kali. */
    static Bitmap fitScreen(Bitmap b, int sw, int sh) {
        if (b == null || sw <= 0 || sh <= 0) return b;
        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        float scale = Math.max(sw / (float) b.getWidth(), sh / (float) b.getHeight());
        int w = Math.round(sw / scale), hgt = Math.round(sh / scale);
        Rect src = new Rect((b.getWidth() - w) / 2, (b.getHeight() - hgt) / 2, (b.getWidth() + w) / 2, (b.getHeight() + hgt) / 2);
        c.drawBitmap(b, src, new Rect(0, 0, sw, sh), new Paint(Paint.FILTER_BITMAP_FLAG));
        b.recycle();
        return out;
    }

    /** Gambar dari galeri: disalin ke penyimpanan DE (home bisa tampil sebelum headunit dibuka kuncinya). */
    void setWallpaper(android.net.Uri uri) {
        File f = wallpaperFile(h);
        new Thread(() -> {
            boolean ok = false;
            try (InputStream in = h.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    File tmp = new File(f.getPath() + ".tmp");
                    try (OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    }
                    ok = tmp.renameTo(f);
                }
            } catch (Exception ignored) { }
            boolean done = ok;
            h.runOnUiThread(() -> {
                if (done) loadWallpaper(); else Notice.info(h, h.getString(R.string.home_wallpaper_failed));
            });
        }, "wallpaper-copy").start();
    }

    void clearWallpaper() {
        //noinspection ResultOfMethodCallIgnored
        wallpaperFile(h).delete();
        loadWallpaper();
    }

    /** Latar home: wallpaper seukuran layar (digambar 1:1), digelapkan sedikit di tema malam. */
    final class Wallpaper extends Drawable {
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Rect src = new Rect();

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            Bitmap w = wall;
            if (w == null) {
                c.drawColor(day() ? 0xFFA9C8E8 : 0xFF0B1020);
                return;
            }
            if (w.getWidth() == b.width() && w.getHeight() == b.height()) c.drawBitmap(w, b.left, b.top, null);
            else {
                src.set(0, 0, w.getWidth(), w.getHeight());
                c.drawBitmap(w, src, b, p);
            }
            if (wallpaperFile(h).isFile()) c.drawColor(day() ? 0x26FFFFFF : 0x40000000);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}

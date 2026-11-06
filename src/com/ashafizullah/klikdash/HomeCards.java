package com.ashafizullah.klikdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ashafizullah.klikdash.HomeActivity.AMBER;
import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.FAINT;
import static com.ashafizullah.klikdash.HomeActivity.INK;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.PANEL;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WARN;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;
import static com.ashafizullah.klikdash.HomeApps.SLOTS;

/**
 * Home bergaya kartu di atas wallpaper: bilah samping (HOME, slot aplikasi, APLIKASI, PENGATURAN), kartu jam + cuaca,
 * kartu kecepatan + waktu sholat berikutnya, peta mini OpenStreetMap dengan tombol Rumah/Kantor, kartu pemutar musik,
 * dan slider volume serta kecerahan. Bagian lama dipakai ulang: TextView jam/tanggal/Hijri/kecepatan milik
 * {@link HomeActivity} (diisi updateTime dan HomeLocation), slot ubin ({@link HomeViews.Tile}), sumber media
 * ({@link MediaNotch#current}), dan volume/kecerahan dari {@link QuickPanel}.
 */
final class HomeCards {
    final HomeActivity h;
    final Typeface light, regular, medium;

    HomeCards(HomeActivity h) {
        this.h = h;
        light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    FrameLayout root;
    DriveCard drive;
    MediaCard media;
    Slider volume, brightness;
    Weather weather;
    Arc arc;
    Sky sky;

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

    // ---------------------------------------------------------------- tata letak

    View build() {
        float wDp = h.wDp, hDp = h.hDp;
        boolean portrait = h.portrait;
        root = new FrameLayout(h);
        root.setBackground(new Wallpaper(h));
        loadWallpaper();

        LinearLayout row = new LinearLayout(h);
        row.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.addView(row, MATCH, MATCH);

        int pad = h.px(14), gap = h.px(14);
        LinearLayout side = sidebar(portrait, hDp);
        LinearLayout content = new LinearLayout(h);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(portrait ? pad : 0, pad, pad, portrait ? 0 : pad);

        View clock = clockCard(portrait ? wDp - 28 : (wDp - 96 - 14 - 14) * .42f, portrait ? hDp * .2f : (hDp - 28 - 14) * .4f);
        View prayer = prayerCard();
        drive = new DriveCard();
        drive.setBackground(card());
        View mapCard = drive;
        media = new MediaCard();
        media.setBackground(card());
        volume = new Slider(true);
        brightness = new Slider(false);
        volume.setBackground(card());
        brightness.setBackground(card());
        LinearLayout controls = new LinearLayout(h);
        controls.addView(volume, new LinearLayout.LayoutParams(0, MATCH, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, MATCH, 1f);
        bl.leftMargin = gap;
        controls.addView(brightness, bl);

        if (portrait) {
            add(content, clock, 0, .9f, 0);
            add(content, prayer, 0, .9f, gap);
            add(content, media, 0, 1f, gap);
            add(content, mapCard, 0, 1.2f, gap);
            add(content, controls, h.px(76), 0, gap);
            row.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            row.addView(side, new LinearLayout.LayoutParams(MATCH, h.px(84)));
        } else {
            LinearLayout top = new LinearLayout(h);
            top.addView(clock, new LinearLayout.LayoutParams(0, MATCH, .42f));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, MATCH, .58f);
            pl.leftMargin = gap;
            top.addView(prayer, pl);
            LinearLayout bottom = new LinearLayout(h);
            bottom.addView(mapCard, new LinearLayout.LayoutParams(0, MATCH, .42f));
            LinearLayout right = new LinearLayout(h);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(media, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(MATCH, h.px(Math.min(84, hDp * .12f)));
            cl.topMargin = gap;
            right.addView(controls, cl);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, MATCH, .58f);
            rl.leftMargin = gap;
            bottom.addView(right, rl);
            add(content, top, 0, .4f, 0);
            add(content, bottom, 0, .6f, gap);
            row.addView(side, new LinearLayout.LayoutParams(h.px(96), MATCH));
            row.addView(content, new LinearLayout.LayoutParams(0, MATCH, 1f));
        }

        // Muncul hanya bila KlikDash belum jadi tampilan utama (lihat HomeActivity.refreshDefaultHome); melayang di atas
        // kartu supaya tata letaknya tidak bergeser.
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
        return root;
    }

    private static void add(LinearLayout parent, View v, int height, float weight, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(MATCH, height, weight);
        l.topMargin = top;
        parent.addView(v, l);
    }

    /** Latar kartu: kaca gelap bersudut bulat yang menampakkan wallpaper samar. */
    Drawable card() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(h.px(22));
        g.setColor(Theme.light() ? 0xE6FFFFFF & (PANEL | 0xFF000000) : (PANEL & 0x00FFFFFF) | 0xC8000000);
        g.setStroke(Math.max(1, h.px(1)), Theme.light() ? 0x1F000000 : 0x1FFFFFFF);
        return g;
    }

    TextView label(String s, float size, int color) {
        TextView t = h.text(s, medium, size, color);
        t.setLetterSpacing(0.18f);
        return t;
    }

    // ---------------------------------------------------------------- bilah samping

    LinearLayout sidebar(boolean portrait, float hDp) {
        LinearLayout side = new LinearLayout(h);
        side.setOrientation(portrait ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER);
        side.setPadding(h.px(10), h.px(10), h.px(10), h.px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.light() ? 0xD9FFFFFF : 0xB3000000);
        side.setBackground(bg);

        // Kunci layar (PIN di Pengaturan): gembok muncul hanya bila PIN sudah diatur, lihat refreshLock.
        h.lockIcon = new ScreenLock.Icon(h);
        h.lockIcon.setOnClickListener(v -> h.toggleLock());
        side.addView(h.lockIcon, item(portrait, .6f));

        SideButton home = new SideButton(h.getString(R.string.home_side_home), SideButton.HOME);
        home.selected = true;
        home.setOnClickListener(v -> {
            if (h.quick.isOpen()) h.quick.close();
            if (h.drawer.isOpen()) h.drawer.close();
        });
        side.addView(home, item(portrait, 1));

        // Slot aplikasi sebanyak yang muat (paling banyak sesuai jumlah ubin di Pengaturan); sisanya di APLIKASI.
        float itemDp = 82;
        int fit = portrait ? (int) ((h.wDp - 20) / 96) - 3 : (int) ((hDp - 40 - 20 - itemDp * .6f) / itemDp) - 3;
        int slots = Math.max(1, Math.min(h.shown, fit));
        for (int i = 0; i < slots; i++) {
            SideTile t = new SideTile(i);
            h.tiles[i] = t;
            side.addView(t, item(portrait, 1));
        }
        SideTile all = new SideTile(SLOTS);
        h.tiles[SLOTS] = all;
        side.addView(all, item(portrait, 1));

        SideButton settings = new SideButton(h.getString(R.string.home_side_settings), SideButton.GEAR);
        settings.setContentDescription(h.getString(R.string.quick_title));
        settings.setOnClickListener(v -> h.quick.open());
        settings.setOnLongClickListener(v -> {
            h.openSettings();
            return true;
        });
        h.gear = settings;
        LinearLayout.LayoutParams sl = item(portrait, 1);
        side.addView(new View(h), portrait ? new LinearLayout.LayoutParams(0, 1, .01f) : new LinearLayout.LayoutParams(1, 0, 1f));
        side.addView(settings, sl);
        return side;
    }

    private LinearLayout.LayoutParams item(boolean portrait, float k) {
        return portrait ? new LinearLayout.LayoutParams(0, MATCH, k) : new LinearLayout.LayoutParams(MATCH, h.px(82 * k));
    }

    /** Pilihan bilah samping: ikon di atas label kecil; yang terpilih berbingkai aksen. */
    abstract class SideBase extends View {
        final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();
        boolean selected;

        SideBase() {
            super(h);
            text.setTypeface(medium);
            text.setTextSize(h.sp(10.5f));
            text.setLetterSpacing(0.08f);
            text.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        void frame(Canvas c) {
            boolean down = isPressed();
            if (!selected && !down) return;
            float m = h.px(4);
            box.set(m, m, getWidth() - m, getHeight() - m);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(down ? (AMBER & 0x00FFFFFF) | 0x55000000 : (AMBER & 0x00FFFFFF) | 0x26000000);
            c.drawRoundRect(box, h.px(16), h.px(16), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(h.px(1));
            paint.setColor((AMBER & 0x00FFFFFF) | 0x66000000);
            c.drawRoundRect(box, h.px(16), h.px(16), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        void caption(Canvas c, String s, boolean accent) {
            text.setColor(accent ? AMBER : TEXT & 0x00FFFFFF | 0xD9000000);
            CharSequence e = TextUtils.ellipsize(s, text, getWidth() - h.px(8), TextUtils.TruncateAt.END);
            c.drawText(e, 0, e.length(), getWidth() / 2f, getHeight() / 2f + h.px(24), text);
        }
    }

    final class SideButton extends SideBase {
        static final int HOME = 0, GEAR = 1;
        final String name;
        final int kind;
        final Path path = new Path();

        SideButton(String name, int kind) {
            this.name = name;
            this.kind = kind;
            setContentDescription(name);
        }

        @Override protected void onDraw(Canvas c) {
            frame(c);
            float cx = getWidth() / 2f, cy = getHeight() / 2f - h.px(10), s = h.px(11);
            paint.setColor(selected ? AMBER : TEXT);
            paint.setStyle(Paint.Style.FILL);
            if (kind == HOME) {
                path.rewind();
                path.moveTo(cx, cy - s);
                path.lineTo(cx + s, cy - s * .05f);
                path.lineTo(cx + s * .72f, cy - s * .05f);
                path.lineTo(cx + s * .72f, cy + s * .9f);
                path.lineTo(cx + s * .22f, cy + s * .9f);
                path.lineTo(cx + s * .22f, cy + s * .3f);
                path.lineTo(cx - s * .22f, cy + s * .3f);
                path.lineTo(cx - s * .22f, cy + s * .9f);
                path.lineTo(cx - s * .72f, cy + s * .9f);
                path.lineTo(cx - s * .72f, cy - s * .05f);
                path.lineTo(cx - s, cy - s * .05f);
                path.close();
                c.drawPath(path, paint);
            } else {
                // Roda gigi: delapan gigi di sekeliling lingkaran berlubang.
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, cx, cy);
                    c.drawRect(cx - s * .2f, cy - s * 1.02f, cx + s * .2f, cy - s * .6f, paint);
                    c.restore();
                }
                c.drawCircle(cx, cy, s * .72f, paint);
                paint.setColor(Theme.light() ? 0xFFFFFFFF : 0xFF0A0A0A);
                c.drawCircle(cx, cy, s * .3f, paint);
            }
            caption(c, name, selected);
        }
    }

    /** Slot aplikasi di bilah samping: logika ketuk, tekan lama, dan seret milik {@link HomeViews.Tile}. */
    final class SideTile extends HomeViews.Tile {
        final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();

        SideTile(int idx) {
            super(HomeCards.this.h, idx);
            text.setTypeface(medium);
            text.setTextSize(h.sp(10.5f));
            text.setLetterSpacing(0.08f);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), ht = getHeight();
            if (down || target) {
                float m = h.px(4);
                box.set(m, m, w - m, ht - m);
                paint.setColor((AMBER & 0x00FFFFFF) | (target ? 0x66000000 : 0x40000000));
                c.drawRoundRect(box, h.px(16), h.px(16), paint);
            }
            HomeApps apps = h.apps;
            int icon = h.px(30), x = (w - icon) / 2, y = ht / 2 - h.px(10) - icon / 2;
            String name;
            if (idx == SLOTS) {
                float cell = h.px(6), g = h.px(3.5f), x0 = w / 2f - (cell * 3 + g * 2) / 2f, y0 = y + (icon - cell * 3 - g * 2) / 2f;
                paint.setColor(TEXT);
                for (int i = 0; i < 9; i++) {
                    float cx = x0 + (i % 3) * (cell + g), cy = y0 + (i / 3) * (cell + g);
                    c.drawCircle(cx + cell / 2, cy + cell / 2, cell / 2, paint);
                }
                name = h.getString(R.string.home_side_apps);
            } else {
                HomeApps.App a = apps.appFor(idx);
                if (a != null) h.drawIcon(c, apps.iconOf(a), x, y, icon, down);
                else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(h.px(1.5f));
                    paint.setColor(FAINT);
                    box.set(x, y, x + icon, y + icon);
                    c.drawRoundRect(box, h.px(8), h.px(8), paint);
                    c.drawLine(w / 2f, y + icon * .3f, w / 2f, y + icon * .7f, paint);
                    c.drawLine(w / 2f - icon * .2f, y + icon / 2f, w / 2f + icon * .2f, y + icon / 2f, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                name = apps.slotLabel[idx] != null ? apps.labelText(apps.slotLabel[idx]) : a != null ? a.upper : h.tEmpty;
            }
            text.setColor(TEXT & 0x00FFFFFF | 0xD9000000);
            CharSequence e = TextUtils.ellipsize(name, text, w - h.px(8), TextUtils.TruncateAt.END);
            c.drawText(e, 0, e.length(), w / 2f, ht / 2f + h.px(24), text);
            drawDragState(c);
        }

        @Override void drawDragState(Canvas c) {
            if (!dragging) return;
            paint.setColor(0x99000000);
            box.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(box, h.px(16), h.px(16), paint);
        }
    }

    // ---------------------------------------------------------------- kartu jam

    View clockCard(float wDp, float hDp) {
        FrameLayout card = new FrameLayout(h);
        card.setBackground(card());
        LinearLayout col = new LinearLayout(h);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = h.px(22);
        col.setPadding(p, h.px(20), p, h.px(16));
        card.addView(col, MATCH, MATCH);

        LinearLayout dateRow = new LinearLayout(h);
        h.day = label("", 12, TEXT & 0x00FFFFFF | 0xB3000000);
        h.date = label("", 12, TEXT & 0x00FFFFFF | 0xB3000000);
        dateRow.addView(h.day);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, WRAP);
        dl.leftMargin = h.px(8);
        dateRow.addView(h.date, dl);
        col.addView(dateRow);

        // Jam sebesar yang muat: ±38% tinggi kartu, dan tidak lebih lebar dari kartu dikurangi ikon bulan/matahari.
        float clockDp = Math.max(48, Math.min(hDp * .4f, (wDp - 44 - 70) / 2.6f));
        LinearLayout clockRow = new LinearLayout(h);
        clockRow.setGravity(Gravity.BOTTOM);
        h.clock = h.text("00:00", light, clockDp * h.dp / h.sp, TEXT);
        h.clock.setLetterSpacing(-0.02f);
        clockRow.addView(h.clock);
        h.ampm = h.text("", medium, 15, DIM);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(WRAP, WRAP);
        al.leftMargin = h.px(10);
        al.bottomMargin = h.px(12);
        clockRow.addView(h.ampm, al);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(WRAP, WRAP);
        crl.topMargin = h.px(2);
        col.addView(clockRow, crl);

        h.hijri = h.text("", medium, Math.max(13, Math.min(17, hDp * .065f)), AMBER);
        h.hijri.setOnClickListener(v -> h.openSettings());
        col.addView(h.hijri);

        col.addView(new View(h), new LinearLayout.LayoutParams(1, 0, 1f));
        weather = new Weather();
        col.addView(weather, new LinearLayout.LayoutParams(MATCH, h.px(Math.max(40, Math.min(54, hDp * .22f)))));

        sky = new Sky();
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(h.px(56), h.px(56), Gravity.TOP | Gravity.END);
        sl.topMargin = h.px(16);
        sl.rightMargin = h.px(18);
        card.addView(sky, sl);
        return card;
    }

    /** Bulan sabit setelah Maghrib sampai Subuh, matahari di antaranya. */
    final class Sky extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path moon = new Path(), cut = new Path();

        Sky() { super(h); }

        @Override protected void onDraw(Canvas c) {
            Calendar cal = Calendar.getInstance();
            int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int[] pray = h.loc.pray;
            boolean night = pray == null ? now < 360 || now >= 1110 : now < pray[0] || now >= pray[3];
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * .78f;
            p.setColor(night ? 0xFFD9C08A : AMBER);
            if (night) {
                moon.rewind();
                moon.addCircle(cx, cy, r, Path.Direction.CW);
                cut.rewind();
                cut.addCircle(cx + r * .55f, cy - r * .35f, r * .85f, Path.Direction.CW);
                moon.op(cut, Path.Op.DIFFERENCE);
                c.drawPath(moon, p);
            } else {
                c.drawCircle(cx, cy, r * .5f, p);
                p.setStrokeWidth(h.px(2.5f));
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(cx + (float) Math.cos(a) * r * .7f, cy + (float) Math.sin(a) * r * .7f,
                            cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, p);
                }
            }
        }
    }

    // ---------------------------------------------------------------- cuaca (Open-Meteo, tanpa kunci API)

    static final long WEATHER_MS = 30 * 60_000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static boolean weatherBusy;

    final class Weather extends View {
        final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Weather() {
            super(h);
            big.setTypeface(light);
            small.setTypeface(regular);
        }

        @Override protected void onDraw(Canvas c) {
            SharedPreferences pr = h.prefs;
            if (!pr.contains("wxTemp")) return;
            int ht = getHeight();
            int code = pr.getInt("wxCode", 0);
            boolean day = pr.getBoolean("wxDay", true);
            float s = ht * .8f;
            drawWeatherIcon(c, p, code, day, s / 2f, ht / 2f, s / 2f, h.dp);
            big.setColor(TEXT);
            big.setTextSize(ht * .72f);
            String t = Math.round(pr.getFloat("wxTemp", 0)) + "°";
            float x = s + h.px(14);
            Paint.FontMetrics fm = big.getFontMetrics();
            float base = ht / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(t, x, base, big);
            x += big.measureText(t) + h.px(14);
            small.setTextSize(Math.min(h.sp(15), ht * .32f));
            small.setColor(TEXT);
            float avail = getWidth() - x;
            CharSequence cond = TextUtils.ellipsize(h.getString(weatherText(code)), small, avail, TextUtils.TruncateAt.END);
            c.drawText(cond, 0, cond.length(), x, ht / 2f - h.px(2), small);
            small.setColor(DIM);
            small.setTextSize(Math.min(h.sp(13), ht * .28f));
            String place = h.loc.city != null ? title(h.loc.city) : "";
            CharSequence pl = TextUtils.ellipsize(place, small, avail, TextUtils.TruncateAt.END);
            c.drawText(pl, 0, pl.length(), x, ht / 2f + small.getTextSize() + h.px(2), small);
        }
    }

    void refreshWeather(boolean force) {
        SharedPreferences pr = h.prefs;
        double lat = h.loc.lat, lon = h.loc.lon;
        if (lat == 0 && lon == 0) return;
        float[] d = new float[1];
        Location.distanceBetween(lat, lon, pr.getFloat("wxLat", 0), pr.getFloat("wxLon", 0), d);
        boolean fresh = System.currentTimeMillis() - pr.getLong("wxAt", 0) < WEATHER_MS && d[0] < 10_000;
        if ((fresh && !force) || weatherBusy) return;
        weatherBusy = true;
        NET.execute(() -> {
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon);
                org.json.JSONObject cur = new org.json.JSONObject(get(url)).getJSONObject("current");
                float temp = (float) cur.getDouble("temperature_2m");
                int code = cur.getInt("weather_code");
                boolean day = cur.optInt("is_day", 1) == 1;
                pr.edit().putFloat("wxTemp", temp).putInt("wxCode", code).putBoolean("wxDay", day)
                        .putLong("wxAt", System.currentTimeMillis()).putFloat("wxLat", (float) lat).putFloat("wxLon", (float) lon)
                        .apply();
                h.runOnUiThread(() -> { if (weather != null) weather.invalidate(); });
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

    /** Kode cuaca WMO → teks. */
    static int weatherText(int code) {
        if (code == 0) return R.string.wx_clear;
        if (code <= 2) return R.string.wx_partly;
        if (code == 3) return R.string.wx_cloudy;
        if (code == 45 || code == 48) return R.string.wx_fog;
        if (code >= 51 && code <= 57) return R.string.wx_drizzle;
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return R.string.wx_rain;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.string.wx_snow;
        if (code >= 95) return R.string.wx_storm;
        return R.string.wx_cloudy;
    }

    /** Ikon cuaca sederhana yang digambar sendiri: matahari/bulan, awan, kabut, hujan, petir. */
    static void drawWeatherIcon(Canvas c, Paint p, int code, boolean day, float cx, float cy, float r, float dp) {
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.ROUND);
        boolean clear = code == 0, partly = code == 1 || code == 2, fog = code == 45 || code == 48;
        boolean rain = code >= 51 && code <= 67 || code >= 80 && code <= 82, storm = code >= 95;
        if (clear || partly) {
            float sx = partly ? cx - r * .3f : cx, sy = partly ? cy - r * .3f : cy, sr = r * (partly ? .38f : .5f);
            p.setColor(day ? AMBER : 0xFFD9C08A);
            c.drawCircle(sx, sy, sr, p);
            if (clear) return;
        }
        int cloud = fog ? 0xFFB8BCC4 : 0xFFE6E8EC;
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
                p.setColor(AMBER);
                c.drawLine(cx + r * .1f, cy + r * .35f, cx - r * .1f, cy + r * .65f, p);
                c.drawLine(cx - r * .1f, cy + r * .65f, cx + r * .1f, cy + r * .65f, p);
                c.drawLine(cx + r * .1f, cy + r * .65f, cx - r * .05f, cy + r * .95f, p);
            }
        }
    }

    static String title(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean up = true;
        for (char ch : s.toCharArray()) {
            b.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            up = !Character.isLetter(ch);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- kartu kecepatan + sholat

    View prayerCard() {
        LinearLayout card = new LinearLayout(h);
        card.setBackground(card());
        card.setPadding(h.px(14), h.px(14), h.px(22), h.px(14));

        FrameLayout gauge = new FrameLayout(h);
        arc = new Arc();
        gauge.addView(arc, MATCH, MATCH);
        LinearLayout speedCol = new LinearLayout(h);
        speedCol.setOrientation(LinearLayout.VERTICAL);
        speedCol.setGravity(Gravity.CENTER_HORIZONTAL);
        h.speed = h.text("--", light, 40, TEXT);
        h.speed.setGravity(Gravity.CENTER);
        // Lebar dan tinggi tetap: setText tiap detik (kecepatan) hanya menggambar ulang TextView ini, tanpa meminta
        // tata letak ulang seluruh home (TextView WRAP_CONTENT selalu requestLayout, lihat HomeActivity.show).
        speedCol.addView(h.speed, new LinearLayout.LayoutParams(MATCH, h.px(48)));
        TextView unit = label(h.getString(R.string.home_speed_unit), 10, DIM);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(WRAP, WRAP);
        ul.topMargin = h.px(4);
        speedCol.addView(unit, ul);
        FrameLayout.LayoutParams scl = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER);
        scl.bottomMargin = h.px(10);
        gauge.addView(speedCol, scl);
        card.addView(gauge, new LinearLayout.LayoutParams(h.px(150), MATCH));

        View rule = new View(h);
        rule.setBackgroundColor(Theme.light() ? 0x1F000000 : 0x1FFFFFFF);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(h.px(1), MATCH);
        rl.leftMargin = h.px(14);
        rl.rightMargin = h.px(22);
        card.addView(rule, rl);

        Prayer p = new Prayer();
        h.prayerView = p;
        card.addView(p, new LinearLayout.LayoutParams(0, MATCH, 1f));
        return card;
    }


    /** Fix GPS (tiap detik selama home tampil, dari HomeLocation): jarak trip, busur kecepatan, dan peta. */
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
        arc.invalidate();
        // Kartu perjalanan: hanya bila angka yang tampil berubah (jarak per 100 m, masa per menit, maks).
        String shown = (Math.round(tripM / 100)) + "/" + tripMovingMs / 60_000 + "/" + tripMaxKmh;
        if (!shown.equals(driveShown)) {
            driveShown = shown;
            if (drive != null) drive.invalidate();
        }
    }

    private String driveShown = "";

    /** Busur kecepatan 0–160 km/j, terisi warna aksen. */
    final class Arc extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();

        Arc() {
            super(h);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            float stroke = h.px(5), size = Math.min(getWidth(), getHeight()) - stroke * 2;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            box.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            p.setStrokeWidth(stroke);
            p.setColor(Theme.light() ? 0x26000000 : 0x2EFFFFFF);
            c.drawArc(box, 135, 270, false, p);
            boolean live = SystemClock.elapsedRealtime() - HomeLocation.lastKmhAt < 5000;
            int kmh = live ? HomeLocation.lastKmh : 0;
            if (kmh > 0) {
                p.setColor(kmh > 100 ? WARN : AMBER);
                c.drawArc(box, 135, 270 * Math.min(1f, kmh / 160f), false, p);
            }
        }
    }

    /** Waktu sholat berikutnya (besar) dan lima waktu hari ini sebagai chip. */
    final class Prayer extends HomeViews.PrayerView {
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();

        Prayer() { super(HomeCards.this.h, 1); }

        /** Hitung mundur per menit: digambar ulang oleh updateTime, bukan tiap detik. */
        @Override void startTicking() { invalidate(); }

        @Override void stopTicking() { }

        @Override protected void onDraw(Canvas c) {
            HomeLocation loc = h.loc;
            int[] pray = loc.pray;
            if (pray == null) return;
            int w = getWidth(), ht = getHeight();
            Calendar cal = Calendar.getInstance();
            int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int next = loc.nextPrayer(now);
            int shown = next >= 0 ? next : 0;
            int target = next >= 0 ? pray[next] : 1440 + loc.prayTomorrow[0];
            int left = Math.max(0, target - now);

            float u = ht / 230f; // skala dari tinggi kartu
            t.setTypeface(medium);
            t.setTextSize(Math.min(h.sp(11), 12 * u * h.dp));
            t.setLetterSpacing(0.2f);
            t.setColor(TEXT & 0x00FFFFFF | 0xB3000000);
            t.setTextAlign(Paint.Align.LEFT);
            String place = loc.city != null ? loc.city : String.format(Locale.US, "%.2f, %.2f", loc.lat, loc.lon);
            String head = h.getString(R.string.home_next_prayer) + "  ·  " + place.toUpperCase(Lang.locale());
            CharSequence hd = TextUtils.ellipsize(head, t, w, TextUtils.TruncateAt.END);
            float y = t.getTextSize() + h.px(6);
            c.drawText(hd, 0, hd.length(), 0, y, t);

            // Waktu besar di kanan, nama dan hitung mundur di kiri.
            String time = next >= 0 ? loc.prayText[next] : Clock.hm(Clock.twelve(h.prefs), loc.prayTomorrow[0]);
            t.setTypeface(light);
            t.setLetterSpacing(0);
            t.setTextSize(Math.min(h.sp(72), 64 * u * h.dp));
            t.setColor(TEXT);
            t.setTextAlign(Paint.Align.RIGHT);
            float bigBase = y + h.px(10) + t.getTextSize() * .9f;
            c.drawText(time, w, bigBase, t);
            float timeW = t.measureText(time);

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(medium);
            t.setTextSize(Math.min(h.sp(36), 34 * u * h.dp));
            t.setColor(TEXT);
            String name = title(Lang.prayers[shown]);
            CharSequence nm = TextUtils.ellipsize(name, t, w - timeW - h.px(16), TextUtils.TruncateAt.END);
            float nameBase = y + h.px(14) + t.getTextSize();
            c.drawText(nm, 0, nm.length(), 0, nameBase, t);
            t.setTypeface(regular);
            t.setTextSize(Math.min(h.sp(15), 15 * u * h.dp));
            t.setColor(AMBER);
            String in = left >= 60 ? h.getString(R.string.home_in_hm, left / 60, left % 60) : h.getString(R.string.home_in_m, left);
            c.drawText(in, 0, nameBase + t.getTextSize() + h.px(8), t);

            // Chip lima waktu di bawah.
            float chipH = Math.min(h.px(76), ht * .36f), top = ht - chipH, col = w / 5f;
            for (int i = 0; i < pray.length; i++) {
                float x0 = i * col;
                boolean on = i == shown;
                if (on) {
                    box.set(x0 + h.px(3), top, x0 + col - h.px(3), ht);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor((AMBER & 0x00FFFFFF) | 0x24000000);
                    c.drawRoundRect(box, h.px(14), h.px(14), p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(h.px(1));
                    p.setColor((AMBER & 0x00FFFFFF) | 0x80000000);
                    c.drawRoundRect(box, h.px(14), h.px(14), p);
                }
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(medium);
                t.setLetterSpacing(0.12f);
                t.setTextSize(Math.min(h.sp(10.5f), col / 8f));
                t.setColor(on ? AMBER : TEXT & 0x00FFFFFF | 0xA6000000);
                c.drawText(Lang.prayers[i], x0 + col / 2, top + chipH * .38f, t);
                t.setTypeface(regular);
                t.setLetterSpacing(0.02f);
                t.setTextSize(Math.min(h.sp(22), col / 3.6f));
                t.setColor(on ? AMBER : pray[i] <= now ? DIM : TEXT);
                c.drawText(loc.prayText[i], x0 + col / 2, top + chipH * .78f, t);
            }
        }
    }

    // ---------------------------------------------------------------- kartu Zlink & perjalanan

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
     * Pengganti peta mini (headunit tidak kuat menjalankan Waze/Maps; navigasi lewat Zlink di HP): tombol besar Zlink
     * (CarPlay/Android Auto), ringkasan perjalanan dari GPS, dan dua tombol cepat Zikir dan Al-Quran. Satu View yang
     * digambar sendiri; digambar ulang hanya saat angka perjalanan berubah.
     */
    final class DriveCard extends View {
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF zlink = new RectF(), stats = new RectF(), dzikir = new RectF(), quran = new RectF(), box = new RectF();
        final Path phone = new Path();
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
        private Drawable icQuran, icDzikir;

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
                    pressed = zlink.contains(downX, downY) ? zlink : dzikir.contains(downX, downY) ? dzikir
                            : quran.contains(downX, downY) ? quran : stats.contains(downX, downY) ? stats : null;
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
                    else if (hit == dzikir) {
                        openIntent(new Intent(h, DzikirActivity.class).putExtra("period",
                                h.loc.dzikirPagi() ? DzikirActivity.PAGI : DzikirActivity.PETANG));
                    } else if (hit == quran) openIntent(new Intent(h, QuranActivity.class));
                    else if (hit == stats) Notice.info(h, h.getString(R.string.home_drive_reset_hint));
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

        void fill(Canvas c, RectF r, float radius, boolean accent) {
            boolean down = pressed == r;
            p.setStyle(Paint.Style.FILL);
            p.setColor(accent ? (AMBER & 0x00FFFFFF) | (down ? 0x59000000 : 0x26000000)
                    : down ? (AMBER & 0x00FFFFFF) | 0x40000000 : Theme.light() ? 0x0D000000 : 0x0FFFFFFF);
            c.drawRoundRect(r, radius, radius, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(h.px(1));
            p.setColor(accent ? (AMBER & 0x00FFFFFF) | 0x73000000 : Theme.light() ? 0x1F000000 : 0x1FFFFFFF);
            c.drawRoundRect(r, radius, radius, p);
            p.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), ht = getHeight();
            float pad = h.px(16), gap = h.px(12), inner = w - pad * 2;
            float zh = Math.min(h.px(96), ht * .3f), bh = Math.min(h.px(92), ht * .26f);
            zlink.set(pad, pad, w - pad, pad + zh);
            dzikir.set(pad, ht - pad - bh, pad + (inner - gap) / 2, ht - pad);
            quran.set(dzikir.right + gap, dzikir.top, w - pad, ht - pad);
            stats.set(pad, zlink.bottom + gap, w - pad, dzikir.top - gap);

            // Zlink.
            fill(c, zlink, h.px(18), true);
            float ic = zh * .5f, ix = zlink.left + h.px(18), iy = zlink.centerY() - ic / 2;
            box.set(ix, iy, ix + ic, iy + ic);
            p.setColor(AMBER);
            c.drawRoundRect(box, ic * .24f, ic * .24f, p);
            // Ikon: HP dengan sinyal ke mobil.
            float pw = ic * .32f, ph = ic * .56f, pcx = box.centerX() - ic * .1f, pcy = box.centerY();
            p.setColor(INK);
            box.set(pcx - pw / 2, pcy - ph / 2, pcx + pw / 2, pcy + ph / 2);
            c.drawRoundRect(box, h.px(2.5f), h.px(2.5f), p);
            p.setColor(AMBER);
            box.inset(h.px(2.2f), h.px(3.5f));
            c.drawRect(box, p);
            p.setColor(INK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(h.px(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int k = 1; k <= 2; k++) {
                float r = ic * (.1f + .1f * k);
                box.set(pcx + pw / 2 - r * .2f - r, pcy - r, pcx + pw / 2 - r * .2f + r, pcy + r);
                c.drawArc(box, -45, 90, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            float tx = ix + ic + h.px(16);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(medium);
            t.setLetterSpacing(0);
            t.setTextSize(Math.min(h.sp(24), zh * .3f));
            t.setColor(TEXT);
            c.drawText(h.getString(R.string.home_zlink), tx, zlink.centerY() - h.px(2), t);
            t.setTypeface(regular);
            t.setTextSize(Math.min(h.sp(14), zh * .18f));
            t.setColor(DIM);
            c.drawText(h.getString(R.string.home_zlink_sub), tx, zlink.centerY() + t.getTextSize() + h.px(4), t);
            t.setTextAlign(Paint.Align.RIGHT);
            t.setTextSize(Math.min(h.sp(30), zh * .4f));
            t.setColor(AMBER);
            Paint.FontMetrics fm = t.getFontMetrics();
            c.drawText("›", zlink.right - h.px(20), zlink.centerY() - (fm.ascent + fm.descent) / 2, t);

            // Perjalanan.
            if (pressed == stats) fill(c, stats, h.px(14), false);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(medium);
            t.setLetterSpacing(0.2f);
            t.setTextSize(Math.min(h.sp(11), stats.height() * .14f));
            t.setColor(TEXT & 0x00FFFFFF | 0xB3000000);
            float sy = stats.top + h.px(6) + t.getTextSize();
            c.drawText(h.getString(R.string.home_drive), stats.left + h.px(4), sy, t);
            long ms = tripMovingMs;
            double hours = ms / 3_600_000.0;
            String[] names = {h.getString(R.string.home_drive_dist), h.getString(R.string.home_drive_time),
                    h.getString(R.string.home_drive_avg), h.getString(R.string.home_drive_max)};
            String[] vals = {String.format(Locale.US, "%.1f", tripM / 1000), String.format(Locale.US, "%d:%02d",
                    ms / 3_600_000, ms / 60_000 % 60), hours > 0.01 ? String.valueOf(Math.round(tripM / 1000 / hours)) : "0",
                    String.valueOf(tripMaxKmh)};
            String[] units = {"km", "", h.getString(R.string.home_speed_unit).toLowerCase(Lang.locale()),
                    h.getString(R.string.home_speed_unit).toLowerCase(Lang.locale())};
            float col = (stats.width() - h.px(8)) / 4f, avail = stats.bottom - sy - h.px(10);
            // Label + angka setinggi ±70 dp, di tengah sisa tinggi area perjalanan.
            float rest = Math.min(avail, h.px(84)), top = sy + h.px(10) + (avail - rest) / 2;
            for (int i = 0; i < 4; i++) {
                float x = stats.left + h.px(4) + i * col;
                t.setTypeface(medium);
                t.setLetterSpacing(0.15f);
                t.setTextSize(Math.min(h.sp(10), rest * .2f));
                t.setColor(DIM);
                c.drawText(names[i], x, top + t.getTextSize(), t);
                float ny = top + t.getTextSize() + h.px(6);
                t.setTypeface(light);
                t.setLetterSpacing(0);
                t.setTextSize(Math.min(h.sp(36), Math.min(rest * .55f, col * .44f)));
                t.setColor(TEXT);
                float vy = ny + t.getTextSize() * .9f;
                c.drawText(vals[i], x, vy, t);
                float vw = t.measureText(vals[i]);
                t.setTypeface(regular);
                t.setTextSize(Math.min(h.sp(12), rest * .18f));
                t.setColor(DIM);
                if (!units[i].isEmpty() && x + vw + h.px(4) + t.measureText(units[i]) < x + col) {
                    c.drawText(units[i], x + vw + h.px(4), vy, t);
                }
            }

            // Zikir & Al-Quran.
            if (icQuran == null) {
                icQuran = h.getDrawable(R.drawable.ic_quran).mutate();
                icDzikir = h.getDrawable(R.drawable.ic_dzikir_tile).mutate();
            }
            boolean pagi = h.loc.dzikirPagi();
            button(c, dzikir, icDzikir, h.getString(pagi ? R.string.home_quick_dzikir_pagi : R.string.home_quick_dzikir_petang),
                    h.getString(DzikirService.playing ? R.string.home_media_playing : R.string.home_quick_dzikir_sub));
            String qsub = QuranService.playing ? String.format(Locale.US, "▶ %d : %d", QuranService.surah, QuranService.ayah)
                    : h.getString(R.string.home_quick_quran_sub);
            button(c, quran, icQuran, h.getString(R.string.home_quick_quran), qsub);
        }

        void button(Canvas c, RectF r, Drawable icon, String title, String sub) {
            fill(c, r, h.px(16), false);
            float s = Math.min(h.px(30), r.height() * .36f), x = r.left + h.px(14), y = r.centerY() - s / 2;
            icon.setBounds(Math.round(x), Math.round(y), Math.round(x + s), Math.round(y + s));
            icon.setTint(AMBER);
            icon.draw(c);
            float tx = x + s + h.px(12), avail = r.right - tx - h.px(10);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(medium);
            t.setLetterSpacing(0);
            t.setTextSize(Math.min(h.sp(17), r.height() * .22f));
            t.setColor(TEXT);
            CharSequence e = TextUtils.ellipsize(title, t, avail, TextUtils.TruncateAt.END);
            c.drawText(e, 0, e.length(), tx, r.centerY() - h.px(2), t);
            t.setTypeface(regular);
            t.setTextSize(Math.min(h.sp(12.5f), r.height() * .17f));
            t.setColor(DIM);
            e = TextUtils.ellipsize(sub, t, avail, TextUtils.TruncateAt.END);
            c.drawText(e, 0, e.length(), tx, r.centerY() + t.getTextSize() + h.px(4), t);
        }
    }

    // ---------------------------------------------------------------- kartu musik

    final class MediaCard extends View {
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF(), prev = new RectF(), play = new RectF(), next = new RectF();
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
            hit.inset(-h.px(10), -h.px(10));
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
            float pad = h.px(18), art = Math.min(ht - pad * 2, w * .34f);
            MediaNotch.Source s = src0;
            MediaController mc = s == MediaListener.SOURCE ? MediaListener.current : null;
            MediaMetadata md = mc != null ? mc.getMetadata() : null;

            // Sampul: dipotong bulat dan diperkecil sekali per gambar (lihat rounded), bukan clipPath tiap frame.
            box.set(pad, pad, pad + art, pad + art);
            Bitmap cover = md != null ? cover(md) : null;
            if (cover != null) {
                c.drawBitmap(rounded(cover, Math.round(art)), box.left, box.top, null);
            } else {
                if (placeholder == null || placeholderSize != Math.round(art)) {
                    placeholderSize = Math.round(art);
                    placeholder = new LinearGradient(0, 0, art, art, Theme.mix(AMBER, 0xFF3A3F8F, .75f), 0xFF1E2238,
                            Shader.TileMode.CLAMP);
                }
                p.setShader(placeholder);
                c.save();
                c.translate(box.left, box.top);
                c.drawRoundRect(0, 0, art, art, h.px(14), h.px(14), p);
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

            float x = pad + art + h.px(22), right = w - pad, avail = right - x;
            if (avail < h.px(80)) return;

            // Aplikasi.
            String app, sub, title, artist;
            Context ctx = h;
            if (s == null) {
                app = h.getString(R.string.home_media_none_app);
                sub = h.getString(R.string.home_media_player);
                boolean ok = MediaListener.connected();
                title = h.getString(ok ? R.string.home_media_idle : R.string.home_media_allow);
                artist = h.getString(ok ? R.string.home_media_idle_hint : R.string.home_media_allow_hint);
            } else if (mc != null) {
                app = MediaListener.appName(ctx, mc.getPackageName());
                sub = s.playing() ? h.getString(R.string.home_media_playing) : h.getString(R.string.home_media_paused);
                String tt = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
                String ar = md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
                if (ar == null && md != null) ar = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                title = tt != null && !tt.isEmpty() ? tt : app;
                artist = ar != null ? ar : "";
            } else {
                app = s == MediaNotch.QURAN ? h.getString(R.string.home_tile_quran) : h.getString(R.string.home_tile_dzikir);
                app = title(app);
                sub = s.playing() ? s.state(ctx) : h.getString(R.string.home_notch_paused);
                title = s.title(ctx);
                artist = "";
            }
            float u = Math.min(1.25f, Math.max(.75f, ht / (float) h.px(250)));
            float iconS = h.px(30) * u, y = pad + h.px(2);
            Drawable icon = mc != null ? icon(mc.getPackageName()) : h.getDrawable(R.drawable.ic_launcher);
            if (icon != null) {
                icon.setBounds(Math.round(x), Math.round(y), Math.round(x + iconS), Math.round(y + iconS));
                icon.draw(c);
            }
            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(medium);
            t.setTextSize(h.sp(16) * u);
            t.setLetterSpacing(0);
            t.setColor(TEXT);
            float tx = x + iconS + h.px(10);
            draw(c, app, tx, y + t.getTextSize() * .85f, right - tx);
            t.setTypeface(regular);
            t.setTextSize(h.sp(12) * u);
            t.setColor(DIM);
            draw(c, sub, tx, y + iconS + h.px(2), right - tx);

            // Judul dan artis.
            float ty = y + iconS + h.px(18) * u;
            t.setTypeface(medium);
            t.setTextSize(fit(title, h.sp(28) * u, avail));
            t.setColor(TEXT);
            ty += t.getTextSize();
            draw(c, title, x, ty, avail);
            t.setTypeface(regular);
            t.setTextSize(h.sp(16) * u);
            t.setColor(DIM);
            ty += t.getTextSize() + h.px(8);
            draw(c, artist, x, ty, avail);

            // Progres.
            long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0, pos = 0;
            PlaybackState ps = mc != null ? mc.getPlaybackState() : null;
            if (ps != null) {
                pos = ps.getPosition();
                if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
            }
            float btn = Math.min(h.px(56) * u, (ht - ty) * .5f), by = ht - pad - btn / 2f;
            float barY = Math.max(ty + h.px(16), by - btn / 2f - h.px(26) * u);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.light() ? 0x26000000 : 0x2EFFFFFF);
            box.set(x, barY - h.px(2), right, barY + h.px(2));
            c.drawRoundRect(box, h.px(2), h.px(2), p);
            if (dur > 0) {
                float f = Math.max(0, Math.min(1, pos / (float) dur));
                p.setColor(AMBER);
                box.set(x, barY - h.px(2), x + (right - x) * f, barY + h.px(2));
                c.drawRoundRect(box, h.px(2), h.px(2), p);
            }
            t.setTextSize(h.sp(11) * u);
            t.setColor(DIM);
            if (dur > 0 || s != null && mc != null) {
                c.drawText(mmss(pos), x, barY + h.px(18) * u, t);
                if (dur > 0) {
                    t.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(mmss(dur), right, barY + h.px(18) * u, t);
                    t.setTextAlign(Paint.Align.LEFT);
                }
            }

            // Tombol.
            float cx = x + avail / 2f, space = Math.min(avail / 3f, btn * 1.9f);
            play.set(cx - btn / 2f, by - btn / 2f, cx + btn / 2f, by + btn / 2f);
            prev.set(cx - space - btn / 2f, by - btn / 2f, cx - space + btn / 2f, by + btn / 2f);
            next.set(cx + space - btn / 2f, by - btn / 2f, cx + space + btn / 2f, by + btn / 2f);
            p.setColor(Theme.light() ? 0x1A000000 : 0x24FFFFFF);
            c.drawCircle(cx, by, btn / 2f, p);
            p.setColor(TEXT);
            float g = btn * .2f;
            boolean playing = s != null && s.playing();
            shape.rewind();
            if (playing) {
                c.drawRect(cx - g * .8f, by - g, cx - g * .25f, by + g, p);
                c.drawRect(cx + g * .25f, by - g, cx + g * .8f, by + g, p);
            } else {
                shape.moveTo(cx - g * .6f, by - g);
                shape.lineTo(cx + g, by);
                shape.lineTo(cx - g * .6f, by + g);
                shape.close();
                c.drawPath(shape, p);
            }
            skip(c, prev.centerX(), by, g, false);
            skip(c, next.centerX(), by, g, true);
        }

        private void skip(Canvas c, float cx, float cy, float g, boolean fwd) {
            shape.rewind();
            float d = fwd ? 1 : -1;
            shape.moveTo(cx - d * g * .8f, cy - g);
            shape.lineTo(cx + d * g * .5f, cy);
            shape.lineTo(cx - d * g * .8f, cy + g);
            shape.close();
            c.drawPath(shape, p);
            c.drawRect(Math.min(cx + d * g * .55f, cx + d * g * .9f), cy - g, Math.max(cx + d * g * .55f, cx + d * g * .9f),
                    cy + g, p);
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
            return tw <= avail ? size : Math.max(size * .7f, size * avail / tw);
        }

        private void draw(Canvas c, String s, float x, float y, float avail) {
            if (s == null || s.isEmpty()) return;
            CharSequence e = TextUtils.ellipsize(s, t, avail, TextUtils.TruncateAt.END);
            c.drawText(e, 0, e.length(), x, y, t);
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

        private Shader placeholder;
        private int placeholderSize;
        private Bitmap coverSrc, coverOut;

        /** Sampul seukuran kotak dengan sudut bulat; dibuat ulang hanya bila gambar atau ukurannya berganti. */
        Bitmap rounded(Bitmap src, int size) {
            if (src == coverSrc && coverOut != null && coverOut.getWidth() == size) return coverOut;
            coverSrc = src;
            Bitmap out = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size), Bitmap.Config.ARGB_8888);
            int bw = src.getWidth(), bh = src.getHeight(), side = Math.min(bw, bh);
            android.graphics.BitmapShader sh = new android.graphics.BitmapShader(src, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            float k = size / (float) side;
            m.setScale(k, k);
            m.postTranslate(-(bw - side) / 2f * k, -(bh - side) / 2f * k);
            sh.setLocalMatrix(m);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            q.setShader(sh);
            new Canvas(out).drawRoundRect(0, 0, size, size, h.px(14), h.px(14), q);
            coverOut = out;
            return out;
        }

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
    }

    // ---------------------------------------------------------------- volume & kecerahan

    /** Slider dengan ikon, label, dan persen; nilainya milik QuickPanel (volume MCU di NWD, kecerahan bergamma). */
    final class Slider extends View {
        final boolean vol;
        final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF track = new RectF(), arcBox = new RectF();
        boolean dragging;
        float downX;

        Slider(boolean vol) {
            super(h);
            this.vol = vol;
            setContentDescription(h.getString(vol ? R.string.quick_volume : R.string.quick_brightness));
        }

        QuickPanel.Level level() { return vol ? h.quick.volume : h.quick.brightness; }

        float iconW() { return getHeight() * .9f; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    dragging = e.getX() > iconW();
                    if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
                    if (dragging) pick(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) pick(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging && vol && Math.abs(e.getX() - downX) < h.px(12)) {
                        h.quick.toggleMute();
                        levels();
                    }
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
            }
            return super.onTouchEvent(e);
        }

        void pick(float x) {
            QuickPanel.Level l = level();
            float f = Math.max(0, Math.min(1, (x - track.left) / Math.max(1, track.width())));
            int v = Math.round(f * l.max);
            if (v == l.value) return;
            if (vol) h.quick.setVolume(v); else h.quick.setBrightness(v);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), ht = getHeight();
            QuickPanel.Level l = level();
            float cy = ht / 2f, ic = iconW(), s = ht * .16f, cx = ic / 2f + h.px(4);
            p.setStyle(Paint.Style.FILL);
            p.setColor(TEXT);
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
                    RectF a = arcBox;
                    a.set(cx - s * .3f, cy - s * .7f, cx + s * 1.1f, cy + s * .7f);
                    c.drawArc(a, -45, 90, false, p);
                    a.set(cx - s * .5f, cy - s * 1.2f, cx + s * 1.5f, cy + s * 1.2f);
                    c.drawArc(a, -45, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
            } else {
                c.drawCircle(cx, cy, s * .5f, p);
                p.setStrokeWidth(h.px(2));
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(cx + (float) Math.cos(a) * s * .8f, cy + (float) Math.sin(a) * s * .8f,
                            cx + (float) Math.cos(a) * s * 1.15f, cy + (float) Math.sin(a) * s * 1.15f, p);
                }
            }
            // Pemisah tipis antara ikon dan slider.
            p.setColor(Theme.light() ? 0x1F000000 : 0x1FFFFFFF);
            c.drawRect(ic, ht * .2f, ic + h.px(1), ht * .8f, p);

            int pct = Math.round(l.value * 100f / Math.max(1, l.max));
            t.setTypeface(regular);
            t.setTextSize(Math.min(h.sp(18), ht * .26f));
            t.setTextAlign(Paint.Align.RIGHT);
            t.setColor(TEXT);
            String ps = pct + "%";
            float pw = t.measureText("100%");
            c.drawText(ps, w - h.px(18), cy + t.getTextSize() * .45f, t);

            float x0 = ic + h.px(22), x1 = w - h.px(30) - pw;
            t.setTypeface(medium);
            t.setLetterSpacing(0.18f);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTextSize(Math.min(h.sp(11), ht * .16f));
            t.setColor(TEXT & 0x00FFFFFF | 0xB3000000);
            c.drawText(h.getString(vol ? R.string.quick_volume : R.string.quick_brightness).toUpperCase(Lang.locale()),
                    x0, cy - ht * .1f, t);
            t.setLetterSpacing(0);
            float ty = cy + ht * .14f, r = Math.min(h.px(11), ht * .14f);
            track.set(x0, ty - h.px(2), Math.max(x0 + 1, x1), ty + h.px(2));
            p.setColor(Theme.light() ? 0x26000000 : 0x33FFFFFF);
            c.drawRoundRect(track, h.px(2), h.px(2), p);
            float fx = x0 + (x1 - x0) * pct / 100f;
            p.setColor(AMBER);
            arcBox.set(x0, ty - h.px(2), fx, ty + h.px(2));
            c.drawRoundRect(arcBox, h.px(2), h.px(2), p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(fx, ty, r, p);
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
        if (drive != null) drive.invalidate();
        sky.invalidate();
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
        if (listening) {
            listening = false;
            try { h.unregisterReceiver(volumeRx); } catch (IllegalArgumentException ignored) { }
            h.getContentResolver().unregisterContentObserver(settingsRx);
        }
    }

    /** Tiap menit (updateTime). */
    void minute() {
        if (sky != null) sky.invalidate();
        if (weather != null) weather.invalidate();
        if (drive != null) drive.invalidate();
    }

    // ---------------------------------------------------------------- wallpaper

    static final int PICK_WALLPAPER = 41;

    static File wallpaperFile(Context c) {
        return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "wallpaper.jpg");
    }

    private Bitmap wall;

    void loadWallpaper() {
        File f = wallpaperFile(h);
        if (!f.isFile()) {
            wall = null;
            root.getBackground().invalidateSelf();
            return;
        }
        int sw = Math.round(h.wDp * h.dp), sh = Math.round(h.hDp * h.dp);
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= sw && o.outHeight / (s * 2) >= sh) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = fitScreen(BitmapFactory.decodeFile(f.getPath(), o), sw, sh);
            h.runOnUiThread(() -> {
                wall = b;
                root.getBackground().invalidateSelf();
            });
        }, "wallpaper").start();
    }

    /**
     * Dipotong dan diperkecil tepat seukuran layar, sudah digelapkan (RGB_565: separuh memori). Digambar 1:1 tanpa
     * penskalaan setiap kali latar home digambar ulang.
     */
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

    /** Latar home: wallpaper (dipotong memenuhi layar, digelapkan sedikit) atau gradasi gelap bawaan. */
    final class Wallpaper extends Drawable {
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Rect src = new Rect(), gradFor = new Rect();
        Shader grad;

        Wallpaper(HomeActivity a) { }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            Bitmap w = wall;
            if (w == null) {
                if (grad == null || !b.equals(gradFor)) {
                    gradFor.set(b);
                    grad = new LinearGradient(b.left, b.top, b.right, b.bottom,
                            Theme.light() ? new int[]{0xFFEDE7DA, 0xFFD9D2C3} : new int[]{0xFF141A26, 0xFF0B0E15, 0xFF120E0A},
                            null, Shader.TileMode.CLAMP);
                }
                p.setShader(grad);
                c.drawRect(b, p);
                p.setShader(null);
                return;
            }
            if (w.getWidth() == b.width() && w.getHeight() == b.height()) {
                c.drawBitmap(w, b.left, b.top, null);
            } else { // layar berubah ukuran sebelum home dibangun ulang
                src.set(0, 0, w.getWidth(), w.getHeight());
                c.drawBitmap(w, src, b, p);
            }
            c.drawColor(Theme.light() ? 0x33FFFFFF : 0x4D000000);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

}

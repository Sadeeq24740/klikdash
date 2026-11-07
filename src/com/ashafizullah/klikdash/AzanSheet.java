package com.ashafizullah.klikdash;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

import static com.ashafizullah.klikdash.HomeActivity.DIM;
import static com.ashafizullah.klikdash.HomeActivity.MATCH;
import static com.ashafizullah.klikdash.HomeActivity.TEXT;
import static com.ashafizullah.klikdash.HomeActivity.WRAP;

/**
 * Tetapan azan bergaya lembar iOS di atas home (panel cepat › AZAN): azan otomatis, per waktu sholat, fail azan sendiri
 * (pilih MP3, dengar, ganti), fail lain untuk Subuh, dan kekuatan suara. Fail dipilih lewat pemilih fail Android;
 * hasilnya kembali ke {@link HomeActivity#onActivityResult} lalu {@link #picked}.
 */
final class AzanSheet extends FrameLayout {
    static final int PICK = 42, PICK_SUBUH = 43;

    final HomeActivity h;
    final SharedPreferences p;
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL), medium = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            bold = Typeface.create("sans-serif", Typeface.BOLD);
    final boolean day = Theme.light();
    LinearLayout list;
    TextView fileName, fileInfo, subuhInfo, listenLabel;
    Toggle master;
    final Toggle[] perPrayer = new Toggle[5];
    Seg subuhSeg;

    AzanSheet(HomeActivity h) {
        super(h);
        this.h = h;
        p = h.prefs;
        setBackgroundColor(0x8C000000);
        setClickable(true);
        setOnClickListener(v -> close());

        LinearLayout panel = new LinearLayout(h);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(day ? 0xF7F2F2F7 : 0xF718181E);
        bg.setCornerRadius(h.px(26));
        bg.setStroke(Math.max(1, h.px(1)), day ? 0x1F000000 : 0x1AFFFFFF);
        panel.setBackground(bg);
        panel.setClickable(true);
        panel.setPadding(h.px(24), h.px(18), h.px(24), h.px(10));
        int w = Math.min(h.px(680), Math.round(h.wDp * h.dp * .9f));
        LayoutParams pl = new LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL);
        pl.topMargin = pl.bottomMargin = h.px(20);
        addView(panel, pl);

        LinearLayout head = new LinearLayout(h);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(h);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(h.getString(R.string.azan_settings_tag), 11, DIM, medium));
        titles.addView(text(h.getString(R.string.azan_title), 28, TEXT, bold));
        head.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView x = text("✕", 18, TEXT, bold);
        x.setGravity(Gravity.CENTER);
        x.setBackground(round(day ? 0x14000000 : 0x1AFFFFFF, h.px(22)));
        x.setOnClickListener(v -> close());
        head.addView(x, new LinearLayout.LayoutParams(h.px(44), h.px(44)));
        panel.addView(head);

        ScrollView scroll = new ScrollView(h);
        scroll.setVerticalScrollBarEnabled(true);
        list = new LinearLayout(h);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, h.px(10), 0, h.px(16));
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        panel.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        master = new Toggle(Azan.enabled(p), on -> {
            p.edit().putBoolean("azanOn", on).commit();
            PrayerAlarm.schedule(h);
        });
        list.addView(row(h.getString(R.string.azan_auto), h.getString(R.string.azan_auto_desc), master, true));

        section(R.string.azan_times);
        LinearLayout times = new LinearLayout(h);
        for (int i = 0; i < 5; i++) {
            final int k = i;
            LinearLayout cell = new LinearLayout(h);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, h.px(10), 0, h.px(10));
            cell.setBackground(round(day ? 0xFFFFFFFF : 0x0FFFFFFF, h.px(14)));
            TextView n = text(HomeCards.title(Lang.prayers[i]), 11, DIM, medium);
            n.setGravity(Gravity.CENTER);
            cell.addView(n);
            String t = h.loc.prayText != null ? h.loc.prayText[i] : "--:--";
            TextView tv = text(t, 18, TEXT, bold);
            tv.setGravity(Gravity.CENTER);
            cell.addView(tv);
            perPrayer[i] = new Toggle(Azan.forPrayer(p, i), on -> p.edit().putBoolean("azanP" + k, on).apply());
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(h.px(50), h.px(30));
            tl.topMargin = h.px(6);
            cell.addView(perPrayer[i], tl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i > 0) cl.leftMargin = h.px(8);
            times.addView(cell, cl);
        }
        list.addView(times);

        section(R.string.azan_voice);
        LinearLayout file = new LinearLayout(h);
        file.setOrientation(LinearLayout.VERTICAL);
        file.setPadding(h.px(14), h.px(12), h.px(14), h.px(12));
        file.setBackground(round(day ? 0xFFFFFFFF : 0x14FF8A5B, h.px(16)));
        LinearLayout fr = new LinearLayout(h);
        fr.setGravity(Gravity.CENTER_VERTICAL);
        TextView note = text("♪", 20, 0xFF111111, bold);
        note.setGravity(Gravity.CENTER);
        note.setBackground(round(0xFFFF8A5B, h.px(10)));
        fr.addView(note, new LinearLayout.LayoutParams(h.px(40), h.px(40)));
        LinearLayout fcol = new LinearLayout(h);
        fcol.setOrientation(LinearLayout.VERTICAL);
        fileName = text("", 15, TEXT, medium);
        fileInfo = text("", 12, DIM, regular);
        fcol.addView(fileName);
        fcol.addView(fileInfo);
        LinearLayout.LayoutParams fcl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        fcl.leftMargin = h.px(12);
        fr.addView(fcol, fcl);
        listenLabel = button("▶ " + h.getString(R.string.azan_listen), this::listen);
        fr.addView(listenLabel, btnParams());
        fr.addView(button(h.getString(R.string.azan_choose), () -> pick(PICK)), btnParams());
        file.addView(fr);
        TextView hint = text(h.getString(R.string.azan_file_hint), 12, DIM, regular);
        hint.setSingleLine(false);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(MATCH, WRAP);
        hl.topMargin = h.px(10);
        file.addView(hint, hl);
        list.addView(file);

        LinearLayout subuh = new LinearLayout(h);
        subuh.setGravity(Gravity.CENTER_VERTICAL);
        subuh.setPadding(h.px(14), h.px(10), h.px(10), h.px(10));
        subuh.setBackground(round(day ? 0xFFFFFFFF : 0x0FFFFFFF, h.px(14)));
        LinearLayout scol = new LinearLayout(h);
        scol.setOrientation(LinearLayout.VERTICAL);
        scol.addView(text(h.getString(R.string.azan_subuh), 15, TEXT, medium));
        subuhInfo = text("", 12, DIM, regular);
        scol.addView(subuhInfo);
        subuh.addView(scol, new LinearLayout.LayoutParams(0, WRAP, 1f));
        subuhSeg = new Seg(new String[]{h.getString(R.string.azan_subuh_same), h.getString(R.string.azan_subuh_other)},
                Azan.file(h, true).isFile() ? 1 : 0, i -> {
                    if (i == 1) pick(PICK_SUBUH);
                    else {
                        //noinspection ResultOfMethodCallIgnored
                        Azan.file(h, true).delete();
                        refresh();
                    }
                });
        subuh.addView(subuhSeg, new LinearLayout.LayoutParams(WRAP, h.px(38)));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = h.px(10);
        list.addView(subuh, sl);

        section(R.string.azan_sound);
        LinearLayout vol = new LinearLayout(h);
        vol.setGravity(Gravity.CENTER_VERTICAL);
        vol.setPadding(h.px(14), h.px(6), h.px(14), h.px(6));
        vol.setBackground(round(day ? 0xFFFFFFFF : 0x0FFFFFFF, h.px(14)));
        vol.addView(text(h.getString(R.string.azan_volume), 15, TEXT, medium), new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView pct = text(Azan.volume(p) + "%", 15, TEXT, bold);
        QuickPanel.Level level = new QuickPanel.Level(h, v -> {
            p.edit().putInt("azanVol", v * 10).apply();
            pct.setText(v * 10 + "%");
        });
        level.set(Azan.volume(p) / 10, 10);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, h.px(44), 1f);
        ll.leftMargin = ll.rightMargin = h.px(16);
        vol.addView(level, ll);
        vol.addView(pct, new LinearLayout.LayoutParams(h.px(52), WRAP));
        list.addView(vol);
        TextView pause = text(h.getString(R.string.azan_pause_note), 12, DIM, regular);
        pause.setSingleLine(false);
        LinearLayout.LayoutParams pl2 = new LinearLayout.LayoutParams(MATCH, WRAP);
        pl2.topMargin = h.px(8);
        list.addView(pause, pl2);
        refresh();
    }

    void refresh() {
        File f = Azan.file(h, false);
        boolean has = f.isFile();
        fileName.setText(has ? h.getString(R.string.azan_file_mine) : h.getString(R.string.azan_file_none));
        fileInfo.setText(has ? HomeCards.mmss(Azan.length(f)) + " · " + h.getString(R.string.azan_file_ready) : h.getString(R.string.azan_file_need));
        File s = Azan.file(h, true);
        subuhInfo.setText(s.isFile() ? HomeCards.mmss(Azan.length(s)) + " · " + h.getString(R.string.azan_subuh_own)
                : h.getString(R.string.azan_subuh_desc));
        subuhSeg.set(s.isFile() ? 1 : 0);
        listenLabel.setText(Azan.playing ? "■ " + h.getString(R.string.azan_stop) : "▶ " + h.getString(R.string.azan_listen));
    }

    void listen() {
        if (Azan.playing) Azan.stop(h);
        else if (Azan.file(h, false).isFile()) Azan.play(h, -1);
        else {
            pick(PICK);
            return;
        }
        postDelayed(this::refresh, 400);
    }

    void pick(int code) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            h.userLaunch = true;
            h.startActivityForResult(i, code);
        } catch (RuntimeException e) {
            h.userLaunch = false;
            Notice.info(h, h.getString(R.string.azan_no_picker));
            refresh();
        }
    }

    /** Hasil pemilih fail (HomeActivity.onActivityResult). */
    void picked(int code, android.net.Uri uri) {
        if (uri == null) {
            refresh();
            return;
        }
        boolean subuh = code == PICK_SUBUH;
        new Thread(() -> {
            boolean ok = Azan.copy(h, uri, subuh);
            h.runOnUiThread(() -> {
                if (ok) {
                    Notice.ok(h, h.getString(R.string.azan_file_saved));
                    if (!subuh && !Azan.enabled(p)) { // fail pertama: nyalakan sekalian
                        p.edit().putBoolean("azanOn", true).commit();
                        master.set(true);
                        PrayerAlarm.schedule(h);
                    }
                } else Notice.info(h, h.getString(R.string.azan_file_bad));
                refresh();
            });
        }, "azan-copy").start();
    }

    void close() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        h.azanSheet = null;
    }

    private void section(int label) {
        TextView t = text(h.getString(label).toUpperCase(Lang.locale()), 11, DIM, medium);
        t.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WRAP, WRAP);
        l.topMargin = h.px(16);
        l.bottomMargin = h.px(8);
        list.addView(t, l);
    }

    private View row(String title, String desc, View end, boolean accent) {
        LinearLayout r = new LinearLayout(h);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(h.px(16), h.px(12), h.px(14), h.px(12));
        r.setBackground(round(accent ? (day ? 0x26FF8A5B : 0x1FFF8A5B) : (day ? 0xFFFFFFFF : 0x0FFFFFFF), h.px(16)));
        LinearLayout col = new LinearLayout(h);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 15, TEXT, medium));
        TextView d = text(desc, 12, DIM, regular);
        d.setSingleLine(false);
        col.addView(d);
        r.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(h.px(52), h.px(32));
        el.leftMargin = h.px(12);
        r.addView(end, el);
        return r;
    }

    private TextView text(String s, float size, int color, Typeface tf) {
        TextView t = new TextView(h);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setPadding(0, h.px(2), 0, h.px(2));
        return t;
    }

    private TextView button(String s, Runnable r) {
        TextView t = text(s, 13, TEXT, medium);
        t.setGravity(Gravity.CENTER);
        t.setPadding(h.px(14), 0, h.px(14), 0);
        t.setBackground(round(day ? 0x14000000 : 0x1AFFFFFF, h.px(14)));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private LinearLayout.LayoutParams btnParams() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WRAP, h.px(38));
        l.leftMargin = h.px(8);
        return l;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    interface OnBool { void set(boolean on); }

    interface OnIndex { void set(int i); }

    /** Saklar iOS: hijau saat menyala. */
    final class Toggle extends View {
        boolean on;
        final OnBool cb;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();

        Toggle(boolean on, OnBool cb) {
            super(h);
            this.on = on;
            this.cb = cb;
            setClickable(true);
            setOnClickListener(v -> {
                set(!this.on);
                cb.set(this.on);
            });
        }

        void set(boolean v) {
            on = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = Math.min(getWidth(), getHeight() * 1.7f), ht = w / 1.7f, x = (getWidth() - w) / 2f, y = (getHeight() - ht) / 2f;
            box.set(x, y, x + w, y + ht);
            paint.setColor(on ? 0xFF34C759 : day ? 0x29787880 : 0x47787880);
            c.drawRoundRect(box, ht / 2, ht / 2, paint);
            paint.setColor(0xFFFFFFFF);
            float r = ht / 2 - h.px(2.5f);
            c.drawCircle(on ? box.right - ht / 2 : box.left + ht / 2, box.centerY(), r, paint);
        }
    }

    /** Pilihan bersegmen iOS. */
    final class Seg extends LinearLayout {
        final TextView[] items;
        int sel;

        Seg(String[] labels, int sel, OnIndex cb) {
            super(h);
            setPadding(h.px(3), h.px(3), h.px(3), h.px(3));
            setBackground(round(day ? 0x14000000 : 0x14FFFFFF, h.px(12)));
            items = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int k = i;
                TextView t = text(labels[i], 13, TEXT, medium);
                t.setGravity(Gravity.CENTER);
                t.setPadding(h.px(12), 0, h.px(12), 0);
                t.setOnClickListener(v -> {
                    set(k);
                    cb.set(k);
                });
                items[i] = t;
                addView(t, new LayoutParams(WRAP, MATCH));
            }
            set(sel);
        }

        void set(int s) {
            sel = s;
            for (int i = 0; i < items.length; i++) {
                items[i].setBackground(i == s ? round(day ? 0xFFFFFFFF : 0x40FF8A5B, h.px(9)) : null);
                items[i].setTextColor(i == s && !day ? 0xFFFF8A5B : TEXT);
            }
        }
    }

}

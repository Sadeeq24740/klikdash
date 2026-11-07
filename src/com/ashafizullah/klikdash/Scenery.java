package com.ashafizullah.klikdash;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Gambar home kartu yang digambar sendiri (tanpa aset): pemandangan jalan kota sebagai wallpaper bawaan (malam/siang),
 * versi kaburnya untuk kartu kaca ({@link Glass}), mobil sedan berlapis hologram untuk kartu kecepatan, dan siluet masjid
 * untuk kartu sholat. Semua digambar sekali ke bitmap lalu dipakai ulang; tidak ada yang dihitung tiap frame.
 */
final class Scenery {
    private Scenery() { }

    // ---------------------------------------------------------------- wallpaper bawaan

    /** Jalan menuju kota saat senja (malam) atau siang, seukuran layar. Koordinat rancangan 1280×720, diskalakan. */
    static Bitmap road(int w, int h, boolean day) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        float sx = w / 1280f, sy = h / 720f, s = Math.max(sx, sy);
        c.save();
        // Isi layar penuh (center-crop) dari kanvas rancangan 1280×720.
        c.translate((w - 1280 * s) / 2f, (h - 720 * s) / 2f);
        c.scale(s, s);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, 720, day ? new int[]{0xFF5AA0E6, 0xFFA9D1F2, 0xFFF3E2C6, 0xFF6F7A86}
                : new int[]{0xFF0B1330, 0xFF3A2A4A, 0xFFC8644A, 0xFF1A0F14}, new float[]{0, .55f, .72f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1280, 720, p);
        p.setShader(new RadialGradient(640, 470, 700, day ? 0x73FFFFFF : 0x8CFFB070, 0x00FFB070, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 220f / 700f, 640, 470);
        c.drawCircle(640, 470, 700, p);
        c.restore();
        p.setShader(null);
        p.setColor(day ? 0xFF5D6B7C : 0xFF0D0B14);
        float[][] city = {{0, 330, 70, 200}, {60, 280, 50, 250}, {105, 360, 80, 170}, {180, 300, 40, 230}, {215, 250, 60, 280},
                {270, 340, 90, 190}, {350, 390, 70, 140}, {840, 380, 70, 150}, {900, 290, 55, 240}, {950, 340, 90, 190},
                {1035, 230, 60, 300}, {1090, 320, 70, 210}, {1150, 270, 60, 260}, {1205, 350, 75, 180}};
        for (float[] r : city) c.drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], p);
        if (!day) {
            p.setColor(0x8CFFD38A);
            float[][] win = {{228, 270}, {246, 300}, {74, 310}, {1050, 260}, {1066, 300}, {915, 320}, {1165, 300}, {290, 370}};
            for (float[] r : win) c.drawRect(r[0], r[1], r[0] + 6, r[1] + 8, p);
        }
        Path road = new Path();
        road.moveTo(0, 720);
        road.lineTo(560, 470);
        road.lineTo(720, 470);
        road.lineTo(1280, 720);
        road.close();
        p.setShader(new LinearGradient(0, 470, 0, 720, day ? 0xFF7B8794 : 0xFF2A1D24, day ? 0xFF3D4651 : 0xFF07080B,
                Shader.TileMode.CLAMP));
        c.drawPath(road, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setColor(0x99F3C46B);
        for (float y = 470; y < 720; y += 56) c.drawLine(640, y, 640, Math.min(720, y + 30), p);
        p.setStrokeWidth(5);
        p.setColor(0x8CFF5A4A);
        c.drawLine(590, 470, 260, 720, p);
        p.setColor(0x80FFF3C4);
        c.drawLine(690, 470, 1020, 720, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(day ? 0x40FFFFFF : 0x59FFCF8A);
        c.drawCircle(420, 430, 18, p);
        c.drawCircle(860, 440, 24, p);
        c.drawCircle(980, 410, 12, p);
        c.drawCircle(300, 455, 10, p);
        c.restore();
        return b;
    }

    /**
     * Versi kabur kecil (1/8 ukuran) untuk kartu kaca: tiga putaran box blur, setara Gaussian. Digambar diperbesar dengan
     * filter bilinear, jadi tetap halus. Sekali per wallpaper; murah untuk semua versi Android (RenderEffect baru ada di 12).
     */
    static Bitmap blur(Bitmap src) {
        if (src == null) return null;
        int w = Math.max(8, src.getWidth() / 8), h = Math.max(8, src.getHeight() / 8);
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap small = scaled.copy(Bitmap.Config.ARGB_8888, true);
        if (scaled != src) scaled.recycle();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, 3, true);
            box(tmp, px, w, h, 3, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, div = r * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[idx(l, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
                sr += p >> 16 & 0xFF; sg += p >> 8 & 0xFF; sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[idx(l, i, w, horizontal)] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | sb / div;
                int add = in[idx(l, Math.min(len - 1, i + r + 1), w, horizontal)];
                int rem = in[idx(l, Math.max(0, i - r), w, horizontal)];
                sr += (add >> 16 & 0xFF) - (rem >> 16 & 0xFF);
                sg += (add >> 8 & 0xFF) - (rem >> 8 & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    // ---------------------------------------------------------------- kartu kaca

    /**
     * Latar kartu kaca seperti widget iOS: potongan wallpaper kabur tepat di belakang kartu (BitmapShader, tanpa clipPath),
     * lapisan warna tembus pandang, dan garis tepi tipis. Posisi kartu dibaca saat digambar, jadi ikut berpindah bila
     * tata letak berubah (mode menyetir, ubah susunan).
     */
    static final class Glass extends Drawable {
        final View owner, root;
        final Source src;
        final float radius, stroke;
        final int tint, edge;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Matrix m = new Matrix();
        final RectF box = new RectF();
        final int[] a = new int[2], b = new int[2];
        private Bitmap shaderFor;
        private BitmapShader shader;

        /** Sumber wallpaper kabur dan ukuran layar yang diwakilinya. */
        interface Source {
            Bitmap blurred();
        }

        Glass(View owner, View root, Source src, float radius, float stroke, int tint, int edge) {
            this.owner = owner;
            this.root = root;
            this.src = src;
            this.radius = radius;
            this.stroke = stroke;
            this.tint = tint;
            this.edge = edge;
        }

        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            box.set(r);
            Bitmap bl = src.blurred();
            if (bl != null && root.getWidth() > 0) {
                if (bl != shaderFor) {
                    shaderFor = bl;
                    shader = new BitmapShader(bl, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
                owner.getLocationInWindow(a);
                root.getLocationInWindow(b);
                m.setScale(root.getWidth() / (float) bl.getWidth(), root.getHeight() / (float) bl.getHeight());
                m.postTranslate(b[0] - a[0], b[1] - a[1]);
                shader.setLocalMatrix(m);
                paint.setShader(shader);
                c.drawRoundRect(box, radius, radius, paint);
            }
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(tint);
            c.drawRoundRect(box, radius, radius, fill);
            if (stroke > 0) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(stroke);
                fill.setColor(edge);
                float s = stroke / 2f;
                box.inset(s, s);
                c.drawRoundRect(box, radius - s, radius - s, fill);
            }
        }

        @Override public void getOutline(android.graphics.Outline o) {
            o.setRoundRect(getBounds(), radius);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- mobil

    /** Path SVG sederhana (perintah absolut M L C Z saja), cukup untuk gambar mobil di bawah. */
    static Path svg(String d) {
        Path p = new Path();
        String[] t = d.replace(",", " ").replaceAll("([MLCZ])", " $1 ").trim().split("\\s+");
        int i = 0;
        char cmd = 'M';
        while (i < t.length) {
            String s = t[i];
            if (s.length() == 1 && Character.isLetter(s.charAt(0))) {
                cmd = s.charAt(0);
                i++;
                if (cmd == 'Z') { p.close(); continue; }
            }
            switch (cmd) {
                case 'M': p.moveTo(f(t[i]), f(t[i + 1])); i += 2; cmd = 'L'; break;
                case 'L': p.lineTo(f(t[i]), f(t[i + 1])); i += 2; break;
                case 'C': p.cubicTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5])); i += 6; break;
                default: i++;
            }
        }
        return p;
    }

    private static float f(String s) { return Float.parseFloat(s); }

    private static final String BODY = "M22 128 C22 112 28 102 40 98 L60 92 C80 88 120 86 150 84 C178 58 222 34 278 30 "
            + "C322 28 352 34 378 48 L420 80 C460 86 500 96 526 110 C540 118 546 130 546 142 L544 160 C543 168 538 171 530 171 "
            + "L34 171 C26 171 22 166 22 158 Z";
    private static final String WIN_REAR = "M160 86 C188 60 226 42 274 40 L294 40 L298 88 Z";
    private static final String WIN_FRONT = "M306 40 C336 40 356 46 374 58 L404 86 L308 88 Z";
    private static final String TRIM = "M154 88 C182 58 222 36 276 34 C318 33 350 40 376 54 L414 86";

    /**
     * Sedan 4 pintu menghadap kanan (rancangan 560×205): lapisan hologram pelangi lembut, kaca gelap berbingkai hitam,
     * rok samping dan bibir depan hitam, rim putih lima jari dengan kaliper merah, lampu depan warna aksen.
     */
    static Bitmap car(int w, int accent) {
        int h = Math.round(w * 205f / 560f);
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(w / 560f, w / 560f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Bayangan.
        p.setShader(new RadialGradient(290, 190, 262, 0xBF000000, 0x00000000, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 12f / 262f, 290, 190);
        c.drawCircle(290, 190, 262, p);
        c.restore();
        Path body = svg(BODY);
        p.setShader(new LinearGradient(0, 0, 560, 196, new int[]{0xFFE9DCF5, 0xFFBFE3F4, 0xFFD9F0D6, 0xFFF6ECC6, 0xFFF5CFE6,
                0xFFC9D7FB, 0xFFEAD7F6}, new float[]{0, .18f, .34f, .5f, .66f, .82f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(body, p);
        p.setShader(new LinearGradient(0, 30, 0, 171, new int[]{0x8CFFFFFF, 0x00FFFFFF, 0x14000000, 0x59000000},
                new float[]{0, .35f, .7f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(body, p);
        p.setShader(new LinearGradient(160, 40, 400, 88, 0xFF3B4252, 0xFF05060A, Shader.TileMode.CLAMP));
        c.drawPath(svg(WIN_REAR), p);
        c.drawPath(svg(WIN_FRONT), p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF14161B);
        p.setStrokeWidth(4);
        c.drawPath(svg(TRIM), p);
        p.setStrokeWidth(7);
        c.drawLine(300, 38, 303, 89, p);
        p.setStrokeWidth(1.4f);
        p.setColor(0x738B88A0);
        c.drawLine(300, 92, 298, 166, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFA9A6BC);
        c.drawRoundRect(226, 100, 248, 104.5f, 2, 2, p);
        c.drawRoundRect(336, 100, 358, 104.5f, 2, 2, p);
        p.setColor(0xFF1A1C22);
        c.drawPath(svg("M398 84 L422 78 L428 90 L406 93 Z"), p);
        p.setColor(0xFF1B1C20);
        c.drawPath(svg("M150 166 L420 166 L418 172 L152 172 Z"), p);
        c.drawPath(svg("M30 92 L66 86 L64 90 L32 96 Z"), p);
        p.setColor(0xFF111215);
        c.drawPath(svg("M500 166 L548 160 L548 168 L506 173 Z"), p);
        p.setColor(0xFF2A2D36);
        c.drawPath(svg("M488 100 C510 104 528 112 538 122 L514 122 C502 116 494 110 488 100 Z"), p);
        p.setColor(accent);
        c.drawPath(svg("M500 108 C512 111 522 115 530 121 L516 121 C510 117 504 113 500 108 Z"), p);
        p.setColor(0xFFE9EEF5);
        Path tail = svg("M24 108 C30 100 40 98 52 98 L52 118 L26 120 Z");
        c.drawPath(tail, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(0xFFC23A32);
        c.drawPath(tail, p);
        p.setStyle(Paint.Style.FILL);
        wheel(c, p, 118, 164);
        wheel(c, p, 458, 164);
        return b;
    }

    private static void wheel(Canvas c, Paint p, float x, float y) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5);
        p.setColor(0xFF1D1E24);
        c.drawArc(x - 42, y - 42, x + 42, y + 42, 180, 180, false, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0D);
        c.drawCircle(x, y, 36, p);
        p.setColor(0xFFF3F4F7);
        c.drawCircle(x, y, 27, p);
        p.setColor(0xFF2A2B30);
        c.drawCircle(x, y, 24, p);
        p.setColor(0xFFD6271F);
        c.save();
        c.rotate(-20, x, y);
        c.drawRoundRect(x - 9, y - 16, x, y - 5, 2, 2, p);
        c.restore();
        p.setColor(0xFFF3F4F7);
        Path spoke = new Path();
        for (int i = 0; i < 5; i++) {
            spoke.rewind();
            spoke.moveTo(x - 4, y - 24);
            spoke.lineTo(x + 4, y - 24);
            spoke.lineTo(x + 3, y - 6);
            spoke.lineTo(x - 3, y - 6);
            spoke.close();
            c.save();
            c.rotate(i * 72, x, y);
            c.drawPath(spoke, p);
            c.restore();
        }
        c.drawCircle(x, y, 7, p);
        p.setColor(0xFF2A2B30);
        c.drawCircle(x, y, 3, p);
    }

    // ---------------------------------------------------------------- masjid

    /** Siluet masjid (kubah, dua kubah kecil, dua menara, bulan sabit di puncak) dengan jendela bercahaya; rancangan 400×200. */
    static void mosque(Canvas c, float right, float bottom, float height, int body, int glow) {
        float k = height / 200f;
        c.save();
        c.translate(right - 400 * k, bottom - 200 * k);
        c.scale(k, k);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(body);
        for (float mx : new float[]{47, 353}) {
            c.drawRect(mx - 7, 40, mx + 7, 200, p);
            Path tip = new Path();
            tip.moveTo(mx - 9, 40);
            tip.lineTo(mx, 10);
            tip.lineTo(mx + 9, 40);
            tip.close();
            c.drawPath(tip, p);
            c.drawCircle(mx, 44, 9, p);
        }
        Path dome = new Path();
        dome.moveTo(120, 200);
        dome.lineTo(120, 130);
        dome.cubicTo(120, 82, 160, 60, 200, 52);
        dome.cubicTo(240, 60, 280, 82, 280, 130);
        dome.lineTo(280, 200);
        dome.close();
        c.drawPath(dome, p);
        c.drawRect(196, 22, 204, 56, p);
        Path cres = new Path();
        cres.addCircle(200, 15, 8, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(204, 12, 6.5f, Path.Direction.CW);
        cres.op(cut, Path.Op.DIFFERENCE);
        c.drawPath(cres, p);
        for (float dx : new float[]{100, 300}) {
            Path d = new Path();
            d.moveTo(dx - 30, 200);
            d.lineTo(dx - 30, 150);
            d.cubicTo(dx - 30, 128, dx - 14, 116, dx, 112);
            d.cubicTo(dx + 14, 116, dx + 30, 128, dx + 30, 150);
            d.lineTo(dx + 30, 200);
            d.close();
            c.drawPath(d, p);
        }
        c.drawRect(60, 160, 350, 200, p);
        p.setColor(glow);
        c.drawRoundRect(188, 138, 212, 180, 12, 12, p);
        c.drawRect(188, 150, 212, 180, p);
        c.drawRoundRect(92, 157, 108, 185, 8, 8, p);
        c.drawRoundRect(292, 157, 308, 185, 8, 8, p);
        c.restore();
    }
}

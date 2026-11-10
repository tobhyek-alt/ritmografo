package com.ritmografo.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Fondo de pantalla animado: espectro en barras 3D que se mueve con lo que suena
 * en el teléfono (Spotify, YouTube, etc.). Usa el Visualizer de Android sobre la
 * mezcla global de audio (sesión 0).
 */
public class RitmoWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new RitmoEngine();
    }

    private class RitmoEngine extends Engine implements Visualizer.OnDataCaptureListener {

        private static final int BARS = 28;     // barras por fila
        private static final int ROWS = 10;     // filas de profundidad (historial)
        private static final int SNAP_EVERY = 5; // cuadros entre cada fila nueva

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable drawer = this::drawFrame;
        private boolean visible = false;
        private Visualizer vis;
        private boolean noPermission = false, visError = false;

        // audio
        private final float[] targetBands = new float[BARS];
        private final float[] bands = new float[BARS];
        private final ArrayList<float[]> rows = new ArrayList<>();
        private int frame = 0;
        private float samplingHz = 44100f;
        private int captureSize = 1024;

        // golpes
        private final ArrayList<float[]> recent = new ArrayList<>();
        private final ArrayList<Float> beatTimes = new ArrayList<>();
        private float lastBeat = -10, pulse = 0, bpm = 0, lastAudioT = -10;

        // dibujo
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bpmText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path quad = new Path();

        private static final int[] BG = {10, 12, 17};

        RitmoEngine() {
            bpmText.setColor(Color.WHITE);
            bpmText.setTextAlign(Paint.Align.CENTER);
            bpmText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            smallText.setTextAlign(Paint.Align.CENTER);
            smallText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            for (int r = 0; r < ROWS; r++) rows.add(new float[BARS]);
        }

        // ---------- ciclo de vida ----------
        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            handler.removeCallbacks(drawer);
            if (v) { startVisualizer(); handler.post(drawer); }
            else stopVisualizer();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            handler.removeCallbacks(drawer);
            stopVisualizer();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacks(drawer);
            stopVisualizer();
            super.onDestroy();
        }

        private void startVisualizer() {
            if (vis != null) return;
            noPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
            if (noPermission) return;
            try {
                vis = new Visualizer(0);
                vis.setEnabled(false);
                captureSize = Math.min(1024, Visualizer.getCaptureSizeRange()[1]);
                vis.setCaptureSize(captureSize);
                vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                vis.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), false, true);
                samplingHz = vis.getSamplingRate() / 1000f;
                vis.setEnabled(true);
                visError = false;
            } catch (Throwable t) {
                visError = true;
                if (vis != null) { try { vis.release(); } catch (Throwable ignored) {} }
                vis = null;
            }
        }

        private void stopVisualizer() {
            if (vis != null) {
                try { vis.setEnabled(false); vis.release(); } catch (Throwable ignored) {}
                vis = null;
            }
        }

        private float now() { return SystemClock.uptimeMillis() / 1000f; }

        // ---------- análisis ----------
        @Override
        public void onWaveFormDataCapture(Visualizer v, byte[] bytes, int rate) { }

        @Override
        public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
            int n = fft.length / 2;
            float binHz = samplingHz / captureSize;
            if (binHz <= 0) binHz = 43f;
            float[] mag = new float[n];
            for (int k = 1; k < n; k++) {
                float re = fft[2 * k], im = fft[2 * k + 1];
                mag[k] = (float) Math.hypot(re, im) / 128f;
            }
            float fMin = 30f, fMax = 14000f;
            for (int i = 0; i < BARS; i++) {
                float f0 = (float) (fMin * Math.pow(fMax / fMin, i / (double) BARS));
                float f1 = (float) (fMin * Math.pow(fMax / fMin, (i + 1) / (double) BARS));
                int a = Math.max(1, (int) Math.floor(f0 / binHz));
                int b = Math.max(a + 1, (int) Math.ceil(f1 / binHz));
                float m = 0;
                for (int k = a; k < b && k < n; k++) m = Math.max(m, mag[k]);
                targetBands[i] = (float) Math.min(1.0, Math.pow(m, 0.6) * (1f + 0.8f * i / BARS));
            }
            int hi = Math.max(2, Math.round(150f / binHz));
            float e = 0;
            for (int k = 1; k <= hi && k < n; k++) e += mag[k] * mag[k];
            detectBeat(e / hi);
        }

        private void detectBeat(float e) {
            float t = now();
            if (e > 0.002f) lastAudioT = t;
            recent.add(new float[]{t, e});
            while (!recent.isEmpty() && t - recent.get(0)[0] > 1.3f) recent.remove(0);
            float avg = 0, var = 0;
            for (float[] r : recent) avg += r[1];
            avg /= recent.size();
            for (float[] r : recent) var += (r[1] - avg) * (r[1] - avg);
            var /= recent.size();
            float thr = avg * 1.15f + (float) Math.sqrt(var) * 0.8f + 0.005f;
            if (e > thr && e > 0.02f && t - lastBeat > 0.27f && recent.size() > 6) {
                lastBeat = t;
                pulse = 1f;
                beatTimes.add(t);
                while (!beatTimes.isEmpty() && t - beatTimes.get(0) > 10f) beatTimes.remove(0);
                if (beatTimes.size() >= 5) {
                    float[] iv = new float[beatTimes.size() - 1];
                    for (int i = 1; i < beatTimes.size(); i++) iv[i - 1] = beatTimes.get(i) - beatTimes.get(i - 1);
                    Arrays.sort(iv);
                    float med = iv[iv.length / 2];
                    if (med > 0) {
                        float b = 60f / med;
                        while (b < 75) b *= 2;
                        while (b > 190) b /= 2;
                        bpm = bpm == 0 ? b : bpm * 0.7f + b * 0.3f;
                    }
                }
            }
        }

        // ---------- dibujo ----------
        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) render(c);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) { try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) {} }
            }
            handler.removeCallbacks(drawer);
            if (visible) handler.postDelayed(drawer, 16);
        }

        private int[] barColor(int i) {
            float f = i / (float) (BARS - 1);
            if (f < 0.13f) return new int[]{184, 243, 90};
            if (f < 0.5f) return new int[]{255, 200, 87};
            if (f < 0.75f) return new int[]{255, 140, 90};
            return new int[]{255, 77, 109};
        }

        /** k = brillo de la cara; depth 0..1 = cuánto se mezcla con el fondo. */
        private int shade(int[] c, float k, float depth) {
            int[] o = new int[3];
            for (int j = 0; j < 3; j++) {
                float v = Math.min(255f, c[j] * k);
                o[j] = Math.round(v * (1 - depth) + BG[j] * depth);
            }
            return Color.rgb(o[0], o[1], o[2]);
        }

        private void drawQuad(Canvas c, float ax, float ay, float bx, float by,
                              float cx, float cy, float dx, float dy, int color) {
            quad.reset();
            quad.moveTo(ax, ay); quad.lineTo(bx, by); quad.lineTo(cx, cy); quad.lineTo(dx, dy); quad.close();
            face.setShader(null);
            face.setColor(color);
            c.drawPath(quad, face);
        }

        private void render(Canvas c) {
            int w = c.getWidth(), h = c.getHeight();
            float t = now();
            boolean silent = t - lastAudioT > 1.5f;

            // suavizado de la fila de enfrente
            for (int i = 0; i < BARS; i++) {
                float target = silent ? 0.02f + 0.02f * (float) Math.sin(t * 2 + i * 0.4f) : targetBands[i];
                bands[i] += (target - bands[i]) * (target > bands[i] ? 0.55f : 0.14f);
            }
            if (frame++ % SNAP_EVERY == 0) {
                float[] snap = rows.remove(rows.size() - 1);
                System.arraycopy(bands, 0, snap, 0, BARS);
                rows.add(1, snap);
            }
            rows.set(0, bands);

            // fondo
            fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF10131C, 0xFF0A0C11, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);

            // geometría 3D (proyección oblicua)
            float ddx = w * 0.020f, ddy = -w * 0.016f;   // desplazamiento por fila hacia el fondo
            float span = ddx * (ROWS - 1);
            float x0 = w * 0.05f, W = w * 0.90f - span;
            float step = W / BARS, bw = step * 0.72f;
            float y0 = h * 0.80f, maxH = h * 0.40f;
            float dX = ddx * 0.85f, dY = ddy * 0.85f;

            // resplandor del piso con cada golpe
            float glowR = w * 0.75f;
            fill.setShader(new RadialGradient(w / 2f, y0, glowR,
                    Color.argb((int) (30 + 110 * pulse), 255, 77, 109), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(w / 2f, y0, glowR, fill);
            fill.setShader(null);

            // de atrás hacia adelante, de izquierda a derecha
            for (int r = ROWS - 1; r >= 0; r--) {
                float[] row = rows.get(r);
                float depth = Math.min(0.85f, r / (float) (ROWS - 1) * 0.9f);
                float ox = x0 + ddx * r, oy = y0 + ddy * r;
                for (int i = 0; i < BARS; i++) {
                    float v = row[i];
                    float bh = Math.max(3f, v * maxH);
                    int[] col = barColor(i);
                    float x = ox + i * step, top = oy - bh;
                    float glow = r == 0 ? 0.75f + 0.45f * v + 0.25f * pulse : 0.8f;
                    float sx = x + bw;
                    // costado
                    drawQuad(c, sx, oy, sx + dX, oy + dY, sx + dX, top + dY, sx, top, shade(col, 0.5f * glow, depth));
                    // tapa
                    drawQuad(c, x, top, x + bw, top, x + bw + dX, top + dY, x + dX, top + dY, shade(col, 1.25f * glow, depth));
                    // frente
                    if (r == 0) {
                        face.setShader(new LinearGradient(0, top, 0, oy,
                                shade(col, 0.95f * glow, 0), shade(col, 0.45f * glow, 0.25f), Shader.TileMode.CLAMP));
                    } else {
                        face.setShader(null);
                        face.setColor(shade(col, 0.8f * glow, depth));
                    }
                    c.drawRect(x, top, x + bw, oy, face);
                    face.setShader(null);
                }
            }

            // reflejo suave bajo la fila de enfrente
            for (int i = 0; i < BARS; i++) {
                float x = x0 + i * step, rh = Math.max(3f, bands[i] * maxH) * 0.35f;
                int[] col = barColor(i);
                face.setShader(new LinearGradient(0, y0, 0, y0 + rh,
                        Color.argb(70, col[0], col[1], col[2]), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(x, y0 + 2, x + bw, y0 + 2 + rh, face);
            }
            face.setShader(null);

            // BPM
            float u = Math.min(w, h);
            bpmText.setTextSize(u * 0.16f);
            bpmText.setColor(Color.argb((int) (170 + 85 * pulse), 255, 255, 255));
            String txt = (bpm > 0 && !silent) ? String.valueOf(Math.round(bpm)) : "--";
            c.drawText(txt, w / 2f, h * 0.22f, bpmText);
            smallText.setTextSize(u * 0.04f);
            smallText.setColor(Color.argb((int) (120 + 135 * pulse), 255, 77, 109));
            c.drawText("BPM", w / 2f, h * 0.22f + u * 0.06f, smallText);

            // mensajes
            smallText.setColor(0xFF8A91A5);
            smallText.setTextSize(u * 0.037f);
            String msg = null;
            if (noPermission) msg = "Abre Ritmógrafo y permite el micrófono";
            else if (visError) msg = "Tu teléfono no permite capturar el audio";
            else if (silent) msg = "Pon música en Spotify ♪";
            if (msg != null) c.drawText(msg, w / 2f, h * 0.88f, smallText);

            pulse *= 0.9f;
        }
    }
}

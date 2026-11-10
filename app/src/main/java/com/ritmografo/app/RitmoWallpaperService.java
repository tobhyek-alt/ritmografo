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
 * Fondo de pantalla animado que escucha lo que suena en el teléfono (Spotify,
 * YouTube, etc.) usando el Visualizer de Android sobre la mezcla global (sesión 0).
 */
public class RitmoWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new RitmoEngine();
    }

    private class RitmoEngine extends Engine implements Visualizer.OnDataCaptureListener {

        private static final int BARS = 72;
        private static final int HIST = 180;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable drawer = this::drawFrame;
        private boolean visible = false;
        private Visualizer vis;
        private boolean noPermission = false, visError = false;

        // datos de audio
        private volatile byte[] wave;
        private final float[] bands = new float[BARS];
        private final float[] targetBands = new float[BARS];
        private float samplingHz = 44100f;
        private int captureSize = 1024;

        // detección de golpes
        private final ArrayList<float[]> recent = new ArrayList<>(); // {t, e}
        private final ArrayList<Float> beatTimes = new ArrayList<>();
        private float lastBeat = 0, pulse = 0, level = 0, smoothLevel = 0;
        private float bpm = 0;
        private final float[] energyHist = new float[HIST];
        private final boolean[] beatHist = new boolean[HIST];
        private int histPos = 0;
        private float lastAudioT = -10;

        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private static final int BG = 0xFF0A0C11;
        private static final int BEAT = 0xFFFF4D6D;
        private static final int WAVE = 0xFF4CC9F0;
        private static final int BASS = 0xFFB8F35A;

        RitmoEngine() {
            barPaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            smallText.setColor(0xFF8A91A5);
            smallText.setTextAlign(Paint.Align.CENTER);
            smallText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                startVisualizer();
                handler.removeCallbacks(drawer);
                handler.post(drawer);
            } else {
                handler.removeCallbacks(drawer);
                stopVisualizer();
            }
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
                int[] range = Visualizer.getCaptureSizeRange();
                captureSize = Math.min(1024, range[1]);
                vis.setCaptureSize(captureSize);
                vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                vis.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), true, true);
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

        @Override
        public void onWaveFormDataCapture(Visualizer v, byte[] bytes, int rate) {
            wave = bytes.clone();
        }

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
            // bandas logarítmicas 30 Hz – 14 kHz
            float fMin = 30f, fMax = 14000f;
            for (int i = 0; i < BARS; i++) {
                float f0 = (float) (fMin * Math.pow(fMax / fMin, i / (double) BARS));
                float f1 = (float) (fMin * Math.pow(fMax / fMin, (i + 1) / (double) BARS));
                int a = Math.max(1, (int) Math.floor(f0 / binHz));
                int b = Math.max(a + 1, (int) Math.ceil(f1 / binHz));
                float m = 0;
                for (int k = a; k < b && k < n; k++) m = Math.max(m, mag[k]);
                // compresión para que se vean los agudos
                targetBands[i] = (float) Math.min(1.0, Math.pow(m, 0.6) * (1f + i / (float) BARS));
            }
            // energía de graves (<150 Hz)
            int hi = Math.max(2, Math.round(150f / binHz));
            float e = 0, all = 0;
            for (int k = 1; k <= hi && k < n; k++) e += mag[k] * mag[k];
            e /= hi;
            for (int k = 1; k < n; k++) all += mag[k];
            level = Math.min(1f, all / n * 6f);
            detectBeat(e);
        }

        private void detectBeat(float e) {
            float t = now();
            if (e > 0.002f) lastAudioT = t;
            recent.add(new float[]{t, e});
            while (!recent.isEmpty() && t - recent.get(0)[0] > 1.3f) recent.remove(0);
            float avg = 0;
            for (float[] r : recent) avg += r[1];
            avg /= recent.size();
            float var = 0;
            for (float[] r : recent) var += (r[1] - avg) * (r[1] - avg);
            var /= recent.size();
            float thr = avg * 1.15f + (float) Math.sqrt(var) * 0.8f + 0.005f;
            boolean beat = false;
            if (e > thr && e > 0.02f && t - lastBeat > 0.27f && recent.size() > 6) {
                beat = true;
                lastBeat = t;
                pulse = 1f;
                beatTimes.add(t);
                while (!beatTimes.isEmpty() && t - beatTimes.get(0) > 10f) beatTimes.remove(0);
                updateBpm();
            }
            energyHist[histPos] = e;
            beatHist[histPos] = beat;
            histPos = (histPos + 1) % HIST;
        }

        private void updateBpm() {
            if (beatTimes.size() < 5) return;
            float[] iv = new float[beatTimes.size() - 1];
            for (int i = 1; i < beatTimes.size(); i++) iv[i - 1] = beatTimes.get(i) - beatTimes.get(i - 1);
            Arrays.sort(iv);
            float med = iv[iv.length / 2];
            if (med <= 0) return;
            float b = 60f / med;
            while (b < 75) b *= 2;
            while (b > 190) b /= 2;
            bpm = bpm == 0 ? b : bpm * 0.7f + b * 0.3f;
        }

        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) render(c);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
                }
            }
            handler.removeCallbacks(drawer);
            if (visible) handler.postDelayed(drawer, 16);
        }

        private void render(Canvas c) {
            int w = c.getWidth(), h = c.getHeight();
            float t = now();
            boolean silent = t - lastAudioT > 1.5f;

            // suavizado
            for (int i = 0; i < BARS; i++) {
                float target = silent ? 0 : targetBands[i];
                bands[i] += (target - bands[i]) * (target > bands[i] ? 0.5f : 0.12f);
            }
            smoothLevel += ((silent ? 0 : level) - smoothLevel) * 0.1f;

            // fondo
            fillPaint.setShader(new LinearGradient(0, 0, 0, h, 0xFF0E1018, BG, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fillPaint);
            fillPaint.setShader(null);

            float cx = w / 2f, cy = h * 0.42f;
            float base = Math.min(w, h) * 0.17f;
            float breathe = silent ? (float) (0.06 * Math.sin(t * 1.6)) : 0;
            float r = base * (1f + 0.22f * pulse + breathe);

            // halo
            float haloR = r * (2.2f + smoothLevel);
            fillPaint.setShader(new RadialGradient(cx, cy, haloR,
                    Color.argb((int) (40 + 90 * pulse), 255, 77, 109), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, haloR, fillPaint);
            fillPaint.setShader(null);

            // ondas que se expanden con cada golpe
            float since = t - lastBeat;
            if (since < 1.2f && !silent) {
                for (int k = 0; k < 3; k++) {
                    float p = since / 1.2f + k * 0.12f;
                    if (p > 1) continue;
                    linePaint.setColor(Color.argb((int) (160 * (1 - p)), 255, 77, 109));
                    linePaint.setStrokeWidth(3f);
                    c.drawCircle(cx, cy, r * (1.1f + p * 1.6f), linePaint);
                }
            }

            // espectro circular
            float inner = r * 1.18f, maxLen = Math.min(w, h) * 0.22f;
            barPaint.setStrokeWidth(Math.max(3f, (float) (2 * Math.PI * inner / BARS * 0.55)));
            for (int half = 0; half < 2; half++) {
                for (int i = 0; i < BARS; i++) {
                    double ang = -Math.PI / 2 + (half == 0 ? 1 : -1) * Math.PI * (i + 0.5) / BARS;
                    float v = bands[i];
                    float len = 4f + v * maxLen;
                    float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
                    int col = i < BARS * 0.12f ? BASS : blend(WAVE, BEAT, i / (float) BARS);
                    barPaint.setColor(withAlpha(col, (int) (90 + 165 * Math.min(1f, v * 1.4f))));
                    c.drawLine(cx + cos * inner, cy + sin * inner,
                            cx + cos * (inner + len), cy + sin * (inner + len), barPaint);
                }
            }

            // círculo central
            fillPaint.setShader(new RadialGradient(cx, cy, r,
                    Color.argb((int) (120 + 135 * pulse), 255, 120, 145),
                    Color.argb((int) (40 + 120 * pulse), 255, 77, 109), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, fillPaint);
            fillPaint.setShader(null);

            // BPM al centro
            textPaint.setTextSize(base * 0.55f);
            String bpmTxt = (bpm > 0 && !silent) ? String.valueOf(Math.round(bpm)) : "--";
            c.drawText(bpmTxt, cx, cy + base * 0.15f, textPaint);
            smallText.setTextSize(base * 0.17f);
            smallText.setColor(0xCCFFFFFF);
            c.drawText("BPM", cx, cy + base * 0.42f, smallText);

            // forma de onda
            byte[] wv = wave;
            float wy = h * 0.74f, wh = h * 0.06f;
            if (wv != null && !silent) {
                path.reset();
                int N = Math.min(wv.length, 512);
                for (int i = 0; i < N; i++) {
                    float x = i / (float) (N - 1) * w;
                    float y = wy - ((wv[i] & 0xFF) - 128) / 128f * wh;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                linePaint.setColor(withAlpha(WAVE, 200));
                linePaint.setStrokeWidth(3f);
                c.drawPath(path, linePaint);
            } else {
                linePaint.setColor(0x334CC9F0);
                linePaint.setStrokeWidth(2f);
                c.drawLine(0, wy, w, wy, linePaint);
            }

            // historial de energía de graves con golpes
            float hy = h * 0.88f, hh = h * 0.07f;
            float maxE = 0.05f;
            for (float e : energyHist) maxE = Math.max(maxE, e);
            path.reset();
            for (int i = 0; i < HIST; i++) {
                int idx = (histPos + i) % HIST;
                float x = i / (float) (HIST - 1) * w;
                float y = hy - energyHist[idx] / maxE * hh;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                if (beatHist[idx]) {
                    fillPaint.setColor(0x55FF4D6D);
                    c.drawRect(x - 2, hy - hh - 6, x + 2, hy + 4, fillPaint);
                }
            }
            linePaint.setColor(withAlpha(BASS, 210));
            linePaint.setStrokeWidth(3f);
            c.drawPath(path, linePaint);

            // mensajes
            smallText.setColor(0xFF8A91A5);
            smallText.setTextSize(Math.min(w, h) * 0.035f);
            if (noPermission) {
                c.drawText("Abre Ritmógrafo y permite el micrófono", cx, h * 0.66f, smallText);
            } else if (visError) {
                c.drawText("Tu teléfono no permite capturar el audio", cx, h * 0.66f, smallText);
            } else if (silent) {
                c.drawText("Pon música en Spotify ♪", cx, h * 0.66f, smallText);
            }

            pulse *= 0.9f;
        }

        private int withAlpha(int color, int a) {
            return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
        }

        private int blend(int a, int b, float f) {
            int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f);
            int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f);
            int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
            return Color.rgb(r, g, bl);
        }
    }
}

package nl.finny.cydstreamer;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import okhttp3.*;
import okio.ByteString;

public class StreamService extends Service {
    public static final String ACTION_OVERLAY = "overlay";
    public static volatile boolean running = false;

    private static final int OUT_W = 320, OUT_H = 240;

    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler h;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private OkHttpClient http;
    private WebSocket ws;
    private volatile boolean wsOpen = false;
    private volatile int inFlight = 0;
    private long lastSend = 0;
    private int capW, capH, dpi, lastRot = -1;

    private Bitmap src, out;
    private Canvas outCanvas;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ByteArrayOutputStream jpg = new ByteArrayOutputStream(40000);
    private int frames = 0;
    private long fpsStart = 0;

    private WindowManager wm;
    private View overlay;
    private TextView overlayText;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_OVERLAY.equals(intent.getAction())) { showOverlay(); return START_NOT_STICKY; }
        if (intent == null || running) return START_NOT_STICKY;

        startFg();
        Cfg.load(this);
        running = true;

        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(intent.getIntExtra("code", 0), intent.getParcelableExtra("data"));
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        thread = new HandlerThread("capture", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        h = new Handler(thread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, h);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        outCanvas = new Canvas(out);

        h.post(this::setupDisplay);
        connect();
        showOverlay();
        h.post(tick);
        ui.post(overlayUpdate);
        return START_NOT_STICKY;
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("s", "Streamen", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "s")
            .setContentTitle("CYD Streamer")
            .setContentText("Scherm wordt naar het bordje gestuurd")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentIntent(open)
            .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
    }

    /** Maakt (opnieuw) de opname aan; ook als je je telefoon draait (bv. liggend gamen). */
    @SuppressWarnings("deprecation")
    private void setupDisplay() {
        DisplayMetrics dm = new DisplayMetrics();
        Display d = wm.getDefaultDisplay();
        d.getRealMetrics(dm);
        lastRot = d.getRotation();
        // op halve resolutie opnemen: scheelt veel rekenwerk, bordje is toch maar 320x240
        float scale = 640f / Math.max(dm.widthPixels, dm.heightPixels);
        if (scale > 1) scale = 1;
        capW = Math.round(dm.widthPixels * scale) & ~1;
        capH = Math.round(dm.heightPixels * scale) & ~1;
        dpi = dm.densityDpi;

        ImageReader old = reader;
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        if (vd == null) {
            vd = projection.createVirtualDisplay("cyd", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
        } else {
            vd.resize(capW, capH, dpi);
            vd.setSurface(reader.getSurface());
        }
        if (old != null) old.close();
        src = null;
    }

    private void connect() {
        if (http == null) http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build();
        Cfg.status = "Verbinden...";
        Request req = new Request.Builder().url("ws://" + Cfg.ip + ":81/").build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) { wsOpen = true; inFlight = 0; Cfg.status = "Verbonden"; }
            @Override public void onMessage(WebSocket w, String t) { if (inFlight > 0) inFlight--; }
            @Override public void onMessage(WebSocket w, ByteString b) { if (inFlight > 0) inFlight--; }
            @Override public void onClosed(WebSocket w, int c, String r) { lost(); }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) { lost(); }
        });
    }

    private void lost() {
        wsOpen = false;
        if (!running) return;
        Cfg.status = "Verbinding kwijt, opnieuw...";
        if (h != null) h.postDelayed(this::connect, 1500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long start = SystemClock.uptimeMillis();
            try { frame(); } catch (Throwable t) { Cfg.status = "Fout: " + t.getClass().getSimpleName(); }
            long interval = 1000L / Math.max(1, Cfg.fps);
            long wait = interval - (SystemClock.uptimeMillis() - start);
            h.postDelayed(this, Math.max(1, wait));
        }
    };

    @SuppressWarnings("deprecation")
    private void frame() {
        if (wm.getDefaultDisplay().getRotation() != lastRot) setupDisplay();

        Image img = reader.acquireLatestImage();
        if (img == null) return;
        long now = SystemClock.uptimeMillis();
        // flow control: max 2 frames onderweg, anders bordje overspoelen = haperen
        if (!wsOpen || (inFlight >= 2 && now - lastSend < 400)) { img.close(); return; }
        if (now - lastSend >= 400) inFlight = 0;

        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int rowW = p.getRowStride() / p.getPixelStride();
        if (src == null || src.getWidth() != rowW || src.getHeight() != capH)
            src = Bitmap.createBitmap(rowW, capH, Bitmap.Config.ARGB_8888);
        buf.rewind();
        src.copyPixelsFromBuffer(buf);
        img.close();

        // uitsnede in pixels
        int l = Cfg.left, t = Cfg.top;
        int w = Math.min(Cfg.width, 100 - l), hh = Math.min(Cfg.height, 100 - t);
        float cx = capW * (l + w / 2f) / 100f, cy = capH * (t + hh / 2f) / 100f;
        float cw = Math.max(1, capW * w / 100f), ch = Math.max(1, capH * hh / 100f);
        int rot = Cfg.rotation;
        boolean sideways = rot == 90 || rot == 270;
        float rw = sideways ? ch : cw, rh = sideways ? cw : ch;

        Matrix mtx = new Matrix();
        mtx.postTranslate(-cx, -cy);
        mtx.postRotate(rot);
        mtx.postScale(OUT_W / rw, OUT_H / rh);
        mtx.postTranslate(OUT_W / 2f, OUT_H / 2f);
        outCanvas.drawColor(Color.BLACK);
        outCanvas.drawBitmap(src, mtx, paint);

        jpg.reset();
        out.compress(Bitmap.CompressFormat.JPEG, Cfg.quality, jpg);
        byte[] data = jpg.toByteArray();
        if (data.length > 40000) return; // past niet in buffer van bordje (40 KB)
        if (ws.send(ByteString.of(data))) {
            inFlight++;
            lastSend = now;
            Cfg.lastKb = data.length / 1024;
            frames++;
            if (fpsStart == 0) fpsStart = now;
            if (now - fpsStart >= 1000) {
                Cfg.liveFps = frames * 1000f / (now - fpsStart);
                frames = 0; fpsStart = now;
            }
        }
    }

    // ---------- Overlay ----------
    @SuppressLint("ClickableViewAccessibility")
    private void showOverlay() {
        if (overlay != null || !android.provider.Settings.canDrawOverlays(this)) return;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackgroundColor(0xCC000000);
        box.setPadding((int) (8 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));

        overlayText = new TextView(this);
        overlayText.setTextColor(0xFF4ADE80);
        overlayText.setTextSize(12);
        overlayText.setTypeface(Typeface.MONOSPACE);
        box.addView(overlayText);

        TextView close = new TextView(this);
        close.setText("  ✕ ");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(16);
        close.setOnClickListener(v -> { Cfg.overlayWanted = false; hideOverlay(); });
        box.addView(close);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_SECURE, // overlay komt NIET mee in de stream
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (10 * d);
        lp.y = (int) (80 * d);

        overlayText.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + (int) (e.getRawX() - tx);
                        lp.y = sy + (int) (e.getRawY() - ty);
                        wm.updateViewLayout(box, lp);
                        return true;
                }
                return false;
            }
        });

        overlay = box;
        wm.addView(box, lp);
    }

    private void hideOverlay() {
        if (overlay != null) { try { wm.removeView(overlay); } catch (Exception ignored) {} overlay = null; }
    }

    private final Runnable overlayUpdate = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (overlayText != null)
                overlayText.setText(String.format("%.0f FPS  %d KB  Q%d\n%s",
                    Cfg.liveFps, Cfg.lastKb, Cfg.quality, Cfg.status));
            ui.postDelayed(this, 500);
        }
    };

    @Override
    public void onDestroy() {
        running = false;
        Cfg.status = "Gestopt";
        hideOverlay();
        if (ws != null) ws.close(1000, "stop");
        if (vd != null) vd.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}

package nl.finny.cydstreamer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private TextView rotLabel;
    private SeekBar sL, sT, sW, sH;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Cfg.load(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ESP32 CYD Streamer");
        title.setTextSize(24);
        root.addView(title);

        root.addView(label("IP-adres van het bordje"));
        EditText ip = new EditText(this);
        ip.setText(Cfg.ip);
        ip.setSingleLine();
        ip.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable e) { Cfg.ip = e.toString().trim(); Cfg.save(MainActivity.this); }
        });
        root.addView(ip);

        root.addView(slider("FPS", 5, 60, Cfg.fps, v -> Cfg.fps = v));
        root.addView(slider("Kwaliteit %", 20, 95, Cfg.quality, v -> Cfg.quality = v));

        rotLabel = label("");
        updateRot();
        root.addView(rotLabel);
        Button rot = new Button(this);
        rot.setText("Draai 90°");
        rot.setOnClickListener(v -> { Cfg.rotation = (Cfg.rotation + 90) % 360; updateRot(); Cfg.save(this); });
        root.addView(rot);

        root.addView(label("Welk deel van het scherm (in %)"));
        LinearLayout c1 = slider("Links", 0, 95, Cfg.left, v -> Cfg.left = v); sL = (SeekBar) c1.getChildAt(1); root.addView(c1);
        LinearLayout c2 = slider("Boven", 0, 95, Cfg.top, v -> Cfg.top = v); sT = (SeekBar) c2.getChildAt(1); root.addView(c2);
        LinearLayout c3 = slider("Breedte", 5, 100, Cfg.width, v -> Cfg.width = v); sW = (SeekBar) c3.getChildAt(1); root.addView(c3);
        LinearLayout c4 = slider("Hoogte", 5, 100, Cfg.height, v -> Cfg.height = v); sH = (SeekBar) c4.getChildAt(1); root.addView(c4);
        Button full = new Button(this);
        full.setText("Hele scherm");
        full.setOnClickListener(v -> { sL.setProgress(0); sT.setProgress(0); sW.setProgress(95); sH.setProgress(95); });
        root.addView(full);

        Button start = new Button(this);
        start.setText("Start streamen");
        start.setOnClickListener(v -> startStream());
        root.addView(start);

        Button overlay = new Button(this);
        overlay.setText("FPS-overlay tonen");
        overlay.setOnClickListener(v -> {
            Cfg.overlayWanted = true;
            Intent i = new Intent(this, StreamService.class).setAction(StreamService.ACTION_OVERLAY);
            if (StreamService.running) startService(i);
        });
        root.addView(overlay);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopService(new Intent(this, StreamService.class)));
        root.addView(stop);

        TextView tip = label("Tip: de overlay wordt niet naar het bordje gestuurd (hij wordt zwart gemaakt). Sleep hem buiten het gekozen deel, dan zie je er niks van.");
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void startStream() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Geef toestemming voor 'over andere apps' en druk daarna opnieuw op Start", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE && res == RESULT_OK && data != null) {
            Cfg.overlayWanted = true;
            Intent i = new Intent(this, StreamService.class)
                .putExtra("code", res).putExtra("data", data);
            startForegroundService(i);
            moveTaskToBack(true);
        }
    }

    private void updateRot() { rotLabel.setText("Draaiing: " + Cfg.rotation + "°"); }

    interface IntCb { void set(int v); }

    private LinearLayout slider(String name, int min, int max, int val, IntCb cb) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = label(name + ": " + val);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(val - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { int v = p + min; t.setText(name + ": " + v); cb.set(v); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) { Cfg.save(MainActivity.this); }
        });
        l.addView(t);
        l.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return l;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}

package nl.finny.cydstreamer;

import android.content.Context;
import android.content.SharedPreferences;

/** Live instellingen; de service leest deze bij elke frame, dus wijzigen werkt direct. */
public class Cfg {
    public static volatile String ip = "192.168.1.50";
    public static volatile int fps = 30;
    public static volatile int quality = 40;
    public static volatile int rotation = 0;      // 0, 90, 180, 270
    public static volatile int left = 0, top = 0, width = 100, height = 100; // procent van scherm

    // live statistieken voor overlay
    public static volatile String status = "Gestopt";
    public static volatile float liveFps = 0;
    public static volatile int lastKb = 0;
    public static volatile boolean overlayWanted = true;

    public static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("cfg", 0);
        ip = p.getString("ip", ip);
        fps = p.getInt("fps", fps);
        quality = p.getInt("q", quality);
        rotation = p.getInt("rot", rotation);
        left = p.getInt("l", left); top = p.getInt("t", top);
        width = p.getInt("w", width); height = p.getInt("h", height);
    }

    public static void save(Context c) {
        c.getSharedPreferences("cfg", 0).edit()
            .putString("ip", ip).putInt("fps", fps).putInt("q", quality).putInt("rot", rotation)
            .putInt("l", left).putInt("t", top).putInt("w", width).putInt("h", height).apply();
    }
}

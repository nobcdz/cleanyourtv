package com.gathin.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps {@link HomeKeyService} on the accessibility list, the way a China-market TV needs it.
 *
 * TCL and Xiaomi start their own launcher at boot and force-stop a third-party one, which also
 * drops its accessibility service. {@link GuardRevival} is bound again by the system afterwards
 * and calls {@link #ensureHomeKey}. With {@code guard_off_in_standby} the service removes itself
 * while the screen is off: on a TCL tc8000 the power key never woke the set while an accessibility
 * service of this app stayed bound.
 */
final class GuardKeep {
    static final String ACTION = "com.gathin.tvhome.GUARD";
    private static final String PREFS = "guard";
    private static final String KEY_OFF = "off_in_standby";

    private static BroadcastReceiver screenOn;
    private static boolean adbRestorationScheduled;

    private GuardKeep() {
    }

    static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        UserManager users = context.getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    static void restoreMiTv4AdbOnBoot(Context context) {
        if (adbRestorationScheduled || Build.VERSION.SDK_INT != Build.VERSION_CODES.M
                || !"MiTV4".equals(Build.MODEL) || SystemClock.elapsedRealtime() > 120_000
                || !context.getResources().getBoolean(R.bool.restore_mitv4_adb_on_boot)
                || context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                        != PackageManager.PERMISSION_GRANTED) return;
        adbRestorationScheduled = true;
        Context app = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (Settings.Global.getInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 0) {
                Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            } else if (Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 0)) {
                handler.postDelayed(() ->
                        Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 1), 1_000);
            }
        }, 15_000);
        handler.postDelayed(() -> rebindMiTv4HomeKey(app, handler), 25_000);
    }

    private static void rebindMiTv4HomeKey(Context app, Handler handler) {
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String enabled = Settings.Secure.getString(app.getContentResolver(), key);
        if (enabled == null) return;
        ComponentName home = new ComponentName(app, HomeKeyService.class);
        List<String> otherServices = new ArrayList<>();
        boolean found = false;
        for (String entry : enabled.split(":")) {
            if (home.equals(ComponentName.unflattenFromString(entry))) found = true;
            else if (!entry.isEmpty()) otherServices.add(entry);
        }
        if (!found || otherServices.isEmpty()) return;
        String withoutHome = String.join(":", otherServices);
        if (!Settings.Secure.putString(app.getContentResolver(), key, withoutHome)) return;
        handler.postDelayed(() -> {
            String current = Settings.Secure.getString(app.getContentResolver(), key);
            if (withoutHome.equals(current)) {
                Settings.Secure.putString(app.getContentResolver(), key, enabled);
            }
        }, 1_000);
    }

    static boolean offInStandby(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isUserUnlocked(context)) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFF, false);
    }

    static void setOffInStandby(Context context, boolean off) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFF, off).commit();
    }

    /** Puts HomeKeyService back on the enabled list when standby exit is opted in. Other services stay. */
    static void ensureHomeKey(Context context) {
        // The helper may already be bound. Arming only inside onServiceConnected misses an opt-in
        // that arrives after that, so the first screen-off does nothing (Philips 65PUS8102).
        HomeKeyService.applyStandbyOptIn();
        if (!offInStandby(context)) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        // Still off: binding the helper again would sit on the power key. Wait for the screen.
        if (power != null && !power.isInteractive()) {
            watchScreenOn(context.getApplicationContext());
            return;
        }
        try {
            ComponentName mine = new ComponentName(context, HomeKeyService.class);
            String list = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<String> entries = new ArrayList<>();
            boolean found = false;
            if (list != null && !list.equals("null")) {
                for (String entry : list.split(":")) {
                    if (entry.isEmpty()) continue;
                    ComponentName name = ComponentName.unflattenFromString(entry);
                    if (mine.equals(name)) found = true;
                    entries.add(entry);
                }
            }
            if (!found) {
                entries.add(mine.flattenToShortString());
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", entries));
            }
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException ignored) {
            // The settings are the system's; a refusal leaves the helper as it was.
        }
    }

    /** Drops the service from the list and puts it back on the next SCREEN_ON. The process has to stay up. */
    static void stepOut(AccessibilityService service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            watchScreenOn(service.getApplicationContext());
            service.disableSelf();
        }
    }

    private static void watchScreenOn(Context app) {
        if (screenOn != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (screenOn != this) return;
                screenOn = null;
                try {
                    app.unregisterReceiver(this);
                } catch (RuntimeException ignored) {
                }
                ensureHomeKey(app);
            }
        };
        screenOn = receiver;
        app.registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }
}

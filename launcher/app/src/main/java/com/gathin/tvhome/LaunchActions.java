package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.util.Map;
import java.util.function.BiFunction;

/** Selects a configured, package-scoped action only when it resolves; otherwise keeps the normal launch. */
final class LaunchActions {
    /** A launch_actions value "view:<uri>" opens that URI in the package instead of sending an action. */
    static final String VIEW_PREFIX = "view:";
    static final String COMPONENT_PREFIX = "component:";

    static ActivityInfo exportedActivity(PackageManager pm, Intent intent) {
        ComponentName component = intent.getComponent();
        if (component == null) return null;
        try {
            ActivityInfo activity = pm.getActivityInfo(component, 0);
            return activity.exported && activity.enabled && activity.applicationInfo.enabled
                    && activity.permission == null ? activity : null;
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private LaunchActions() {}

    static <T> T select(String pkg, Map<String, String> actions, T fallback,
                        BiFunction<String, String, T> resolver) {
        String action = actions.get(pkg);
        if (action == null) return fallback;
        T custom = resolver.apply(pkg, action);
        return custom != null ? custom : fallback;
    }

    private static final String INPUT_PREFIX = "input:";

    /** The TV input id from an "input:<id>" launch_actions value, or null when the value isn't one, including
     *  an empty id ("input:" alone), which falls back to a normal launch rather than an empty input switch. */
    static String inputId(String action) {
        if (action == null || !action.startsWith(INPUT_PREFIX)) return null;
        String id = action.substring(INPUT_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }

    /** The URI of a "view:<uri>" value, or null for a plain action. */
    static String viewUri(String value) {
        if (value == null || !value.startsWith(VIEW_PREFIX)) return null;
        String uri = value.substring(VIEW_PREFIX.length()).trim();
        return uri.isEmpty() ? null : uri;
    }

    /** The intent for a launch_actions value, limited to its package: ACTION_VIEW on the URI, or the action. */
    static Intent intent(String pkg, String value) {
        if (value.startsWith(COMPONENT_PREFIX)) {
            ComponentName component = ComponentName.unflattenFromString(value.substring(COMPONENT_PREFIX.length()));
            if (component != null && pkg.equals(component.getPackageName())) {
                return new Intent(Intent.ACTION_MAIN).setComponent(component);
            }
        }
        String uri = viewUri(value);
        Intent i = uri != null ? new Intent(Intent.ACTION_VIEW, Uri.parse(uri)) : new Intent(value);
        return i.setPackage(pkg);
    }
}

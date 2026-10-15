package com.raphele.shell;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class AppCatalog {
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Object CACHE_LOCK = new Object();
    private static List<AppInfo> cachedApps;
    private static long cachedAt;

    private final LogHub logs;

    AppCatalog(LogHub logs) {
        this.logs = logs;
    }

    List<AppInfo> installedApps() {
        synchronized (CACHE_LOCK) {
            long now = SystemClock.uptimeMillis();
            if (cachedApps != null && now - cachedAt < CACHE_TTL_MS) {
                return new ArrayList<>(cachedApps);
            }
            List<AppInfo> fresh = scan();
            cachedApps = fresh;
            cachedAt = now;
            return new ArrayList<>(fresh);
        }
    }

    private List<AppInfo> scan() {
        PackageManager pm = ShellContext.get().getPackageManager();
        List<AppInfo> result = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (pm.getLaunchIntentForPackage(app.packageName) == null) {
                continue;
            }
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(app));
            } catch (Throwable ignored) {
                label = app.packageName;
            }
            result.add(new AppInfo(label, app.packageName));
        }
        result.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.US)));
        return result;
    }

    String appsText(List<AppInfo> apps) {
        StringBuilder out = new StringBuilder();
        for (AppInfo app : apps) {
            out.append("- ").append(app.name).append(" | ").append(app.packageName).append('\n');
            if (out.length() > 12000) {
                break;
            }
        }
        return out.toString();
    }

    String localPackageGuess(String goal, List<AppInfo> apps) {
        String lower = goal.toLowerCase(Locale.US);
        for (AppInfo app : apps) {
            if (lower.contains(app.name.toLowerCase(Locale.US)) || lower.contains(app.packageName.toLowerCase(Locale.US))) {
                return app.packageName;
            }
        }
        return "";
    }

    void launchPackage(String taskId, String packageName, int displayId) throws Exception {
        launchPackage(taskId, packageName, displayId, true);
    }

    void launchPackage(String taskId, String packageName, int displayId, boolean forceStop) throws Exception {
        PackageManager pm = ShellContext.get().getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) {
            throw new IllegalStateException("no launch intent for " + packageName);
        }
        ComponentName component = intent.getComponent();
        if (component == null) {
            throw new IllegalStateException("no launch component for " + packageName);
        }
        // Single shell invocation instead of two: halves process-spawn latency.
        // `;` (not &&) preserves the old behavior: start runs even if the
        // preceding force-stop reports an error. Live commands skip the
        // force-stop so the user's own running app is not killed.
        String command = (forceStop ? "am force-stop " + shellQuote(packageName) + " ; " : "")
                + "am start --display " + displayId
                + " -f " + (forceStop ? "0x10008000" : "0x10000000")
                + " -n " + shellQuote(component.flattenToShortString());
        Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        logs.info(taskId, "am start exit=" + code + " " + output.toString().trim());
    }

    /** Shows the device launcher on the given display (live sessions must not start blank). */
    void launchHome(String taskId, int displayId) throws Exception {
        String command = "am start --display " + displayId
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME"
                + " -f 0x10000000";
        Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        logs.info(taskId, "am home exit=" + code + " " + output.toString().trim());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static final class AppInfo {
        final String name;
        final String packageName;

        AppInfo(String name, String packageName) {
            this.name = name == null || name.trim().isEmpty() ? packageName : name;
            this.packageName = packageName;
        }
    }
}

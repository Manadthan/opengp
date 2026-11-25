// SPDX-License-Identifier: GPL-3.0-or-later
package org.opengp.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The session report the player sends back: saved to Download/ through
 * MediaStore (no permission needed), with the version, the device, how the
 * game process ended, the end of last_run.log (the game's output), the
 * controller lines, OpenGP's crash and hang reports, and logcat.
 */
final class Journal {
    private Journal() {}

    /** The game process's latest exit, or null. */
    static ApplicationExitInfo lastGameExit(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 10);
        String game = c.getPackageName() + ":game";
        for (ApplicationExitInfo e : exits) {
            if (game.equals(e.getProcessName())) return e;
        }
        return null;
    }

    static boolean isCrash(ApplicationExitInfo e) {
        if (e == null) return false;
        switch (e.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return true;
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return e.getStatus() != 0;
            default:
                return false;
        }
    }

    static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    static String version(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName + " (" + p.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    /** Builds the report. */
    static String build(Context c, ApplicationExitInfo exit) {
        StringBuilder b = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        b.append("OpenGP Android – journal ").append(now).append('\n');
        b.append("Version : ").append(version(c)).append('\n');
        b.append("Appareil : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append("), Android ").append(Build.VERSION.RELEASE)
                .append(" API ").append(Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 31) b.append(", SoC ").append(Build.SOC_MODEL);
        b.append('\n');
        File image = Paths.image(c);
        b.append("Image : ").append(image.exists() ? image.length() + " octets" : "absente").append('\n');

        b.append("\n== Fin du processus du jeu ==\n");
        if (exit == null) {
            b.append("(aucune)\n");
        } else {
            b.append(reasonName(exit.getReason())).append(", statut ").append(exit.getStatus())
                    .append(", ").append(new Date(exit.getTimestamp())).append('\n');
            if (exit.getDescription() != null) b.append(exit.getDescription()).append('\n');
            b.append("PSS ").append(exit.getPss()).append(" kB, RSS ").append(exit.getRss()).append(" kB\n");
        }

        File log = new File(Paths.home(c), "last_run.log");
        b.append("\n== Fin de last_run.log ==\n").append(tail(log, 400));

        b.append("\n== Manette ==\n");
        for (String line : tail(log, 100000).split("\n")) {
            if (line.contains("[input")) b.append(line).append('\n');
        }

        for (String name : new String[] {"crash-report.txt", "hang-report.txt"}) {
            File f = new File(Paths.userDir(c), name);
            if (f.exists() && (exit == null || f.lastModified() >= exit.getTimestamp() - 600_000)) {
                b.append("\n== ").append(name).append(" ==\n").append(tail(f, 300));
            }
        }

        b.append("\n== logcat ==\n").append(logcat());
        return b.toString();
    }

    /** Saves the report to Download/; returns its name, or null. */
    static String save(Context c, String text) {
        String name = "opengp-journal-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        ContentResolver r = c.getContentResolver();
        Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return null;
        try (OutputStream out = r.openOutputStream(uri)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        return name;
    }

    static String tail(File f, int lines) {
        if (!f.exists()) return "(absent)\n";
        ArrayDeque<String> q = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                q.add(line);
                if (q.size() > lines) q.removeFirst();
            }
        } catch (Exception e) {
            return "(illisible : " + e + ")\n";
        }
        StringBuilder b = new StringBuilder();
        for (String s : q) b.append(s).append('\n');
        return b.toString();
    }

    static String logcat() {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000")
                    .redirectErrorStream(true).start();
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = r.readLine()) != null; ) b.append(line).append('\n');
            }
            p.waitFor();
            return b.toString();
        } catch (Exception e) {
            return "(logcat indisponible : " + e + ")\n";
        }
    }
}

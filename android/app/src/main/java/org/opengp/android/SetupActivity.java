// SPDX-License-Identifier: GPL-3.0-or-later
package org.opengp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The launcher: imports the player's own disc image of Mario Kart Arcade GP 2
 * (GGPE02, .iso/.gcm, or a .zip holding one) with the system file picker,
 * starts the game, and saves a journal to Download/ after each session.
 */
public class SetupActivity extends Activity {
    private static final int PICK_IMAGE = 1;
    private static final String PREFS = "opengp";

    private TextView status;
    private Button play;
    private Button importButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean importing;

    /** A reboot is not the end of a session: no journal for it (see RestartActivity). */
    static void markRestart(Context c) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("restartAt", System.currentTimeMillis()).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(16, 18, 24));

        TextView title = new TextView(this);
        title.setText("OpenGP – Mario Kart Arcade GP 2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("Version " + Journal.version(this));
        version.setTextColor(Color.LTGRAY);
        root.addView(version);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, dp(16), 0, dp(16));
        root.addView(status);

        play = button("Jouer", v -> startActivity(new Intent(this, GameActivity.class)));
        root.addView(play);
        importButton = button("Importer l'image du jeu (.iso, .gcm ou .zip)", v -> pickImage());
        root.addView(importButton);
        root.addView(button("Enregistrer un journal", v -> saveJournal(Journal.lastGameExit(this), false)));

        TextView note = new TextView(this);
        note.setText("Ton image (GGPE02) reste sur cette console. Menu en jeu : Select + Start.");
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        play.requestFocus();
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        checkLastSession();
    }

    private void refresh() {
        if (importing) return;
        File image = Paths.image(this);
        boolean ok = image.exists();
        status.setText(ok ? "Image importée (" + (image.length() >> 20) + " Mo)."
                          : "Importe d'abord ton image du jeu.");
        play.setEnabled(ok);
        if (!ok) importButton.requestFocus();
    }

    /** A journal after each session that ended since the last look; a message after a crash. */
    private void checkLastSession() {
        ApplicationExitInfo exit = Journal.lastGameExit(this);
        if (exit == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (exit.getTimestamp() <= prefs.getLong("lastExit", 0)) return;
        prefs.edit().putLong("lastExit", exit.getTimestamp()).apply();
        long restartAt = prefs.getLong("restartAt", 0);
        boolean restart = !Journal.isCrash(exit) && Math.abs(exit.getTimestamp() - restartAt) < 15_000;
        if (restart) return;
        saveJournal(exit, true);
    }

    private void saveJournal(ApplicationExitInfo exit, boolean automatic) {
        status.setText("Écriture du journal…");
        new Thread(() -> {
            String name = Journal.save(this, Journal.build(this, exit));
            ui.post(() -> {
                refresh();
                boolean crash = Journal.isCrash(exit);
                String where = name != null ? "Journal enregistré : Téléchargements/" + name
                                            : "Impossible d'enregistrer le journal.";
                if (!automatic || crash) {
                    new AlertDialog.Builder(this)
                            .setTitle(crash ? "Le jeu s'est arrêté (" + Journal.reasonName(exit.getReason()) + ")"
                                            : "Journal")
                            .setMessage(where + (crash ? "\n\nEnvoie-moi ce fichier." : ""))
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    status.setText(status.getText() + "\n" + where);
                }
            });
        }).start();
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importImage(data.getData());
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    /** Copies the image (or the first .iso/.gcm in a zip) to ISO/GGPE02.iso, checking its disc ID. */
    private void importImage(Uri uri) {
        importing = true;
        play.setEnabled(false);
        importButton.setEnabled(false);
        status.setText("Import…");
        new Thread(() -> {
            String error = null;
            File dir = Paths.isoDir(this);
            dir.mkdirs();
            File tmp = new File(dir, "import.tmp");
            try (InputStream raw = new BufferedInputStream(getContentResolver().openInputStream(uri), 1 << 20)) {
                raw.mark(8);
                byte[] magic = new byte[4];
                int n = raw.read(magic);
                raw.reset();
                InputStream in = raw;
                ZipInputStream zip = null;
                if (n == 4 && magic[0] == 'P' && magic[1] == 'K') {
                    zip = new ZipInputStream(raw);
                    ZipEntry e;
                    while ((e = zip.getNextEntry()) != null) {
                        String name = e.getName().toLowerCase();
                        if (!e.isDirectory() && (name.endsWith(".iso") || name.endsWith(".gcm"))) break;
                    }
                    if (e == null) throw new Exception("aucun fichier .iso ou .gcm dans le zip");
                    in = zip;
                }
                byte[] buf = new byte[1 << 20];
                long total = 0;
                String id = null;
                try (OutputStream out = new FileOutputStream(tmp)) {
                    for (int r; (r = in.read(buf)) > 0; ) {
                        if (id == null && total == 0 && r >= 6) id = new String(buf, 0, 6, StandardCharsets.US_ASCII);
                        out.write(buf, 0, r);
                        total += r;
                        if ((total & ((64 << 20) - 1)) < r) setStatus("Import… " + (total >> 20) + " Mo");
                    }
                }
                if (zip != null) zip.close();
                // GGPE02, or a converted dump's placeholder header (the game checks its main.dol).
                if (id == null || !(id.equals("GGPE02") || id.equals("RELSAB"))) {
                    throw new Exception("ce n'est pas Mario Kart Arcade GP 2 (ID " + id + ", attendu GGPE02)");
                }
                File image = Paths.image(this);
                image.delete();
                if (!tmp.renameTo(image)) throw new Exception("impossible de renommer le fichier importé");
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                tmp.delete();
            }
            String err = error;
            ui.post(() -> {
                importing = false;
                importButton.setEnabled(true);
                refresh();
                if (err != null) status.setText("Échec de l'import : " + err);
            });
        }).start();
    }
}

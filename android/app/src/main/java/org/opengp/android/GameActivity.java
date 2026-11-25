// SPDX-License-Identifier: GPL-3.0-or-later
package org.opengp.android;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The game: SDL's activity, which loads libmain.so (the recompiled game and
 * OpenGP's runtime) and calls its SDL_main. It runs in its own process
 * (":game"), so that a reboot of the game can start a fresh one.
 */
public class GameActivity extends SDLActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"c++_shared", "SDL3", "main"};
    }

    /**
     * --fullscreen; after a reboot, the arguments the native side wrote to
     * restart-args.txt instead (the last run's, which include --fullscreen,
     * and the reboot's).
     */
    @Override
    protected String[] getArguments() {
        List<String> args = new ArrayList<>();
        File restart = new File(Paths.home(this), "restart-args.txt");
        if (restart.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(restart))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.isEmpty()) args.add(line);
                }
            } catch (IOException ignored) {
                args.clear();
            }
            restart.delete();
        }
        if (args.isEmpty()) args.add("--fullscreen");
        return args.toArray(new String[0]);
    }

    /**
     * Called from native code when the game reboots (runtime/app/android.cpp):
     * the launcher's process starts the game again once this process has ended.
     */
    @SuppressWarnings("unused")
    public void restartGame() {
        Intent i = new Intent(this, RestartActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}

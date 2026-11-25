// SPDX-License-Identifier: GPL-3.0-or-later
package org.opengp.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Starts the game again after it rebooted, once its old process has ended. */
public class RestartActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SetupActivity.markRestart(this);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, GameActivity.class));
            finish();
        }, 700);
    }
}

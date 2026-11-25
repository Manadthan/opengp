// SPDX-License-Identifier: GPL-3.0-or-later
package org.opengp.android;

import android.content.Context;

import java.io.File;

/** OpenGP's folders, the same the native side uses (OPENGP_HOME, see runtime/app/paths.h). */
final class Paths {
    private Paths() {}

    /** SDL_GetAndroidExternalStoragePath(): the app's external files folder. */
    static File home(Context c) {
        File f = c.getExternalFilesDir(null);
        return f != null ? f : c.getFilesDir();
    }

    static File isoDir(Context c) { return new File(home(c), "ISO"); }

    static File image(Context c) { return new File(isoDir(c), "GGPE02.iso"); }

    static File userDir(Context c) { return new File(home(c), "user"); }
}

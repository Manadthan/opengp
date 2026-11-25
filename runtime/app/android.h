// SPDX-License-Identifier: GPL-3.0-or-later
//
// Android start-up, before the launcher runs: the app's files folder becomes
// OpenGP's home (OPENGP_HOME, see paths.h), stdout and stderr go to logcat
// and to last_run.log (read by the app's journal), and a reboot asks the
// app's Java side for a new game process (os/reboot.h).
#pragma once

namespace app {

#ifdef __ANDROID__
void android_init();
#endif

}  // namespace app

// SPDX-License-Identifier: GPL-3.0-or-later
//
// Android start-up (see android.h).

#include "app/android.h"

#ifdef __ANDROID__

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <SDL3/SDL.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "os/reboot.h"

namespace app {

namespace {

std::string g_home;

// stdout and stderr go to logcat and to last_run.log, line by line: the app's
// journal (android/) reads the file after the game process has ended.
void tee_stdio() {
  int fds[2];
  if (pipe(fds) != 0) return;
  std::setvbuf(stdout, nullptr, _IOLBF, 0);
  std::setvbuf(stderr, nullptr, _IONBF, 0);
  dup2(fds[1], STDOUT_FILENO);
  dup2(fds[1], STDERR_FILENO);
  close(fds[1]);
  const int in = fds[0];
  std::FILE* log = std::fopen((g_home + "/last_run.log").c_str(), "w");
  std::thread([in, log] {
    std::string line;
    char buf[4096];
    for (;;) {
      const ssize_t n = read(in, buf, sizeof(buf));
      if (n <= 0) break;
      if (log) {
        std::fwrite(buf, 1, static_cast<size_t>(n), log);
        std::fflush(log);
      }
      for (ssize_t i = 0; i < n; ++i) {
        if (buf[i] == '\n') {
          __android_log_write(ANDROID_LOG_INFO, "OpenGP", line.c_str());
          line.clear();
        } else {
          line += buf[i];
        }
      }
    }
  }).detach();
}

// A reboot (the test menu, or the game restarting itself): the arguments go to
// restart-args.txt, which the game activity reads when it starts; the app's
// Java side starts a new game process, and this one ends.
void restart(const std::vector<std::string>& args) {
  if (std::FILE* f = std::fopen((g_home + "/restart-args.txt").c_str(), "w")) {
    for (const std::string& a : args) std::fprintf(f, "%s\n", a.c_str());
    std::fclose(f);
  }
  JNIEnv* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
  jobject activity = static_cast<jobject>(SDL_GetAndroidActivity());
  if (env && activity) {
    jclass cls = env->GetObjectClass(activity);
    jmethodID method = env->GetMethodID(cls, "restartGame", "()V");
    if (method) env->CallVoidMethod(activity, method);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(activity);
  } else {
    std::fprintf(stderr, "[android] no activity to restart the game from\n");
  }
  std::fflush(stderr);
  std::_Exit(0);
}

}  // namespace

void android_init() {
  const char* storage = SDL_GetAndroidExternalStoragePath();
  g_home = storage ? storage : ".";
  setenv("OPENGP_HOME", g_home.c_str(), 1);
  setenv("HOME", g_home.c_str(), 1);
  tee_stdio();
  std::fprintf(stderr, "[android] OpenGP home %s, page size %ld\n", g_home.c_str(), sysconf(_SC_PAGESIZE));
  // The back button comes to the game as a key, instead of closing it.
  SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
  os::g_restart_handler = restart;
}

}  // namespace app

#endif

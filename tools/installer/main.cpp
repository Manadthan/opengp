// SPDX-License-Identifier: GPL-3.0-or-later
//
// OpenGP's installer for the command line (see installer.h): builds the games
// from the player's disc images, as the launcher (the package's OpenGP) does.
//
//   opengp-cli [IMAGE...] [--force] [--jobs N] [--cxx COMPILER] [--keep-build]
//              [--only ID] [--prepare]
//
// Without images it uses the images each game was built from before, then
// those in ISO/ and private/ next to it. Each game is built on its own: a
// missing image only skips that game. Games already built by this version
// are skipped unless --force is given. On Windows an image can be dropped on
// opengp-cli.exe.
//
// For other launchers (RecompLauncher's recomplauncher.json): --only ID builds
// that game alone, and an image of another game is an error; --prepare prints
// the progress as "progress <0..1> <text>" lines and never waits for a key.

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

#include "app/paths.h"
#include "installer.h"
#include "os/atomic_file.h"
#include "os/utf8.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#endif

namespace {

constexpr const char* kUsage =
    "usage: opengp-cli [IMAGE...] [--force] [--jobs N] [--cxx COMPILER]\n"
    "                      [--keep-build] [--only ID] [--prepare]\n"
    "\n"
    "Builds the games from your disc images (.iso / .gcm). Without images, it uses\n"
    "the ones the games were built from before, then those in ISO/ and private/.\n"
    "OpenGP, the launcher next to it, does the same in a window.\n"
    "\n"
    "  --only ID   build only this game (GGPE02 or GGPE01); an image of another\n"
    "              game is an error\n"
    "  --prepare   for other launchers: the progress as \"progress <0..1> <text>\"\n"
    "              lines, and no waiting for a key at the end\n";

// When opengp-cli.exe was started from Explorer (a double click, or an
// image dropped on it), its console closes when it exits: wait for a key first.
void pause_if_own_console() {
#ifdef _WIN32
  DWORD pids[2];
  if (GetConsoleProcessList(pids, 2) == 1) {
    std::printf("\nPress Enter to close.");
    std::fflush(stdout);
    std::getchar();
  }
#endif
}

int run(const std::vector<std::string>& args, bool* prepare) {
  std::vector<std::string> images;
  std::string cxx, only;
  bool force = false;
  installer::BuildOptions options;
  const std::size_t argc = args.size();
  for (std::size_t i = 1; i < argc; ++i) {
    const std::string& a = args[i];
    if (a == "--force") force = true;
    else if (a == "--keep-build") options.keep_build = true;
    else if (a == "--jobs" && i + 1 < argc) {
      char* end = nullptr;
      const long n = std::strtol(args[++i].c_str(), &end, 10);
      if (*end != '\0' || n < 1 || n > 256) {
        std::fprintf(stderr, "--jobs %s: give a number from 1 to 256\n", args[i].c_str());
        return 2;
      }
      options.jobs = static_cast<unsigned>(n);
    } else if (a == "--cxx" && i + 1 < argc) cxx = args[++i];
    else if (a == "--only" && i + 1 < argc) only = args[++i];
    else if (a == "--prepare") *prepare = true;
    else if (a == "-h" || a == "--help") {
      std::printf("%s", kUsage);
      return 0;
    } else if (!a.empty() && a[0] == '-') {
      std::fprintf(stderr, "%s", kUsage);
      return 2;
    } else {
      images.push_back(a);
    }
  }

  std::string error;
  installer::Package package;
  if (!installer::load_package(app::executable_dir(), &package, &error)) {
    std::fprintf(stderr, "%s\n", installer::not_a_package_message(error).c_str());
    return 1;
  }
  if (!only.empty()) {
    // This game alone: the package's others are left out of this run.
    const auto it = std::find_if(package.games.begin(), package.games.end(),
                                 [&](const installer::GameInfo& g) { return g.id == only; });
    if (it == package.games.end()) {
      std::fprintf(stderr, "--only %s: not one of this package's games\n", only.c_str());
      return 2;
    }
    for (const std::string& image : images) {
      const installer::GameInfo* g = installer::identify_image(package, image);
      if (g && g->id != only) {
        std::fprintf(stderr, "%s is %s (%s), not %s (%s). Pick your disc image of %s.\n", image.c_str(),
                     g->title.c_str(), g->id.c_str(), it->title.c_str(), it->id.c_str(), it->title.c_str());
        return 1;
      }
    }
    installer::GameInfo game = *it;
    package.games = {std::move(game)};
  }
  std::printf("OpenGP %s installer\n\n", package.version.c_str());
  // Everything it makes goes in the package folder: say so at once if it
  // cannot write there.
  if (!installer::check_writable(package.home, &error)) {
    std::fprintf(stderr, "%s\n", error.c_str());
    return 1;
  }

  installer::Reporter report;
  // --prepare: the whole run as one fraction. Each game's share is mostly its
  // compile step, the only one that reports its own progress.
  std::size_t game_index = 0, game_count = 1;
  double game_done = 0;
  std::string last_line;
  int last_percent = -1;
  auto overall = [&] { return (static_cast<double>(game_index) + game_done) / static_cast<double>(game_count); };
  if (*prepare) {
    report.line = [&](const std::string& s) {
      last_line = s;
      std::printf("progress %.3f %s\n", overall(), s.c_str());
      std::fflush(stdout);
    };
    report.progress = [&](double f) {
      game_done = 0.1 + 0.85 * f;
      const int percent = static_cast<int>(overall() * 100);
      if (percent == last_percent) return;
      last_percent = percent;
      std::printf("progress %.3f %s\n", overall(), last_line.c_str());
      std::fflush(stdout);
    };
  } else {
    report.line = [](const std::string& s) {
      std::printf("\r%s\n", s.c_str());
      std::fflush(stdout);
    };
    report.progress = [](double f) {
      std::printf("\r  %3d%%", static_cast<int>(f * 100 + 0.5));
      std::fflush(stdout);
    };
  }

  const auto found = installer::find_images(package, images, report);
  for (const installer::GameInfo& g : package.games) {
    bool have = false;
    for (const auto& f : found) have |= f.first == &g;
    if (!have) std::printf("%s: no disc image found; skipped.\n", g.title.c_str());
  }
  if (found.empty()) {
    if (*prepare)
      std::fprintf(stderr, "No disc image of %s found.\n", package.games.front().title.c_str());
    else
      std::printf(
          "\nPut your disc images in the ISO folder next to opengp-cli, or drop one on it (see ISO/README.txt).\n");
    return 1;
  }

  int failures = 0;
  std::string compiler;
  game_count = found.size();
  for (const auto& [game, image] : found) {
    game_done = 0;
    if (*prepare) report.line(game->title + " (" + game->id + ")");
    else std::printf("\n%s (%s)\n", game->title.c_str(), game->id.c_str());
    if (!force && installer::is_installed(package, *game)) {
      std::printf("Already built by this version (--force rebuilds it).\n");
      // The image may have moved: the game uses this one from now on.
      if (app::remembered_image(package.home / "user", game->id) != image) {
        if (app::remember_image(package.home / "user", game->id, image, &error))
          std::printf("%s will now use %s\n", game->title.c_str(), image.c_str());
        else
          std::fprintf(stderr, "%s\n", error.c_str());
      }
      ++game_index;
      continue;
    }
    if (compiler.empty() && !installer::find_compiler(package, cxx, report, &compiler, &error)) {
      std::fprintf(stderr, "%s\n", error.c_str());
      return 1;
    }
    if (!installer::install_game(package, *game, image, compiler, options, report, &error)) {
      std::fprintf(stderr, "\n%s\n", error.c_str());
      ++failures;
    }
    ++game_index;
  }
  if (*prepare) {
    if (failures == 0) {
      game_done = 0;
      report.line("Ready to play");
    }
  } else if (failures == 0) {
    std::printf("\nDone. Start a game with OpenGP, or with opengp-mkgp2 or opengp-mkgp1.\n");
  }
  return failures ? 1 : 0;
}

}  // namespace

int main(int argc, char** argv) {
  bool prepare = false;
  const int code = run(os::utf8_args(argc, argv), &prepare);
  if (!prepare) pause_if_own_console();
  return code;
}

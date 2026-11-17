## 1.0.5 (17/11/2026)

This release pulls in the latest changes from [mkgp2-decomp](https://github.com/naari3/mkgp2-decomp), the GP 2 decompilation. Thank you to naari3 and everyone working on it! Thousands more of the games' functions and data now have names, which makes hang reports easier to read and the code easier to work on. No gameplay changes.

- Updated the game's names from the latest [mkgp2-decomp](https://github.com/naari3/mkgp2-decomp) (CC0): GP 2 now has 4,296 of its 7,612 functions and 5,794 of its data objects named (up from 3,427 and 488). Most of the new data names say which part of the game the data belongs to rather than what it is. Hang reports and status backtraces show the new names
- GP 1 picks up GP 2's new names wherever the two games share code: 1,560 functions and 1,269 data objects (up from 1,021 and 77)
- 34 GP 2 functions named earlier by signature matching or by older decomp names now use the decomp's names, which are checked against the game's code (for example `GX_SetNumTevStages` is now `GXSetNumTexGens` and `dGeomDisable` is now `sndConvertMs`). GP 1 gets the same corrections for the 27 of these functions it shares
- 48 GP 2 data objects are split into their named parts, as the decomp has them. This changes the recompiled code slightly (the recompiler bounds jump tables by object size); both games have been rebuilt and played through a race
- `tools/decompnames.py`: data is paired only where its address agrees with the distance between the two releases there, which stops names landing on the wrong object where the releases lay data out differently. Addresses inside the decomp's names are converted to the US release's (names that can't be converted are skipped), the decomp's placeholder names for unnamed data are skipped, and a list of fixes corrects names the decomp has wrong (`GXGetCPUFifo`)
- `tools/decompnames.py` and `tools/crossmatch.py`: the new `--rename-differing` option replaces existing function names with the decomp's or GP 2's (names used by OpenGP's own code are kept)
- `tools/crossmatch.py` no longer renames a label that shares its address with a function instead of the function, and converts addresses inside names

## 1.0.4 (12/11/2026)

- GP 2: the brake goes back through this play's menu screens to change a class, character, kart, cup, course or round. **Brake goes back** is on by default in the Game tab and can be turned off.
- Going back skips the photo screen and stops working once the gas confirms a choice on the current screen. Menu history resets when a race starts. This feature is disabled in linked play and does not apply to GP 1.

- Added a live Performance page and an optional overlay (F7, rebindable in Controls): frame times, drops, hitches, computer CPU usage, GPU textures, sound queue and scene information. No profile recording or stack sampling is included.

### Known issues

- Graphics fixes remain outstanding: reported Yoshi Park 2 Trick House flicker, widescreen HUD/banner and screen-effect distortions, and the macOS GP 2 rainbow fade. Solid-white highlights still need confirmation against the existing texture fix.
- Brake-back is GP 2 only and disabled in linked play. Return to title and Restart race are not included.

## 1.0.3 (7/11/2026)

- Fixed the periodic freezes in linked races: the cabinets now keep in step (a cabinet runs a fraction of a percent faster or slower to follow the one that leads the race), and one that falls a frame behind catches up without freezing the race

## 1.0.2

- The command-line installer is now called `opengp-cli`, so it is no longer mistaken for the way to install OpenGP 
- The menu no longer shows your full folder path: an **Open folder** button opens the card or controls folder instead
- Plainer names and help text in the menu's settings, These now match the settings text from the launcher
- Aspect ratio and resolution choices now name their sizes (16:9, 1080p, 4K and so on)
- Gamepads without a Guide button (or where Steam takes it) open the menu with **Back** and **Start** held together
- Key bindings now show the correct key labels on non-US keyboards
- Fixed the default window size on high-DPI displays
- Removed debug spam from the console (use `--verbose` to restore pre 1.0.2 behaviour)
- Added a first-run hint for inserting a coin and opening the menu
- Improved wheel and pedal precision
- Fixed pedals reading as half-pressed until they were first moved
- Fixed pedals binding to the wrong axis half when bound before they were first moved
- The webcam no longer switches on at start-up: the game's camera check gets a picture without it
- Fixed the first photo coming out darker than intended
- Fixed audio drifting out of sync after changing the audio device
- Fixed occasional audio stutter
- Fixed a small periodic stutter on 60 and 120 Hz displays

### Known issues

- Races need most of a CPU core: a PC busy with something else (another game) can drop below
  60 fps.
- Linked races can still freeze for a moment now and then; this is being fixed for 1.0.3.
- 1.0.2 cabinets link with 1.0.1 ones, but not with 1.0.0, run 1.0.1 or newer on every cabinet.

## 1.0.1 (2/11/2026)

- A new in-game menu in the OpenGP launcher's look: a pause card, with the settings in a dialog
- GP 1: fixed objects popping in at the sides of the screen in widescreen
- Fewer dropped frames on Windows: the game's timed events now arrive on time
- Less GPU work at high internal resolutions, for the same picture
- Fixed the picture staying blank or white after the GPU briefly ran out of memory
- Fixed a possible freeze in effects that read the picture back
- Fixed crashes and freezes caused by damaged disc images or bad game data
- Linked play: less lag between computers, and cabinets are harder to disrupt by mistake
- Linked cabinets only connect over the local network (virtual LANs such as Radmin VPN and Hamachi still work)
- Fixed a brief freeze when a link connection was refused
- Linked cabinets on mismatched versions now tell you why they can't connect
- Fixed steering stuck at full lock when extra USB devices (pedals, handbrakes) were connected
- Fixed inputs a device doesn't have reading as half-pressed
- Steering wheels get fewer force-feedback updates: only forces that change are sent
- Fixed menu keys carrying over into the game
- Fixed a bug where binding certain keys broke the controls file
- Menu sliders now stay within their range
- "Restore defaults" now asks for confirmation
- Fixed Mario Card progress being lost when saving failed, and the game now tells you
- Fixed card and settings saves failing on Windows with OneDrive or antivirus running
- A bad value in the settings file no longer resets everything
- Settings and controls are now saved when you close the menu
- A second copy of a game can no longer share a user folder with one that is running
- Fixed the second-cabinet command not finding the game
- Fixed the game not finding its image when launched from another folder
- Fixed crashes and install failures when the folder path contains accented or non-Latin characters
- Fixed the game ignoring or crashing on ISO files with non-ASCII names
- Start-up errors are now shown in a message box
- Crashes now write a crash report (on Windows, the game tells you where it is)
- Linux: fixed the webcam staying on after a game restart
- Linux: the installer finds GCC 12 and newer Clang versions, so Ubuntu 22.04 works
- Improved compiler download feedback and recovery from interrupted installs
- Clearer messages for unsupported or compressed disc images
- Clearer errors for unwritable or zipped folders

### Known issues

- Races need most of a CPU core: a PC busy with something else (another game) can drop below
  60 fps.


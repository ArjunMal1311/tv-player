# TV Player

Send a link from your computer to an Android TV and watch only the video, fullscreen. You never use the TV's on-screen cursor and never see the rest of the website.

Two parts:

- **`app/`**: an Android TV app with two players.
  - **Direct video links** (`.mp4`, `.m3u8`, `.mpd`, `.mkv`, `.webm`, `.mov`) play in a built-in ExoPlayer, with a small time and progress bar.
  - **Any other web page** opens out of sight. The app finds the page's video player and shows only that player, fullscreen, with its own controls and subtitles. On the way it:
    - presses the page's Play button if the video waits for one;
    - opens embedded player frames;
    - blocks pop-up ads and redirects to other sites;
    - drops requests to common ad servers (`app/src/main/assets/adhosts.txt`).
- **`cli/tv`**: a shell script that sends a link, or a remote key, to the TV over ADB.

## Remote control

| Key | What it does |
| --- | --- |
| OK | Play / pause |
| Right / Left | Skip 10 s forward / back (hold to keep going) |
| Up | Show the player's controls |
| Down | **Button mode**: a yellow ring on the player's own buttons |
| Back | Leave button mode (it also turns off after 10 s idle); otherwise close the app |

In button mode the arrows move the ring between the player's buttons and menu items. OK taps the highlighted one: subtitles (CC), quality, server and so on. Long menus scroll as the ring moves.

## Setup

1. On the TV, turn on **Developer options → Network debugging (ADB over Wi-Fi)** and note its IP address.
2. Build and install the app (needs the Android SDK and JDK 17):

   ```sh
   ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug
   adb connect <tv-ip>:5555
   adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Put the script on your PATH and tell it the TV's address:

   ```sh
   cp cli/tv ~/.local/bin/tv
   mkdir -p ~/.config/tv-player && echo <tv-ip> > ~/.config/tv-player/tv_ip   # or: export TV_IP=<tv-ip>
   ```

## Usage

```sh
tv https://example.com/some-video-page   # open a page or a direct video link
tv                                       # open the link in the clipboard
tv ok | left | right | up | down | back  # remote keys
tv pause | vol+ | vol- | mute | on | off
```

## Debugging

The web player logs each step it takes on a page (found / pending / embed / button mode) under the tag `tvp`:

```sh
adb -s <tv-ip>:5555 logcat -s tvp
```

Debug builds also run a piece of JavaScript in the current page and log the result:

```sh
adb -s <tv-ip>:5555 shell am start -n dev.atg.tvplayer/.WebPlayerActivity --es js "'document.title'"
```

Any app on the TV can send that intent, so take it out before building a release you share.

## Limits

- Players with DRM (Netflix, Prime Video and similar) don't work; use their own TV apps.
- Some players hide the video several frames deep and pass it data from the outer page. Those don't play yet.
- A site whose own servers refuse the request fails here too.

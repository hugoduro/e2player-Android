# e2player for Android TV

Android TV player for streaming from Enigma2 STB, featuring a built-in PAT/PMT fix engine (e2proxy). Video engine: Media3 / ExoPlayer.

## Installing on Android TV

- Install the **Downloader** app (AFTVnews) on your TV, or use **Send Files to TV** to transfer the APK; alternatively, copy it to a USB drive and open it with a file manager.
- Under *Settings › Security / Apps › Unknown Sources*, allow installation for the app you are using.
- Using ADB: `adb connect TV_IP` followed by `adb install app-debug.apk`.

On first launch, the app will ask you to configure your Enigma2 box: use **Scan Network for Boxes** (automatically detects boxes running OpenWebif on your local network) or manually enter the IP address.

It will also ask for the ports and, if applicable, your OpenWebif username and password. You can change these settings later under **⚙ Settings** (the last group in the list) or by pressing the MENU key.

## Remote Control

| Key | Full Screen | Channel List |
|---|---|---|
| Up / Down, CH+ / CH- | Next / previous channel | Move / page |
| OK | Open channel list | 1st OK plays in preview; 2nd switches to full screen |
| Left / Right | Information bar | Groups ↔ channels |
| 0–9 | Channel number zapping | Channel number zapping |
| RED | Favorite | Favorite |
| GREEN | Audio track | Audio track |
| YELLOW | Subtitles | Subtitles |
| BLUE / GUIDE | Program guide | Guide for selected channel |
| INFO | Show / hide info | Return to video |
| MENU | Settings | Settings |
| BACK | Hide info; press twice to exit | Return to video |

## Project Structure

- `core/TsFixer.java` – PAT/PMT correction engine (port of e2proxy.py)
- `core/StreamProxy.java` – Local HTTP server (127.0.0.1) that passes the stream through the correction engine
- `core/OpenWebif.java` – Bouquets, EPG and picons via the OpenWebif XML API
- `core/BoxScanner.java` – Automatic detection of OpenWebif boxes on the local network
- `core/Model.java` – Groups, numbering, favorites, now/next EPG
- `MainActivity.java` – Player, remote control keys and settings
- `TvView.java` – User interface rendering (lists, info, info bar and guide)

## Screenshots

<img width="1920" height="1079" alt="channel_list_with_preview" src="https://github.com/user-attachments/assets/61124100-95d8-4821-83d9-8875954d5623" />

<img width="1920" height="1079" alt="channel_streaming" src="https://github.com/user-attachments/assets/0ef1e0be-34e3-4d27-9923-dad6441b05db" />

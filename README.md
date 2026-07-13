# Remote Camera LAN Control for Android 16

A premium remote camera shutter application built for Android 16 (API 35+) targeting local network control. The app is split into two modules: **Controller** (which initiates the photo command and receives the live image preview) and **Controlled/Receiver** (which connects to the camera hardware, snaps the photo on command, and transmits it back).

## Features

- **LAN Communication**: Seamless peer-to-peer control using local IP sockets.
- **Zero-Config NSD Discovery**: Auto-discovery of receiver devices on the local area network using Network Service Discovery (NSD).
- **Viewfinder Preview (Controller)**: The controller receives the taken photo instantly, updates a premium viewport UI, and saves it to the local gallery.
- **Persistent Local Gallery**: Both the receiver and controller save a copy of the captured photo directly to the public device gallery (`Pictures/RemoteCamera`).
- **Modern Cyberpunk UI**: Sleek dark aesthetic with glowing neon cyan/magenta styling and event logs on the receiver terminal interface.

## Project Structure

```
├── controller          # The Controller Module (APK for controlling device)
│   ├── src/main/java   # Controller network discovery and socket logic
│   └── src/main/res    # Shutter controls, device scan list, and image viewer
├── controlled          # The Controlled Module (APK for camera device)
│   ├── src/main/java   # CameraX integration, TCP server, and image dispatcher
│   └── src/main/res    # Camera preview frame and monospaced diagnostic terminal log
└── .github/workflows   # Github Actions build workflow
```

## How It Works

1. **Discovery**:
   - The **Controlled (Receiver)** app starts and initializes CameraX, grabs its local IP, and broadcasts a DNS-SD service (`_remotecamera._tcp`) via Android's `NsdManager`.
   - The **Controller** app scans the network and displays discovered receiver units as quick-connect buttons.
2. **Connection**:
   - The Controller connects to the Receiver's TCP port `8888`.
   - Once connected, both devices update their status indicators (Red -> Green).
3. **Trigger & Transport**:
   - Tapping the shutter button on the Controller sends a `TAKE_PHOTO\n` command.
   - The Receiver fires CameraX to take a high-quality picture.
   - The captured frame is written to a temporary cache file and sent over the socket in binary frame blocks:
     `[5-byte Header "PHOTO"][4-byte Integer size][N-byte JPEG data]`
   - The Controller decodes the frame, updates the preview viewport, and registers it into the system gallery.

## Building the APKs

This project is fully automated via GitHub Actions:
1. Push the project to GitHub.
2. The GitHub Action will compile both modules:
   - `assembleDebug` (installs directly onto your devices).
   - `assembleRelease` (unsigned production build).
3. Download the built APKs from the workflow run's **Artifacts** tab.

## Requirements

- Android SDK version 26 (Android 8.0) or higher.
- Fully compatible with Android 16 (API 35/36).
- Both devices must be on the same local network (Wi-Fi).

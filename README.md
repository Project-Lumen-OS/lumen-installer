# Lumen Installer

An app for XGIMI projectors that brings [Lumen OS](https://github.com/Project-Lumen-OS/lumen-os) to the projector
over the network, without a computer and without a USB cable. It reads the projector's model and looks it up in the signed list of Lumen OS builds. If a build
for that model is published, the app offers it: the stable version, or the newest beta if you choose Beta. Every file is checked against its signature on the
projector before it is used. You can try Lumen OS first: the XGIMI system and your files stay as they are.

Lumen OS always downloads over the projector's own internet connection, Wi-Fi or Ethernet. This app is the only
way to install it. The XGIMI Play 6 is the one exception: it still installs with a computer, see its
[guide](https://github.com/Project-Lumen-OS/lumen-os/blob/main/docs/install/play6/en.md).

[Русская версия](README.ru.md)

## Install the app

1. Download the Lumen Installer file that ends in `.apk1` from the [latest release](https://github.com/Project-Lumen-OS/lumen-installer/releases/latest)
   and copy it to a USB stick (FAT32 or exFAT). The stick only carries the app.
2. Plug the stick into the projector, open the file in the XGIMI file manager and confirm.
3. Connect the projector to the internet, open Lumen Installer and follow the screen.

The app updates over any earlier Lumen Installer. Which projectors and firmware versions get Lumen OS is decided by
the signed list of builds on the [index release](https://github.com/Project-Lumen-OS/lumen-installer/releases/tag/index),
made from the published [Lumen OS releases](https://github.com/Project-Lumen-OS/lumen-os/releases), not by the app.
When a build for a new projector is published, the same app offers it. A projector without a build is not touched.

## Build

`sh build.sh` builds `build/LumenInstaller.apk` with the Android SDK command line tools (platform 34, build tools
36.0.0) and a JDK 17 or newer. Official builds are signed with the project's key. `LI_ANY_KEY=1 LI_KEY_DIR=<dir>`
builds with your own key (`installerkey.pk8` + `installerkey.x509.pem`). Such a build does not update an installed
official one.

## License

Apache License 2.0, see [LICENSE](LICENSE). The Onest font (SIL Open Font License 1.1) is downloaded at build time.

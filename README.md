# Lumen OS Live

An app for XGIMI projectors that brings [Lumen OS](https://github.com/Project-Lumen-OS/lumen-os) to the projector
over the network, without a computer and without a USB cable. It reads the projector's model and looks it up in the signed list of Lumen OS builds. If a build
for that model is published, the app offers it: the stable version, or the newest beta if you choose Beta. Every file is checked against its signature on the
projector before it is used. You can try Lumen OS first in Lumen OS Live: the XGIMI system and your files stay as they are.

Lumen OS always downloads over the projector's own internet connection, Wi-Fi or Ethernet. This app is the only
way to install it. The XGIMI Play 6 is the one exception: it still installs with a computer, see its
[guide](https://github.com/Project-Lumen-OS/lumen-os/blob/main/docs/install/play6/en.md).

[Русская версия](README.ru.md)

## Supported projectors

<!-- models:installer:begin -->
| Projector | Model code | What Lumen OS Live offers |
|---|---|---|
| XGIMI Z9X | G0082 | Lumen OS Live, Install Lumen OS permanently |
| XGIMI Z9X HB | G0130, G0148 | Lumen OS Live, Install Lumen OS permanently |
| XGIMI X50 Ultra | G0111 | Lumen OS Live, Install Lumen OS permanently |
| XGIMI X50 Ultra Max | G0100 | Lumen OS Live, Install Lumen OS permanently |
| XGIMI Play 6 | G0058 | With a computer, not with Lumen OS Live |
| XGIMI AURA 3 Max | G0104 | Lumen OS Live only |
| XGIMI MIRA 4K | G0109 | Lumen OS Live only |
| XGIMI MIRA 4K Pro | G0110 | Lumen OS Live only |
| XGIMI RS 20 | G0087 | Lumen OS Live only |
| XGIMI RS 20 Plus | G0063 | Lumen OS Live only |
| XGIMI RS 20 Plus Highlight | G0079 | Lumen OS Live only |
| XGIMI RS 20 Plus New | G0086 | Lumen OS Live only |
| XGIMI RS 20 Pro | G0088 | Lumen OS Live only |
| XGIMI RS 20 Pro Master | G0077 | Lumen OS Live only |
| XGIMI RS 20 Pro Max Highlight | G0089 | Lumen OS Live only |
| XGIMI RS 20 Ultra | G0067 | Lumen OS Live only |
| XGIMI RS 20 Ultra Master | G0078 | Lumen OS Live only |
| XGIMI RS30 Pro | G0125 | Lumen OS Live only |
| XGIMI RS30 Pro Max | G0124 | Lumen OS Live only |
| XGIMI RS30 Ultra | G0114 | Lumen OS Live only |
| XGIMI RS30 Ultra Highlight | G0170 | Lumen OS Live only |
| XGIMI RS30 Ultra Max | G0115 | Lumen OS Live only |
| XGIMI RS30 Ultra Max Flagship | G0155 | Lumen OS Live only |
| XGIMI TITAN Noir | H0061 | Lumen OS Live only |
| XGIMI TITAN Noir Max | H0063 | Lumen OS Live only |
| XGIMI TITAN Noir Pro | H0062 | Lumen OS Live only |
<!-- models:end -->

## Install the app

1. Download the Lumen OS Live file that ends in `.apk1` from the [latest release](https://github.com/Project-Lumen-OS/lumen-installer/releases/latest)
   and copy it to a USB stick (FAT32 or exFAT). The stick only carries the app.
2. Plug the stick into the projector, open the file in the XGIMI file manager and confirm.
3. Connect the projector to the internet, open Lumen OS Live and follow the screen.

The app updates over any earlier Lumen OS Live. Which projectors and firmware versions get Lumen OS is decided by
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

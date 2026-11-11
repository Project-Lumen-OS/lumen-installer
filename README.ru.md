# Lumen Installer

Приложение для проекторов XGIMI, которое ставит [Lumen OS](https://github.com/Project-Lumen-OS/lumen-os) без
компьютера. Оно узнаёт модель проектора, находит её в подписанном списке поддерживаемых проекторов и скачивает
последнюю версию Lumen OS именно для этой модели. Перед использованием каждый файл проверяется по подписи прямо на
проекторе. Сначала Lumen OS можно попробовать: система XGIMI и ваши файлы остаются как есть.

[English version](README.md)

## Как поставить приложение

1. Скачайте `lumen-installer-1.0.1.apk` в [Releases](https://github.com/Project-Lumen-OS/lumen-installer/releases/latest).
2. Скопируйте его на флешку (FAT32 или exFAT), вставьте флешку в проектор и откройте файл в файловом менеджере XGIMI.
   Если менеджер не ставит файлы `.apk`, переименуйте файл в `lumen-installer-1.0.1.apk1` и откройте ещё раз.
3. Или через компьютер: включите на проекторе отладку, затем `adb connect <IP проектора>:5555` и
   `adb install -r lumen-installer-1.0.1.apk`.
4. Откройте Lumen Installer на проекторе и следуйте подсказкам на экране.

Приложение ставится поверх любой прежней версии Lumen Installer. Какие проекторы и версии прошивки поддерживаются,
решает подписанный список в [выпуске index](https://github.com/Project-Lumen-OS/lumen-installer/releases/tag/index), а
не приложение. Проектор, которого нет в списке, приложение не трогает.

## Сборка

`sh build.sh` собирает `build/LumenInstaller.apk` инструментами Android SDK (platform 34, build tools 36.0.0) и JDK 17
или новее. Официальные сборки подписаны ключом проекта. `LI_ANY_KEY=1 LI_KEY_DIR=<папка>` собирает со своим ключом
(`installerkey.pk8` и `installerkey.x509.pem`). Такая сборка не обновит уже установленную официальную.

## Лицензия

Apache License 2.0, см. [LICENSE](LICENSE). Шрифт Onest (SIL Open Font License 1.1) скачивается при сборке.

# Lumen Installer

Приложение для проекторов XGIMI, которое ставит [Lumen OS](https://github.com/Project-Lumen-OS/lumen-os) по сети, без
компьютера и без кабеля USB. Оно узнаёт модель проектора и ищет её в подписанном списке сборок Lumen OS. Если сборка для этой модели
опубликована, приложение её предлагает: стабильную версию или самую новую бету, если выбрать «Бета». Перед использованием каждый файл проверяется по подписи прямо на
проекторе. Сначала Lumen OS можно попробовать в Lumen OS Live: система XGIMI и ваши файлы остаются как есть.

Lumen OS всегда скачивается через интернет самого проектора, по Wi-Fi или Ethernet. Это приложение и есть единственный
способ её поставить. Исключение одно, XGIMI Play 6: он пока ставится с компьютера, см. его
[инструкцию](https://github.com/Project-Lumen-OS/lumen-os/blob/main/docs/install/play6/ru.md).

[English version](README.md)

## Какие проекторы подходят

<!-- models:installer:begin -->
| Проектор | Код модели | Что предлагает Lumen Installer |
|---|---|---|
| XGIMI Z9X | G0082 | Lumen OS Live, Установить Lumen OS насовсем |
| XGIMI Z9X HB | G0130, G0148 | Lumen OS Live, Установить Lumen OS насовсем |
| XGIMI X50 Ultra | G0111 | Lumen OS Live, Установить Lumen OS насовсем |
| XGIMI X50 Ultra Max | G0100 | Lumen OS Live, Установить Lumen OS насовсем |
| XGIMI Play 6 | G0058 | С компьютера, не через Lumen Installer |
| XGIMI AURA 3 Max | G0104 | только Lumen OS Live |
| XGIMI MIRA 4K | G0109 | только Lumen OS Live |
| XGIMI MIRA 4K Pro | G0110 | только Lumen OS Live |
| XGIMI RS30 Pro | G0125 | только Lumen OS Live |
| XGIMI RS30 Pro Max | G0124 | только Lumen OS Live |
| XGIMI RS30 Ultra | G0114 | только Lumen OS Live |
| XGIMI RS30 Ultra | G0170 | только Lumen OS Live |
| XGIMI RS30 Ultra Max | G0115 | только Lumen OS Live |
| XGIMI RS30 Ultra Max | G0155 | только Lumen OS Live |
| XGIMI TITAN Noir | H0061 | только Lumen OS Live |
| XGIMI TITAN Noir Max | H0063 | только Lumen OS Live |
| XGIMI TITAN Noir Pro | H0062 | только Lumen OS Live |
<!-- models:end -->

## Как поставить приложение

1. Скачайте из [последнего релиза](https://github.com/Project-Lumen-OS/lumen-installer/releases/latest) файл Lumen Installer с окончанием `.apk1`
   и скопируйте его на флешку (FAT32 или exFAT). Флешка нужна только для приложения.
2. Вставьте флешку в проектор, откройте файл в файловом менеджере XGIMI и подтвердите установку.
3. Подключите проектор к интернету, откройте Lumen Installer и следуйте подсказкам на экране.

Приложение ставится поверх любой прежней версии Lumen Installer. Каким проекторам и версиям прошивки доступна Lumen
OS, решает подписанный список сборок в [выпуске index](https://github.com/Project-Lumen-OS/lumen-installer/releases/tag/index),
собранный из опубликованных [выпусков Lumen OS](https://github.com/Project-Lumen-OS/lumen-os/releases), а не приложение.
Когда выходит сборка для нового проектора, то же приложение её предлагает. Проектор без сборки приложение не трогает.

## Сборка

`sh build.sh` собирает `build/LumenInstaller.apk` инструментами Android SDK (platform 34, build tools 36.0.0) и JDK 17
или новее. Официальные сборки подписаны ключом проекта. `LI_ANY_KEY=1 LI_KEY_DIR=<папка>` собирает со своим ключом
(`installerkey.pk8` и `installerkey.x509.pem`). Такая сборка не обновит уже установленную официальную.

## Лицензия

Apache License 2.0, см. [LICENSE](LICENSE). Шрифт Onest (SIL Open Font License 1.1) скачивается при сборке.

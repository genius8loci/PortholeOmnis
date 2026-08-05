<div align="center">

<img src="src/main/resources/assets/portholeomnis/icon.png" width="128" alt="PortholeOmnis">

# PortholeOmnis

Клиентский мод для Minecraft 1.20.1 (Fabric), который пускает друзей в LAN-мир
через интернет — транспортом служит [Porthole](https://store.steampowered.com/app/4963920/Porthole__Local_Port_Sharing/),
бесплатное приложение в Steam, проксирующее локальные порты через сеть Steam.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-lightgrey)](https://fabricmc.net)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**Русский** · [English](README.en.md)

</div>

## Что делает

**Хост.** Открываете мир в LAN как обычно — мод сам поднимает туннель и пишет
в чат share-код. По клику код копируется в буфер. На экране «Открыть для сети»
появляются три тумблера: онлайн-режим, Porthole и релей.

**Гость.** На экране «Сетевая игра» появляется кнопка **Porthole**. Вводите код —
мод сам запускает туннель, подбирает свободный локальный порт и заходит в игру.
Ни консоли, ни ручного Direct Connect. Тумблер релея на этом экране свой,
независимый от хостового.

Туннель сворачивается сам: у хоста — когда закрывается мир, у гостя — при выходе
с сервера.

## Требования

- Minecraft **1.20.1** + Fabric Loader ≥ 0.15.0
- [Fabric API](https://modrinth.com/mod/fabric-api)
- **[Porthole](https://store.steampowered.com/app/4963920/Porthole__Local_Port_Sharing/)** — бесплатно в Steam, нужен **обеим сторонам**
- Запущенный клиент Steam с выполненным входом
- Windows или Linux/SteamOS: под macOS Porthole не выпускается, и мод честно
  говорит об этом вместо «не найден»

Мод проверяет и наличие Porthole, и запущенный Steam, и подсказывает, чего не хватает.
Проверка повторяется прямо на экране подключения, так что поставить Porthole
и запустить Steam можно не закрывая его.
Если бинарник лежит вне библиотеки Steam, путь к нему можно задать переменной
окружения `PORTHOLE_EXE`.

## Установка

1. Установите Fabric Loader для 1.20.1 и положите Fabric API в `mods/`.
2. Скачайте jar из [Releases](https://github.com/genius8loci/PortholeOmnis/releases) и положите туда же.
3. Установите Porthole в Steam и запустите Steam.

Мод клиентский: на сервере он не нужен и работать там не будет.

## Настройки на экране «Открыть для сети»

| Тумблер | По умолчанию | Что делает |
|---|---|---|
| Онлайн-режим | вкл | Выключите, чтобы пускать друзей без проверки лицензии Mojang |
| Porthole | вкл | Выключите, чтобы открыть мир только в локальной сети, без туннеля |
| Релей | вкл | Пускает трафик через релеи Valve и скрывает ваш IP. Выключите ради задержки |

У гостя на экране подключения есть свой тумблер релея — он не связан с хостовым.

Мир всегда публикуется гостям как порт 25565, какой бы порт ни выдал Minecraft, —
у гостя мод сам замапит его на свободный локальный.

## Сборка

Нужен JDK 17 или новее (проверялось на Temurin 21).

```bash
./gradlew build
```

Готовый jar окажется в `build/libs/`. Тесты — `./gradlew test`; покрыто то, что
живёт без Minecraft: разбор `libraryfolders.vdf`, выделение имени файла из пути
и разбор событий `porthole expose --json`.

Версия задаётся `mod_version` в `gradle.properties`. Релиз собирается по тегу
(`v1.0.2` или `1.0.2`), и workflow сверяет тег с `mod_version` — если забыть
поднять версию в файле, сборка упадёт, а не выложит jar со старым номером.

## Лицензия

[MIT](LICENSE) © genius8loci

Porthole — продукт SeStudio, к этому проекту отношения не имеет и распространяется
отдельно через Steam.

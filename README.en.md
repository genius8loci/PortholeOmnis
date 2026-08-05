<div align="center">

<img src="src/main/resources/assets/portholeomnis/icon.png" width="128" alt="PortholeOmnis">

# PortholeOmnis

A client-side Minecraft 1.20.1 (Fabric) mod that lets friends join your LAN world
over the internet, using [Porthole](https://store.steampowered.com/app/4963920/Porthole__Local_Port_Sharing/)
as the transport — a free Steam app that proxies local ports through Steam's network.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-lightgrey)](https://fabricmc.net)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[Русский](README.md) · **English**

</div>

## What it does

**Host.** Open your world to LAN as usual — the mod brings up the tunnel by itself
and posts the share code to chat. Click the code to copy it. Three toggles appear
on the "Open to LAN" screen: online mode, Porthole, and relay.

**Guest.** A **Porthole** button appears on the Multiplayer screen. Enter the code
and the mod starts the tunnel, picks a free local port, and joins the game — no
console, no manual Direct Connect. The relay toggle on that screen is the guest's
own, independent of the host's.

The tunnel shuts down on its own: on the host when the world closes, on the guest
when leaving the server.

## Requirements

- Minecraft **1.20.1** + Fabric Loader ≥ 0.15.0
- [Fabric API](https://modrinth.com/mod/fabric-api)
- **[Porthole](https://store.steampowered.com/app/4963920/Porthole__Local_Port_Sharing/)** — free on Steam, needed on **both sides**
- A running, signed-in Steam client
- Windows or Linux/SteamOS: Porthole has no macOS build, and the mod says so
  outright instead of reporting "not found"

The mod checks for both Porthole and a running Steam, and tells you what is
missing. The check repeats on the join screen itself, so you can install Porthole
and start Steam without closing it. If the binary lives outside a Steam library,
point at it with the `PORTHOLE_EXE` environment variable.

## Installation

1. Install Fabric Loader for 1.20.1 and drop Fabric API into `mods/`.
2. Download the jar from [Releases](https://github.com/genius8loci/PortholeOmnis/releases) and put it there too.
3. Install Porthole on Steam and start Steam.

This is a client mod: it is not needed on a server and will not work there.

## Toggles on the "Open to LAN" screen

| Toggle | Default | What it does |
|---|---|---|
| Online Mode | on | Turn off to let friends in without a Mojang account check |
| Porthole | on | Turn off to open the world on the local network only, with no tunnel |
| Relay | on | Routes traffic through Valve relays and hides your IP. Turn off to trade privacy for latency |

The guest's join screen has its own relay toggle, unrelated to the host's.

The world is always advertised to guests as port 25565 no matter which port
Minecraft handed out — the guest side remaps it to a free local one.

## Building

Needs JDK 17 or newer (tested on Temurin 21).

```bash
./gradlew build
```

The jar lands in `build/libs/`. Tests are `./gradlew test`; they cover what runs
without Minecraft: `libraryfolders.vdf` parsing, extracting a file name from a
path, and parsing `porthole expose --json` events.

The version comes from `mod_version` in `gradle.properties`. Releases are built
from a tag (`v1.0.2` or `1.0.2`), and the workflow checks the tag against
`mod_version` — forgetting to bump the file fails the build instead of shipping a
jar with the old number.

## License

[MIT](LICENSE) © genius8loci

Porthole is a product of SeStudio, unaffiliated with this project and distributed
separately through Steam.

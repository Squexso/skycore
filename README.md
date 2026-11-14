<div align="center">

<img src="src/main/resources/assets/skycore/icon.png" alt="SKYCORE" width="96" />

# SKYCORE

**Your Hypixel SkyBlock progression, tracked, explained and easy to read.**

A client-side Fabric mod that follows your levels, shows what is next, plans the way there
and plays your music while you grind.

![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2-62b47a?style=flat-square)
![Fabric](https://img.shields.io/badge/Mod%20loader-Fabric-dbd0b4?style=flat-square)
![Side](https://img.shields.io/badge/Side-Client-5b8def?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

</div>

---

## ✨ What is SKYCORE?

One clean window next to your game. It reads what Hypixel SkyBlock already shows you (chat, sidebar, tab list and
menus you open yourself) and turns it into levels, progress, history, goals and estimates.

- **Passive.** It never sends commands, never clicks and never opens menus for you.
- **No API key, no account.** Everything stays on your computer.

## 🎯 Features

### 📈 Progress and planning

| | |
|---|---|
| 📊 **Overview** | Your profile at a glance: key levels, recent progress, what changed today. |
| 🧭 **Progression** | Skills, Catacombs and classes, Slayer, HOTM, collections and bestiary, each with progress to the next level. |
| 🔮 **Estimates** | How many runs or quests are left for a target level, with a confidence rating and an honest range instead of a made-up number. |
| 🏁 **Goals** | Set a target, watch it fill up, get notified when you reach it. Time-to-goal comes as a from-to range. |
| ⚡ **Pace and Peak times** | Compares your XP per hour with your 7-day average and shows when you are usually most productive. AFK time is left out. |
| 🕒 **Sessions** | What you gained in this session and over time, with charts. |
| 🏰 **Dungeons and Slayer** | Runs and quests are sorted by floor and tier from real evidence. Uncertain ones are never counted, and you can correct them. |
| 🗳️ **Mayors and Reputation** | Keeps track of the current mayor and your faction reputation. |

### 🧩 HUD Studio

Drag, resize and style your own HUD boxes. In **Auto** mode a box only appears while you do that activity,
so the screen stays clean.

Boxes: Catacombs · Skill · Slayer · Session · Goals · Reputation · HOTM · **Now playing** · FPS · TPS · Ping · RAM · Coordinates

### 🎵 Music

A full music player inside the SKYCORE window.

- **Search** songs, artists or genres. Also works with Russian names, even typed in Latin letters.
- **My files:** put MP3, OGG, FLAC or WAV files into `config/skycore/music/local/` and they show up in search, favorites and playlists, fully offline. Your own songs, no account needed.
- **Favorites and playlists**, saved on every change in `config/skycore/music/playlists.json`.
- **Autoplay** picks similar songs from the genre and your taste, with the next song prepared for gapless playback.
- **Player bar** with play/pause, skip, seek and volume. Sound goes through the game's own audio device and master volume.
- **Now playing box** on the HUD with title, artist and time.
- **Hotkeys** for pause, next, previous and volume (`\` `]` `[` `=` `-`). Change them in *Settings → Shortcuts*.
- Streams come from [Audius](https://audius.co), which needs no key. The catalog is smaller than the big streaming services, so some songs are missing.

### 🔔 Quality of life

| | |
|---|---|
| 🔔 **Notifications** | Level-ups, nearly-there warnings and goal completions as quiet toasts. |
| 💬 **Milestone sharing** | Prepares ready-to-send messages (for example "Catacombs 40 reached") for Party, All chat or the clipboard. You decide what is sent. |
| 💾 **Backups and export** | Automatic backups of your data and a statistics export. |
| 🎨 **Appearance** | Themes, accent colors, window opacity and scale. |
| 🌍 **English and German** | Fully translated. Another language is one JSON file. |

## 🔍 Honest data

Every number carries a small marker that says where it comes from:

**Verified** (read from the game) · **Event-tracked** (verified base plus recorded events) · **Estimate** · **Manual** (entered by you) · **Unavailable**

If a verified value disagrees with what SKYCORE expected, the verified value wins. Nothing is invented, and gaps are shown as gaps.

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **26.1.2** or **26.2**.
2. Install the [Fabric API](https://modrinth.com/mod/fabric-api).
3. Put the SKYCORE `.jar` into your `mods` folder. Use the file that matches your Minecraft version.
4. Start the game and join Hypixel SkyBlock.

Open SKYCORE with **Right Shift** (rebindable) or `/skycore`. The HUD editor opens with `/skycore hud`.

> The `main` branch targets Minecraft 26.1.2, the `mc/26.2` branch targets 26.2.
> The jar is about 32 MB because it bundles the audio library for the Music tab.

## 🛠️ Building from source

Requires a Java 25 toolchain (Gradle can provision one).

```bash
./gradlew build      # the mod ends up in build/libs/
./gradlew test       # unit tests
./gradlew runClient  # start a dev client
```

## 🔒 Privacy and fair play

- Everything stays in `config/skycore/`. No telemetry.
- The only network traffic: the Music tab contacts `api.audius.co` when you search or play, and the optional ping readout sends the same tiny vanilla ping request as F3+3 every 5 seconds while it is on.
- SKYCORE only reads what the game shows you and never acts on your behalf.

> ⚠️ Mods are used at your own risk. Always check the rules of the server you play on.
> SKYCORE is built to stay passive, but it has not been tested against every situation on the live server.

## 📄 License

[MIT License](LICENSE). SKYCORE is an independent project and is not affiliated with Hypixel, Mojang or Audius.

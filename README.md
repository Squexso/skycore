<div align="center">

<img src="src/main/resources/assets/skycore/icon.png" alt="SKYCORE" width="96" />

# SKYCORE

**Your Hypixel SkyBlock progression, tracked, explained and easy to read.**

A client-side Fabric mod for Minecraft **26.1.2**. It follows your achievements, shows what is next
and helps you plan what to do to get there.

![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-62b47a?style=flat-square)
![Fabric](https://img.shields.io/badge/Mod%20loader-Fabric-dbd0b4?style=flat-square)
![Side](https://img.shields.io/badge/Side-Client-5b8def?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

</div>

---

## ✨ What is SKYCORE?

SKYCORE is a small overlay that sits next to your game. It reads what Hypixel SkyBlock already shows you
(chat, the sidebar, the tab list and menus you open yourself) and turns it into something you can actually
use: levels, progress, history, goals and estimates in one clean window.

It is **passive**. It never sends commands, never clicks for you and never opens menus.
It also needs **no API key** and talks to no external service.

## 🎯 Features

| | |
|---|---|
| 📊 **Overview** | Your profile at a glance: key levels, recent progress and what changed today. |
| 🧭 **Progression** | Skills, Catacombs and classes, Slayer, HOTM, collections and bestiary, each with progress to the next level. |
| 🔮 **Estimates** | How many runs or quests are left to reach a target level, with a confidence rating and an honest range instead of a made-up number. |
| 🏰 **Dungeons & Slayer** | Runs and quests are classified by floor and tier from real evidence. Uncertain ones are never counted, and you can correct them. |
| 🕒 **Sessions** | What you gained in this session and over time, with charts. |
| 🏁 **Goals** | Set a target, watch the progress and get notified when you reach it. |
| 🧩 **HUD Studio** | Drag, resize and style your own HUD boxes. In **Auto** mode a box only shows while you are doing that activity (a dungeon run, a slayer quest, a skill), so your screen stays clean. |
| 🔔 **Notifications** | Level-ups, nearly-there warnings and goal completions as quiet toasts. |
| 💬 **Milestone sharing** | Prepares ready-to-send messages for milestones (for example "Catacombs 40 reached"), routed to Party, All chat or the clipboard. You decide what to send; SKYCORE never sends anything for you. |
| 🎨 **Appearance** | Themes, accent colors, window opacity and scale. |
| 🌍 **English and German** | Full translations. Other languages can be added with a single JSON file. |

## 🔍 Honest data

Every number in SKYCORE carries a small status marker, so you always know where it comes from:

- **Verified**: read directly from the game
- **Event-tracked**: a verified base plus events recorded since
- **Estimate**: approximate, for example from a rounded percentage
- **Manual**: entered by you
- **Unavailable**: not known, and shown as such

When a verified value disagrees with what SKYCORE expected, the verified value wins and the difference is logged.
Nothing is invented, and gaps are shown as gaps.

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **26.1.2**.
2. Install the [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download the SKYCORE `.jar` and put it into your `mods` folder.
4. Start the game and join Hypixel SkyBlock.

Open SKYCORE with **Right Shift** (rebindable in *Options → Controls*) or with the command `/skycore`.
The HUD editor opens with `/skycore hud`.

## 🛠️ Building from source

Requires a Java 25 toolchain (Gradle can provision one automatically).

```bash
./gradlew build      # the mod ends up in build/libs/
./gradlew test       # unit tests
./gradlew runClient  # start a dev client
```

## 🔒 Privacy and fair play

- Everything stays on your computer, in `config/skycore/`.
- No accounts, no telemetry, no API key.
- SKYCORE only reads what the game shows you and never acts on your behalf.

> ⚠️ Mods are used at your own risk. Always check the rules of the server you play on.
> SKYCORE has been built to stay passive, but it has not been tested against every situation on the live server.

## 📄 License

Released under the [MIT License](LICENSE).

SKYCORE is an independent project and is not affiliated with Hypixel or Mojang.

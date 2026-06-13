# GeminiBaritone

A Fabric client-side mod for Minecraft 1.21.11 that lets other players control Baritone through in-game chat — just by saying "hey gemini" followed by whatever you want it to do.

If you want the full experience, pair it with [U.R.A.I by L33T](https://modrinth.com/mod/urai). U.R.A.I makes your client respond to chat messages using the Google Gemini AI API, so when combined with this mod it genuinely looks like an AI is reading the chat and acting on it in real time.

---

## Dependencies

| Dependency | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | Latest |
| Fabric API | Latest for 1.21.11 |
| Baritone for Fabric | `baritone-meteor-1.21.11.jar` |

### Getting Baritone for 1.21.11

The official Baritone releases don't always keep up with newer MC versions, but the Meteor Client team maintains their own build. You can grab it from the archived download page here:

**https://web.archive.org/web/20260406204949/https://www.meteorclient.com/api/downloadBaritone**

Just drop the jar into your `.minecraft/mods/` folder like any other mod.

---

## Is it safe?

Yes. The full source code for this mod is right here in this repo — both files, every line. Nothing is hidden, there's no telemetry, no external connections, nothing phoning home. If you're ever unsure about a mod, being able to read the source is the only real answer, and you can with this one.

---

## What it does

Normally Baritone only listens to you. This mod opens it up so that other players on the server can send commands to it through chat. Anyone can type "hey gemini walk 10" and your client will walk forward 10 blocks. It's a simple concept but it opens up a lot of fun possibilities, especially when combined with U.R.A.I where it genuinely looks like an AI player is responding to people and acting on their requests.

---

## Commands

Every command starts with:

```
hey gemini <command>
```

The parser is forgiving — typos and synonyms work fine. "fallow", "kil", "mne" will all be understood correctly.

| Command | Example | What happens |
|---|---|---|
| `walk <blocks>` | `hey gemini walk 10` | Walks forward that many blocks |
| `follow <player>` | `hey gemini follow Steve` | Follows a player using Baritone |
| `kill <player>` | `hey gemini kill Steve` | Follows and attacks a player (see warning below) |
| `mine <block>` | `hey gemini mine diamond ore` | Mines any matching block types it can find |
| `goto <x y z>` | `hey gemini go to 100 64 200` | Pathfinds to those coordinates |
| `tower <amount>` | `hey gemini tower up 15` | Pillars straight up that many blocks |
| `tpa <player>` | `hey gemini tpa Steve` | Sends /tpa to that player |
| `tpaccept` | `hey gemini accept tp` | Accepts an incoming teleport request |
| `stop` | `hey gemini stop` | Cancels everything and stops moving |

---

## Warning

The kill command has aim assist and a trigger bot built in. It will automatically aim at the target player and attack them whenever the cooldown is ready and they're within range. This is there to make it actually functional when other players ask it to fight someone.

This is meant for messing around with friends — not for griefing people or gaining an unfair advantage in competitive play. Use it on servers where that kind of thing is fine and don't be a jerk about it.

---

## How to build it

You need JDK 21 and Git.

```bash
git clone https://github.com/your-username/GeminiBaritone.git
cd GeminiBaritone

# drop baritone-meteor-1.21.11.jar into the repo root first

./gradlew build
```

The built jar ends up in `build/libs/`. Drop it into `.minecraft/mods/` alongside Baritone and Fabric API and you're good to go.

---

## Making it better

The codebase is just two files and pretty easy to follow. Some things worth adding if you want to extend it:

- **Player whitelisting** — right now anyone on the server can send commands. Adding a whitelist would let you restrict it to friends only
- **Command cooldowns** — stops people from spamming it
- **More Baritone commands** — `#elytra`, `#explore`, `#build` and others all work the same way, just add them to the parser
- **Chat feedback** — send a message back to whoever issued the command so they know it worked
- **More verb aliases** — the VERB_ALIASES array in the parser makes adding synonyms trivial

If you add something useful, feel free to open a PR.

---

## License

Do whatever you want with it. Credit is appreciated but not required.

![](https://lol.scarsz.me/AiKvTS/Logo-filled-stroke.png "DiscordSRV Logo")

[![](https://discord.com/api/guilds/135634590575493120/widget.png)](https://discordsrv.com/discord "Discord")
[![](https://img.shields.io/github/release/DiscordSRV/DiscordSRV.svg)](https://github.com/DiscordSRV/DiscordSRV/releases/latest "Latest release")
[![](https://img.shields.io/github/downloads/DiscordSRV/DiscordSRV/total.svg)](https://github.com/DiscordSRV/DiscordSRV/releases/latest "GitHub downloads")
![](https://img.shields.io/github/languages/code-size/DiscordSRV/DiscordSRV.svg "Code size in bytes")
[![](https://img.shields.io/github/contributors/DiscordSRV/DiscordSRV.svg)](https://github.com/DiscordSRV/DiscordSRV/graphs/contributors "GitHub contributors")
[![](https://img.shields.io/github/license/DiscordSRV/DiscordSRV.svg)](https://github.com/DiscordSRV/DiscordSRV/blob/master/LICENSE "License")

A Minecraft to Discord and back link plugin

[![](https://bstats.org/signatures/bukkit/DiscordSRV.svg)](https://bstats.org/plugin/bukkit/DiscordSRV/387 "DiscordSRV on bStats")

---

## MinehutFilter integration (this fork)

This fork adds a soft integration with the [MinehutFilter](https://minehut.com/) plugin (the
free chat filter Minehut installs on its servers) so that messages MinehutFilter blocks in-game
are **also not relayed to Discord**.

### The problem

MinehutFilter blocks a filtered/blacklisted message by cancelling the chat event:

- On Paper it registers `PaperChatListener`, which cancels
  `io.papermc.paper.event.player.AsyncChatEvent`.
- On Spigot it registers `SpigotChatListener`, which cancels the legacy
  `org.bukkit.event.player.AsyncPlayerChatEvent`.

DiscordSRV only relays chat after checking `event.isCancelled()` (`RespectChatPlugins`). That
works when both plugins listen to the same event, but by default DiscordSRV uses the legacy event
(`UseModernPaperChatEvent: false`). On Paper, MinehutFilter cancels the *modern* event, so
DiscordSRV never sees the cancellation and forwards the raw, unfiltered message to Discord, even
though the player only sees the "content blocked" notice in-game.

There is a second gap: MinehutFilter also refuses to show a message when the filter errors or times
out, which DiscordSRV could never detect through event cancellation.

### The fix

Before DiscordSRV delivers a game chat message to Discord, it now asks MinehutFilter's own filter
API whether the message is blocked and aborts the delivery if it is.

MinehutFilter exposes everything needed publicly:

| Member | Purpose |
| --- | --- |
| `MinehutFilter#getInstance()` | plugin singleton |
| `MinehutFilter#filter(Player, String)` → `CompletableFuture<FilterResponse>` | runs the filter (no player notification, unlike `allows(...)`) |
| `FilterResponse#isBlocked()` | whether the content is blocked |
| `FilterResponse#isError()` | whether the filter could not be reached |

The integration is implemented **reflectively**
(`src/main/java/github/scarsz/discordsrv/util/MinehutFilterUtil.java`), so DiscordSRV still
compiles and runs with no changes if MinehutFilter is not installed, and no build dependency on the
MinehutFilter jar is required.

### Behaviour details

- The check is performed in `DiscordSRV#processChatMessage`, so it covers every Minecraft → Discord
  chat path: the legacy `AsyncPlayerChatEvent` listener, the modern Paper listener, and the
  chat-plugin hooks.
- MinehutFilter caches filter responses for 5 minutes and already filtered the same message during
  the chat event, so this is normally a **cache hit** and adds no extra HTTP request. On a cache miss
  it performs a real filter call (MinehutFilter itself times out after 3 seconds).
- The lookup is **non-blocking on the server's main thread**: on the primary thread only an already
  completed result is used (`getNow`), otherwise the calling (async) thread waits up to 3 seconds.
- **Fails open**: if MinehutFilter errors, times out, or is unreachable, chat keeps flowing to
  Discord (`isError()`/null response are not treated as blocked). MinehutFilter itself fails closed
  in-game; this fork intentionally does not silence Discord on transient filter outages.
- Only Minecraft → Discord is filtered. Discord → Minecraft is unaffected.

### Configuration

A single option was added to `config.yml`, under the **Plugin hooks** section (default `true`):

```yaml
# MinehutFilterRespectBlockedMessages: whether messages blocked by the MinehutFilter plugin (https://minehut.com/) should also be prevented from being sent to Discord
MinehutFilterRespectBlockedMessages: true
```

Additional controls:

- Add `MinehutFilter` to `DisabledPluginHooks` to disable the integration.
- For debugging, run `/discord debug minecrafttodiscord`; blocked messages log
  `"... but it was not delivered to Discord because MinehutFilter blocked it"`.

### Files changed

- `src/main/java/github/scarsz/discordsrv/util/MinehutFilterUtil.java` (new) — reflective MinehutFilter integration.
- `src/main/java/github/scarsz/discordsrv/DiscordSRV.java` — skip delivery in `processChatMessage` when MinehutFilter blocks the message.
- `src/main/resources/plugin.yml` — added `MinehutFilter` to `softdepend` (load ordering).
- `src/main/resources/config/en.yml` — added the `MinehutFilterRespectBlockedMessages` option.

### Building

The Paper API pinned by this repo (`paperVersion` in `gradle.properties`) is compiled for Java 25,
so a JDK 25 is required to build (`./gradlew shadowJar`). The shaded plugin jar is written to
`build/libs/`.

## Thanks to:
<table>
    <tr>
        <td align="center" width="50%">
            <a href="https://www.jetbrains.com/idea/"><img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg" alt="Jetbrains" width="400px"></a>
            <p><strong>IntelliJ IDEA - The Leading Java and Kotlin IDE</strong></p>
        </td>
        <td align="center" width="50%">
            <a href="http://www.ej-technologies.com/products/jprofiler/overview.html"><img src="https://www.ej-technologies.com/images/product_banners/jprofiler_large.png" alt="JProfiler" width="150px"></img></a>
            <p><strong>JProfiler's intuitive UI helps you resolve performance bottlenecks, pin down memory leaks and understand threading issues.</strong></p>
        </td>
    </tr>
</table>

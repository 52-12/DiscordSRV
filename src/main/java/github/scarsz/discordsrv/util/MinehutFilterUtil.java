/*
 * DiscordSRV - https://github.com/DiscordSRV/DiscordSRV
 *
 * Copyright (C) 2016 - 2024 Austin "Scarsz" Shapiro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */

package github.scarsz.discordsrv.util;

import github.scarsz.discordsrv.Debug;
import github.scarsz.discordsrv.DiscordSRV;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Soft integration with the MinehutFilter plugin (https://minehut.com/).
 *
 * <p>MinehutFilter blocks filtered chat in-game (cancelling {@code AsyncChatEvent} on Paper, or
 * {@code AsyncPlayerChatEvent} on Spigot) but DiscordSRV does not always see that cancellation,
 * for example when it is (by default) listening for the legacy Spigot chat event while
 * MinehutFilter cancels Paper's modern event. As a result filtered messages would still be
 * relayed to Discord.</p>
 *
 * <p>This asks MinehutFilter's own filter API whether the message is blocked, so anything
 * MinehutFilter refuses to show in-game is also not delivered to Discord. Everything is done
 * reflectively so DiscordSRV keeps working (and compiling) without MinehutFilter installed.</p>
 */
public final class MinehutFilterUtil {

    private static final String PLUGIN_NAME = "MinehutFilter";
    private static final long FILTER_TIMEOUT_SECONDS = 3L;

    private static volatile Method filterMethod;
    private static volatile Class<?> filterResponseClass;
    private static volatile Method isBlockedMethod;
    private static volatile Method isErrorMethod;

    private MinehutFilterUtil() {}

    /**
     * Asks MinehutFilter whether it considers the given message blocked.
     *
     * <p>Uses MinehutFilter's own cache internally, so when MinehutFilter has already filtered the
     * message (which it does for normal chat) this returns immediately without an extra HTTP
     * request. When the filter errors or cannot be reached we fail open, so a broken filter will
     * never silence all chat going to Discord.</p>
     *
     * @param player the player that sent the message
     * @param message the plain-text message that was sent
     * @return {@code true} if the message is blocked and should not be delivered to Discord
     */
    public static boolean isMessageBlocked(Player player, String message) {
        if (player == null || message == null || message.isEmpty()) return false;

        Plugin plugin = PluginUtil.getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) return false;

        try {
            Method filter = filterMethod;
            if (filter == null || filter.getDeclaringClass() != plugin.getClass()) {
                filter = plugin.getClass().getMethod("filter", Player.class, String.class);
                filterMethod = filter;
            }

            Object result = filter.invoke(plugin, player, message);
            if (!(result instanceof CompletableFuture)) return false;

            CompletableFuture<?> future = (CompletableFuture<?>) result;
            // Don't block the server's main thread on a filter lookup; MinehutFilter's future is
            // already populated from its cache in the normal chat flow, so this is only a fallback
            Object response = Bukkit.isPrimaryThread()
                    ? future.getNow(null)
                    : future.get(FILTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response == null) return false;

            if (isBlockedMethod == null || filterResponseClass != response.getClass()) {
                filterResponseClass = response.getClass();
                isBlockedMethod = filterResponseClass.getMethod("isBlocked");
                isErrorMethod = filterResponseClass.getMethod("isError");
            }

            // Fail open: a filter that errors is not a blacklisted message, keep relaying chat
            if ((boolean) isErrorMethod.invoke(response)) return false;

            return (boolean) isBlockedMethod.invoke(response);
        } catch (Throwable t) {
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Failed to ask MinehutFilter whether a chat message is blocked: " + t);
            return false;
        }
    }

}

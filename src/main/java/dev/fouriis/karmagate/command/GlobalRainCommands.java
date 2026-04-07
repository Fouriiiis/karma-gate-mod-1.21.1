package dev.fouriis.karmagate.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.fouriis.karmagate.rain.GlobalRain;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;

public class GlobalRainCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("globalrain")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(literal("trigger")
                                .executes(context -> executeTrigger(context.getSource()))
                        )
                        .then(literal("reset")
                                .executes(context -> executeReset(context.getSource()))
                        )
        );
    }

    private static int executeTrigger(ServerCommandSource source) {
        GlobalRain rain = GlobalRain.get(source.getServer());
        rain.triggerRain();

        source.sendFeedback(
                () -> Text.literal("Global rain triggered. forcedRain=")
                        .append(Text.literal(String.valueOf(rain.isForcedRain())).formatted(Formatting.GREEN))
                        .append(" intensity=")
                        .append(Text.literal(String.format("%.3f", rain.getIntensity())).formatted(Formatting.AQUA))
                        .append(" flood=")
                        .append(Text.literal(String.format("%.3f", rain.getFlood())).formatted(Formatting.AQUA)),
                true
        );
        return 1;
    }

    private static int executeReset(ServerCommandSource source) {
        GlobalRain rain = GlobalRain.get(source.getServer());
        rain.resetRain();

        source.sendFeedback(
                () -> Text.literal("Global rain reset. forcedRain=")
                        .append(Text.literal(String.valueOf(rain.isForcedRain())).formatted(Formatting.RED))
                        .append(" intensity=")
                        .append(Text.literal(String.format("%.3f", rain.getIntensity())).formatted(Formatting.AQUA))
                        .append(" flood=")
                        .append(Text.literal(String.format("%.3f", rain.getFlood())).formatted(Formatting.AQUA)),
                true
        );
        return 1;
    }
}

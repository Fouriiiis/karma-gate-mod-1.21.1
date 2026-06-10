package dev.fouriis.karmagate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.fouriis.karmagate.gridproject.ProjectionZoneData;
import dev.fouriis.karmagate.gridproject.ProjectionZoneManager;
import dev.fouriis.karmagate.gridproject.StarMatrixData;
import dev.fouriis.karmagate.gridproject.StarMatrixManager;
import dev.fouriis.karmagate.network.ModNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers /starmatrix commands for creating StarMatrices inside projection zones.
 */
public final class StarMatrixCommands {
    private StarMatrixCommands() {}

    private static final SuggestionProvider<ServerCommandSource> MATRIX_NAME_SUGGESTIONS = (context, builder) -> {
        StarMatrixManager manager = StarMatrixManager.get(context.getSource().getServer());
        return CommandSource.suggestMatching(manager.getMatrixNames(), builder);
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("starmatrix")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("new")
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("x", IntegerArgumentType.integer())
                            .then(argument("y", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                    .executes(StarMatrixCommands::executeNew)
                                )
                            )
                        )
                    )
                )
                .then(literal("remove")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(MATRIX_NAME_SUGGESTIONS)
                        .executes(StarMatrixCommands::executeRemove)
                    )
                )
                .then(literal("list")
                    .executes(StarMatrixCommands::executeList)
                )
        );
    }

    private static int executeNew(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        BlockPos pos = new BlockPos(x, y, z);

        ProjectionZoneManager zoneManager = ProjectionZoneManager.get(source.getServer());
        Optional<ProjectionZoneData> zone = findContainingZone(zoneManager, pos);
        if (zone.isEmpty()) {
            source.sendError(Text.literal("No projection zone contains that position.").formatted(Formatting.RED));
            return 0;
        }

        StarMatrixManager manager = StarMatrixManager.get(source.getServer());
        StarMatrixData data = StarMatrixData.of(name, zone.get().name(), x, y, z);
        boolean isNew = manager.addMatrix(data);
        ModNetworking.syncStarMatricesToAll(source.getServer());

        Text feedback = Text.literal(isNew ? "Created StarMatrix '" : "Updated StarMatrix '")
            .append(Text.literal(name).formatted(isNew ? Formatting.GREEN : Formatting.GOLD))
            .append("' in zone ")
            .append(Text.literal(zone.get().name()).formatted(Formatting.AQUA))
            .append(" at (")
            .append(Text.literal(x + ", " + y + ", " + z).formatted(Formatting.YELLOW))
            .append(")");
        source.sendFeedback(() -> feedback, true);
        return 1;
    }

    private static int executeRemove(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        StarMatrixManager manager = StarMatrixManager.get(source.getServer());
        Optional<StarMatrixData> removed = manager.removeMatrix(name);
        if (removed.isEmpty()) {
            source.sendError(
                Text.literal("No StarMatrix named '")
                    .append(Text.literal(name).formatted(Formatting.YELLOW))
                    .append("' exists")
            );
            return 0;
        }

        ModNetworking.syncStarMatricesToAll(source.getServer());
        source.sendFeedback(
            () -> Text.literal("Removed StarMatrix '")
                .append(Text.literal(name).formatted(Formatting.RED))
                .append("'"),
            true
        );
        return 1;
    }

    private static int executeList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        StarMatrixManager manager = StarMatrixManager.get(source.getServer());

        if (manager.getMatrixCount() == 0) {
            source.sendFeedback(() -> Text.literal("No StarMatrices defined.").formatted(Formatting.GRAY), false);
            return 0;
        }

        source.sendFeedback(
            () -> Text.literal("StarMatrices (")
                .append(Text.literal(String.valueOf(manager.getMatrixCount())).formatted(Formatting.GREEN))
                .append("):"),
            false
        );

        for (StarMatrixData matrix : manager.getAllMatrices()) {
            source.sendFeedback(
                () -> Text.literal("  - ")
                    .append(Text.literal(matrix.name()).formatted(Formatting.AQUA))
                    .append(" zone=")
                    .append(Text.literal(matrix.zoneName()).formatted(Formatting.GREEN))
                    .append(" pos=(")
                    .append(Text.literal(
                        matrix.position().getX() + ", " + matrix.position().getY() + ", " + matrix.position().getZ()
                    ).formatted(Formatting.YELLOW))
                    .append(")"),
                false
            );
        }

        return manager.getMatrixCount();
    }

    private static Optional<ProjectionZoneData> findContainingZone(ProjectionZoneManager manager, BlockPos pos) {
        ProjectionZoneData best = null;
        long bestVolume = Long.MAX_VALUE;
        for (ProjectionZoneData zone : manager.getAllZones()) {
            BlockPos min = zone.getMin();
            BlockPos max = zone.getMax();
            if (pos.getX() < min.getX() || pos.getX() > max.getX()
                || pos.getY() < min.getY() || pos.getY() > max.getY()
                || pos.getZ() < min.getZ() || pos.getZ() > max.getZ()) {
                continue;
            }

            long volume = (long) (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1L)
                * (max.getZ() - min.getZ() + 1L);
            if (best == null || volume < bestVolume) {
                best = zone;
                bestVolume = volume;
            }
        }
        return Optional.ofNullable(best);
    }
}

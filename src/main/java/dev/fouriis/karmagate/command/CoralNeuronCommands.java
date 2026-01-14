package dev.fouriis.karmagate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.fouriis.karmagate.CoralNeuronEntity;
import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.coralneuron.CoralNeuronData;
import dev.fouriis.karmagate.coralneuron.CoralNeuronManager;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers the /coralNeuron command for managing CoralNeuron entities.
 *
 * Usage:
 *   /coralNeuron new <name> <x1> <y1> <z1> <x2> <y2> <z2> <anchored1> <anchored2>
 *   /coralNeuron delete <name>
 *   /coralNeuron list
 */
public class CoralNeuronCommands {

    /**
     * Suggestion provider for existing neuron names.
     */
    private static final SuggestionProvider<ServerCommandSource> NEURON_NAME_SUGGESTIONS = (context, builder) -> {
        CoralNeuronManager manager = CoralNeuronManager.get(context.getSource().getServer());
        return CommandSource.suggestMatching(manager.getNeuronNames(), builder);
    };

    /**
     * Registers all /coralNeuron subcommands.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("coralNeuron")
                        .requires(source -> source.hasPermissionLevel(2)) // Require OP level 2
                        .then(literal("new")
                                .then(argument("name", StringArgumentType.word())
                                        .then(argument("x1", DoubleArgumentType.doubleArg())
                                                .then(argument("y1", DoubleArgumentType.doubleArg())
                                                        .then(argument("z1", DoubleArgumentType.doubleArg())
                                                                .then(argument("x2", DoubleArgumentType.doubleArg())
                                                                        .then(argument("y2", DoubleArgumentType.doubleArg())
                                                                                .then(argument("z2", DoubleArgumentType.doubleArg())
                                                                                        .then(argument("anchored1", BoolArgumentType.bool())
                                                                                                .then(argument("anchored2", BoolArgumentType.bool())
                                                                                                        .executes(CoralNeuronCommands::executeNew)
                                                                                                )
                                                                                        )
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(literal("delete")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests(NEURON_NAME_SUGGESTIONS)
                                        .executes(CoralNeuronCommands::executeDelete)
                                )
                                .then(literal("all")
                                        .executes(CoralNeuronCommands::executeDeleteAll)
                                )
                        )
                        .then(literal("list")
                                .executes(CoralNeuronCommands::executeList)
                        )
        );
    }

    /**
     * Executes /coralNeuron new <name> <x1> <y1> <z1> <x2> <y2> <z2> <anchored1> <anchored2>
     */
    private static int executeNew(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        String name = StringArgumentType.getString(context, "name");
        double x1 = DoubleArgumentType.getDouble(context, "x1");
        double y1 = DoubleArgumentType.getDouble(context, "y1");
        double z1 = DoubleArgumentType.getDouble(context, "z1");
        double x2 = DoubleArgumentType.getDouble(context, "x2");
        double y2 = DoubleArgumentType.getDouble(context, "y2");
        double z2 = DoubleArgumentType.getDouble(context, "z2");
        boolean anchored1 = BoolArgumentType.getBool(context, "anchored1");
        boolean anchored2 = BoolArgumentType.getBool(context, "anchored2");

        CoralNeuronManager manager = CoralNeuronManager.get(source.getServer());

        // Check if name already exists
        if (manager.hasNeuron(name)) {
            source.sendError(
                    Text.literal("A CoralNeuron named '")
                            .append(Text.literal(name).formatted(Formatting.YELLOW))
                            .append("' already exists. Delete it first or use a different name.")
            );
            return 0;
        }

        // Get the world to spawn in (use the command sender's world)
        ServerWorld world = source.getWorld();

        // Create the entity
        Vec3d anchorA = new Vec3d(x1, y1, z1);
        Vec3d anchorB = new Vec3d(x2, y2, z2);

        CoralNeuronEntity entity = new CoralNeuronEntity(
                KarmaGateMod.VINE_ENTITY_TYPE,
                world,
                anchorA,
                anchorB,
                anchored1,
                anchored2
        );

        // Spawn the entity
        world.spawnEntity(entity);

        // Register in manager
        CoralNeuronData data = CoralNeuronData.of(
                name,
                entity.getUuid(),
                x1, y1, z1,
                x2, y2, z2,
                anchored1,
                anchored2
        );
        manager.addNeuron(data);

        source.sendFeedback(
                () -> Text.literal("Created CoralNeuron '")
                        .append(Text.literal(name).formatted(Formatting.GREEN))
                        .append("' from (")
                        .append(Text.literal(String.format("%.1f, %.1f, %.1f", x1, y1, z1)).formatted(Formatting.YELLOW))
                        .append(") to (")
                        .append(Text.literal(String.format("%.1f, %.1f, %.1f", x2, y2, z2)).formatted(Formatting.YELLOW))
                        .append(") [anchored: ")
                        .append(Text.literal(String.valueOf(anchored1)).formatted(anchored1 ? Formatting.GREEN : Formatting.RED))
                        .append(", ")
                        .append(Text.literal(String.valueOf(anchored2)).formatted(anchored2 ? Formatting.GREEN : Formatting.RED))
                        .append("]"),
                true
        );

        return 1;
    }

    /**     * Executes /coralNeuron delete all
     */
    private static int executeDeleteAll(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        CoralNeuronManager manager = CoralNeuronManager.get(source.getServer());

        if (manager.getNeuronCount() == 0) {
            source.sendFeedback(() -> Text.literal("No CoralNeuron entities registered.").formatted(Formatting.GRAY), false);
            return 0;
        }

        List<String> names = new ArrayList<>(manager.getNeuronNames());
        int removedCount = 0;
        int entityRemoved = 0;

        for (String name : names) {
            Optional<CoralNeuronData> removedOpt = manager.removeNeuron(name);
            if (removedOpt.isPresent()) {
                removedCount++;
                Optional<CoralNeuronEntity> entityOpt = manager.findEntity(source.getServer(), removedOpt.get().entityUuid());
                if (entityOpt.isPresent()) {
                    entityOpt.get().discard();
                    entityRemoved++;
                }
            }
        }

        final int removed = removedCount;
        final int removedEntities = entityRemoved;

        source.sendFeedback(
                () -> Text.literal("Deleted all CoralNeuron entries (")
                        .append(Text.literal(String.valueOf(removed)).formatted(Formatting.GREEN))
                        .append(") and removed ")
                        .append(Text.literal(String.valueOf(removedEntities)).formatted(Formatting.GREEN))
                        .append(" entities from world"),
                true
        );

        return removed;
    }

    /**     * Executes /coralNeuron delete <name>
     */
    private static int executeDelete(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        CoralNeuronManager manager = CoralNeuronManager.get(source.getServer());
        Optional<CoralNeuronData> dataOpt = manager.removeNeuron(name);

        if (dataOpt.isPresent()) {
            CoralNeuronData data = dataOpt.get();

            // Try to find and remove the actual entity
            Optional<CoralNeuronEntity> entityOpt = manager.findEntity(source.getServer(), data.entityUuid());
            if (entityOpt.isPresent()) {
                entityOpt.get().discard();
                source.sendFeedback(
                        () -> Text.literal("Deleted CoralNeuron '")
                                .append(Text.literal(name).formatted(Formatting.RED))
                                .append("' and removed entity from world"),
                        true
                );
            } else {
                source.sendFeedback(
                        () -> Text.literal("Deleted CoralNeuron '")
                                .append(Text.literal(name).formatted(Formatting.RED))
                                .append("' (entity was not found in world - may have been unloaded)"),
                        true
                );
            }
            return 1;
        } else {
            source.sendError(
                    Text.literal("No CoralNeuron named '")
                            .append(Text.literal(name).formatted(Formatting.YELLOW))
                            .append("' exists")
            );
            return 0;
        }
    }

    /**
     * Executes /coralNeuron list
     */
    private static int executeList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        CoralNeuronManager manager = CoralNeuronManager.get(source.getServer());

        if (manager.getNeuronCount() == 0) {
            source.sendFeedback(() -> Text.literal("No CoralNeuron entities registered.").formatted(Formatting.GRAY), false);
            return 0;
        }

        source.sendFeedback(
                () -> Text.literal("CoralNeuron Entities (")
                        .append(Text.literal(String.valueOf(manager.getNeuronCount())).formatted(Formatting.GREEN))
                        .append("):"),
                false
        );

        for (CoralNeuronData data : manager.getAllNeurons()) {
            Vec3d a = data.anchorA();
            Vec3d b = data.anchorB();

            // Check if entity is currently loaded
            boolean loaded = manager.findEntity(source.getServer(), data.entityUuid()).isPresent();

            source.sendFeedback(
                    () -> Text.literal("  • ")
                            .append(Text.literal(data.name()).formatted(Formatting.AQUA))
                            .append(": (")
                            .append(Text.literal(String.format("%.1f, %.1f, %.1f", a.x, a.y, a.z)).formatted(Formatting.YELLOW))
                            .append(") to (")
                            .append(Text.literal(String.format("%.1f, %.1f, %.1f", b.x, b.y, b.z)).formatted(Formatting.YELLOW))
                            .append(") [")
                            .append(Text.literal(data.anchoredA() ? "⚓" : "~").formatted(data.anchoredA() ? Formatting.GREEN : Formatting.GRAY))
                            .append(", ")
                            .append(Text.literal(data.anchoredB() ? "⚓" : "~").formatted(data.anchoredB() ? Formatting.GREEN : Formatting.GRAY))
                            .append("] ")
                            .append(Text.literal(loaded ? "✓" : "?").formatted(loaded ? Formatting.GREEN : Formatting.GRAY)),
                    false
            );
        }

        return manager.getNeuronCount();
    }
}

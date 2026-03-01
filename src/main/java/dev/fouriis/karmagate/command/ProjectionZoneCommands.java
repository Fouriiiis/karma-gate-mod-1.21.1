package dev.fouriis.karmagate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.fouriis.karmagate.gridproject.ProjectionZoneData;
import dev.fouriis.karmagate.gridproject.ProjectionZoneManager;
import dev.fouriis.karmagate.network.ModNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers the /pz command for managing projection zones.
 * 
 * Usage:
 *   /pz new <name> <x1> <y1> <z1> <x2> <y2> <z2> [swarmerCount] [drawCircles] [drawGrid]
 *   /pz remove <name>
 *   /pz list
 */
public class ProjectionZoneCommands {
    
    /**
     * Suggestion provider for existing zone names.
     */
    private static final SuggestionProvider<ServerCommandSource> ZONE_NAME_SUGGESTIONS = (context, builder) -> {
        ProjectionZoneManager manager = ProjectionZoneManager.get(context.getSource().getServer());
        return CommandSource.suggestMatching(manager.getZoneNames(), builder);
    };
    
    /**
     * Registers all /pz subcommands.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("pz")
                .requires(source -> source.hasPermissionLevel(2)) // Require OP level 2
                .then(literal("new")
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("x1", IntegerArgumentType.integer())
                            .then(argument("y1", IntegerArgumentType.integer())
                                .then(argument("z1", IntegerArgumentType.integer())
                                    .then(argument("x2", IntegerArgumentType.integer())
                                        .then(argument("y2", IntegerArgumentType.integer())
                                            .then(argument("z2", IntegerArgumentType.integer())
                                                .executes(ProjectionZoneCommands::executeNew)
                                                .then(argument("swarmerCount", IntegerArgumentType.integer(0))
                                                    .executes(ProjectionZoneCommands::executeNew)
                                                    .then(argument("drawCircles", BoolArgumentType.bool())
                                                        .executes(ProjectionZoneCommands::executeNew)
                                                        .then(argument("drawGrid", BoolArgumentType.bool())
                                                            .executes(ProjectionZoneCommands::executeNew)
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
                )
                .then(literal("remove")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(ZONE_NAME_SUGGESTIONS)
                        .executes(ProjectionZoneCommands::executeRemove)
                    )
                )
                .then(literal("list")
                    .executes(ProjectionZoneCommands::executeList)
                )
        );
    }
    
    /**
     * Executes /pz new <name> <x1> <y1> <z1> <x2> <y2> <z2>
     */
    private static int executeNew(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        String name = StringArgumentType.getString(context, "name");
        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int y1 = IntegerArgumentType.getInteger(context, "y1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int y2 = IntegerArgumentType.getInteger(context, "y2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");
        
        // Optional arguments with defaults
        int swarmerCount;
        try {
            swarmerCount = IntegerArgumentType.getInteger(context, "swarmerCount");
        } catch (IllegalArgumentException e) {
            swarmerCount = ProjectionZoneData.DEFAULT_SWARMER_COUNT;
        }
        
        boolean drawCircles;
        try {
            drawCircles = BoolArgumentType.getBool(context, "drawCircles");
        } catch (IllegalArgumentException e) {
            drawCircles = true;
        }
        
        boolean drawGrid;
        try {
            drawGrid = BoolArgumentType.getBool(context, "drawGrid");
        } catch (IllegalArgumentException e) {
            drawGrid = true;
        }
        
        ProjectionZoneManager manager = ProjectionZoneManager.get(source.getServer());
        ProjectionZoneData zone = ProjectionZoneData.of(name, x1, y1, z1, x2, y2, z2, swarmerCount, drawCircles, drawGrid);
        
        boolean isNew = manager.addZone(zone);
        
        // Sync to all clients
        ModNetworking.syncToAll(source.getServer());
        
        // Capture for lambda
        final int sc = swarmerCount;
        final boolean dc = drawCircles;
        final boolean dg = drawGrid;
        
        if (isNew) {
            source.sendFeedback(
                () -> Text.literal("Created projection zone '")
                    .append(Text.literal(name).formatted(Formatting.GREEN))
                    .append("' from (")
                    .append(Text.literal(x1 + ", " + y1 + ", " + z1).formatted(Formatting.YELLOW))
                    .append(") to (")
                    .append(Text.literal(x2 + ", " + y2 + ", " + z2).formatted(Formatting.YELLOW))
                    .append(") swarmers=")
                    .append(Text.literal(String.valueOf(sc)).formatted(Formatting.AQUA))
                    .append(" circles=")
                    .append(Text.literal(String.valueOf(dc)).formatted(Formatting.AQUA))
                    .append(" grid=")
                    .append(Text.literal(String.valueOf(dg)).formatted(Formatting.AQUA)),
                true
            );
        } else {
            source.sendFeedback(
                () -> Text.literal("Updated projection zone '")
                    .append(Text.literal(name).formatted(Formatting.GOLD))
                    .append("' with new bounds (")
                    .append(Text.literal(x1 + ", " + y1 + ", " + z1).formatted(Formatting.YELLOW))
                    .append(") to (")
                    .append(Text.literal(x2 + ", " + y2 + ", " + z2).formatted(Formatting.YELLOW))
                    .append(") swarmers=")
                    .append(Text.literal(String.valueOf(sc)).formatted(Formatting.AQUA))
                    .append(" circles=")
                    .append(Text.literal(String.valueOf(dc)).formatted(Formatting.AQUA))
                    .append(" grid=")
                    .append(Text.literal(String.valueOf(dg)).formatted(Formatting.AQUA)),
                true
            );
        }
        
        return 1;
    }
    
    /**
     * Executes /pz remove <name>
     */
    private static int executeRemove(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        
        ProjectionZoneManager manager = ProjectionZoneManager.get(source.getServer());
        Optional<ProjectionZoneData> removed = manager.removeZone(name);
        
        if (removed.isPresent()) {
            // Sync to all clients
            ModNetworking.syncToAll(source.getServer());
            
            source.sendFeedback(
                () -> Text.literal("Removed projection zone '")
                    .append(Text.literal(name).formatted(Formatting.RED))
                    .append("'"),
                true
            );
            return 1;
        } else {
            source.sendError(
                Text.literal("No projection zone named '")
                    .append(Text.literal(name).formatted(Formatting.YELLOW))
                    .append("' exists")
            );
            return 0;
        }
    }
    
    /**
     * Executes /pz list
     */
    private static int executeList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ProjectionZoneManager manager = ProjectionZoneManager.get(source.getServer());
        
        if (manager.getZoneCount() == 0) {
            source.sendFeedback(() -> Text.literal("No projection zones defined.").formatted(Formatting.GRAY), false);
            return 0;
        }
        
        source.sendFeedback(
            () -> Text.literal("Projection Zones (")
                .append(Text.literal(String.valueOf(manager.getZoneCount())).formatted(Formatting.GREEN))
                .append("):"),
            false
        );
        
        for (ProjectionZoneData zone : manager.getAllZones()) {
            source.sendFeedback(
                () -> Text.literal("  • ")
                    .append(Text.literal(zone.name()).formatted(Formatting.AQUA))
                    .append(": (")
                    .append(Text.literal(zone.corner1().getX() + ", " + zone.corner1().getY() + ", " + zone.corner1().getZ()).formatted(Formatting.YELLOW))
                    .append(") to (")
                    .append(Text.literal(zone.corner2().getX() + ", " + zone.corner2().getY() + ", " + zone.corner2().getZ()).formatted(Formatting.YELLOW))
                    .append(") swarmers=")
                    .append(Text.literal(String.valueOf(zone.swarmerCount())).formatted(Formatting.AQUA))
                    .append(" circles=")
                    .append(Text.literal(String.valueOf(zone.drawCircles())).formatted(Formatting.AQUA))
                    .append(" grid=")
                    .append(Text.literal(String.valueOf(zone.drawGrid())).formatted(Formatting.AQUA)),
                false
            );
        }
        
        return manager.getZoneCount();
    }
}

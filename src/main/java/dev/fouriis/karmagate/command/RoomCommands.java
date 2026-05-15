package dev.fouriis.karmagate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.fouriis.karmagate.network.ModNetworking;
import dev.fouriis.karmagate.room.RoomData;
import dev.fouriis.karmagate.room.RoomManager;
import dev.fouriis.karmagate.room.RoomSelection;
import dev.fouriis.karmagate.room.RoomSelectionManager;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers the /room command for managing rooms.
 *
 * Usage:
 *   /room new <name>
 *   /room delete <name>
 */
public class RoomCommands {

    private static final SuggestionProvider<ServerCommandSource> ROOM_NAME_SUGGESTIONS = (context, builder) -> {
        RoomManager manager = RoomManager.get(context.getSource().getServer());
        return CommandSource.suggestMatching(manager.getRoomNames(), builder);
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("room")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("new")
                    .then(argument("name", StringArgumentType.word())
                        .executes(RoomCommands::executeNew)
                    )
                )
                .then(literal("delete")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(ROOM_NAME_SUGGESTIONS)
                        .executes(RoomCommands::executeDelete)
                    )
                )
        );
    }

    private static int executeNew(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("/room new can only be used by a player."));
            return 0;
        }

        RoomSelection selection = RoomSelectionManager.getSelection(player);
        if (selection == null || !selection.isComplete()) {
            source.sendError(Text.literal("Select two corners with the Room Tool first."));
            return 0;
        }

        BlockPos corner1 = selection.corner1();
        BlockPos corner2 = selection.corner2();

        RoomManager manager = RoomManager.get(source.getServer());
        RoomData room = RoomData.of(
            name,
            corner1.getX(), corner1.getY(), corner1.getZ(),
            corner2.getX(), corner2.getY(), corner2.getZ()
        );

        boolean isNew = manager.addRoom(source.getServer(), room);
        ModNetworking.syncRoomsToAll(source.getServer());

        if (isNew) {
            source.sendFeedback(
                () -> Text.literal("Created room '")
                    .append(Text.literal(name).formatted(Formatting.GREEN))
                    .append("' from (")
                    .append(Text.literal(formatPos(corner1)).formatted(Formatting.YELLOW))
                    .append(") to (")
                    .append(Text.literal(formatPos(corner2)).formatted(Formatting.YELLOW))
                    .append(")"),
                true
            );
        } else {
            source.sendFeedback(
                () -> Text.literal("Updated room '")
                    .append(Text.literal(name).formatted(Formatting.GOLD))
                    .append("' to (")
                    .append(Text.literal(formatPos(corner1)).formatted(Formatting.YELLOW))
                    .append(") -> (")
                    .append(Text.literal(formatPos(corner2)).formatted(Formatting.YELLOW))
                    .append(")"),
                true
            );
        }

        return 1;
    }

    private static int executeDelete(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        RoomManager manager = RoomManager.get(source.getServer());
        Optional<RoomData> removed = manager.removeRoom(source.getServer(), name);

        if (removed.isPresent()) {
            ModNetworking.syncRoomsToAll(source.getServer());
            source.sendFeedback(
                () -> Text.literal("Deleted room '")
                    .append(Text.literal(name).formatted(Formatting.RED))
                    .append("'"),
                true
            );
            return 1;
        }

        source.sendError(
            Text.literal("No room named '")
                .append(Text.literal(name).formatted(Formatting.YELLOW))
                .append("' exists")
        );
        return 0;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}

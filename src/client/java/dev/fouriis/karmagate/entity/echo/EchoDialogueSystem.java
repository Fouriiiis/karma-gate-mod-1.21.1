package dev.fouriis.karmagate.entity.echo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/** Client-local port of the isolated Rain World ghost conversation and dialog box. */
public final class EchoDialogueSystem {
    private static final int UPDATES_PER_TICK = 2;
    private static final int BOX_ANIMATION_UPDATES = 6;
    private static final int BASE_LINGER_UPDATES = 40;
    private static final int MAX_DIALOGUE_WIDTH = 600;
    private static final int SCREEN_MARGIN = 20;
    private static final int LINE_HEIGHT = 15;
    private static final int HEIGHT_MARGIN = 20;
    private static final int WIDTH_MARGIN = 30;
    private static final int BOX_CORNER_RADIUS = 7;
    private static final int BOX_BORDER_THICKNESS = 2;

    private static final Set<UUID> ECHOES_PLAYER_IS_INSIDE = new HashSet<>();
    private static final DialogueBox DIALOG_BOX = new DialogueBox();

    private static ClientWorld activeWorld;
    private static UUID activePlayer;
    private static Conversation conversation;
    private static int conversationEchoId = -1;

    private EchoDialogueSystem() {
    }

    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            clear();
            return;
        }
        if (activeWorld != client.world || !client.player.getUuid().equals(activePlayer)) {
            clear();
            activeWorld = client.world;
            activePlayer = client.player.getUuid();
        }

        updateParticleZoneEntries(client);
        for (int i = 0; i < UPDATES_PER_TICK; i++) {
            if (conversation != null) {
                conversation.update();
                if (conversation.complete) conversation = null;
            }
            DIALOG_BOX.update();
        }
    }

    private static void updateParticleZoneEntries(MinecraftClient client) {
        Box search = client.player.getBoundingBox().expand(
                EchoEntity.PARTICLE_ZONE_HALF_EXTENT + EchoEntity.VISUAL_CENTER_Y + 2.0);
        List<EchoEntity> nearby = client.world.getEntitiesByClass(
                EchoEntity.class, search, echo -> !echo.isRemoved());
        Set<UUID> insideNow = new HashSet<>();
        EchoEntity newlyEntered = null;
        double nearestDistance = Double.MAX_VALUE;

        for (EchoEntity echo : nearby) {
            if (!echo.getParticleZone().contains(client.player.getPos())) continue;
            UUID id = echo.getUuid();
            insideNow.add(id);
            if (!ECHOES_PLAYER_IS_INSIDE.contains(id)) {
                double distance = echo.getVisualCenter().squaredDistanceTo(client.player.getPos());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    newlyEntered = echo;
                }
            }
        }

        ECHOES_PLAYER_IS_INSIDE.retainAll(insideNow);
        ECHOES_PLAYER_IS_INSIDE.addAll(insideNow);
        if (newlyEntered != null && conversation == null && DIALOG_BOX.currentMessage() == null) {
            startConversation(newlyEntered);
        }
    }

    private static void startConversation(EchoEntity echo) {
        DIALOG_BOX.clear();
        conversationEchoId = echo.getId();
        conversation = new Conversation(List.of(
                new TextEvent(18, "dialogue.karma-gate-mod.echo.1", 14),
                new TextEvent(0, "dialogue.karma-gate-mod.echo.2", 8),
                new SpecialEvent(0, "GHOST_PULSE"),
                new TextEvent(12, "dialogue.karma-gate-mod.echo.3", 18)
        ));
    }

    private static void runSpecialEvent(String eventName) {
        if (!"GHOST_PULSE".equals(eventName) || activeWorld == null) return;
        if (activeWorld.getEntityById(conversationEchoId) instanceof EchoEntity echo) {
            EchoGhostEffectSystem.triggerPulse(echo);
        }
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        Message message = DIALOG_BOX.currentMessage();
        if (message == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer renderer = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        float inverseGuiScale = (float) (1.0 / Math.max(1.0, client.getWindow().getScaleFactor()));
        int availableWidth = client.getWindow().getFramebufferWidth() - SCREEN_MARGIN * 2;
        int maxContentWidth = Math.max(40,
                Math.min(MAX_DIALOGUE_WIDTH, availableWidth) - WIDTH_MARGIN);
        List<OrderedText> fullLines = renderer.wrapLines(dialogueText(message.text), maxContentWidth);
        if (fullLines.isEmpty()) fullLines = List.of(dialogueText("").asOrderedText());

        int contentWidth = 0;
        for (OrderedText line : fullLines) contentWidth = Math.max(contentWidth, renderer.getWidth(line));
        int fullWidth = contentWidth + WIDTH_MARGIN;
        int fullHeight = HEIGHT_MARGIN + fullLines.size() * LINE_HEIGHT;
        float tickDelta = tickCounter == null ? 1.0f : tickCounter.getTickDelta(false);
        float size = MathHelper.lerp(tickDelta, DIALOG_BOX.lastSizeFactor, DIALOG_BOX.sizeFactor);
        int width = Math.round(40.0f + (fullWidth - 40.0f) * MathHelper.sqrt(Math.max(0.0f, size)));
        int height = Math.round(fullHeight * (0.5f + 0.5f * size));
        int left = -width / 2;
        int right = left + width;
        int top = -height / 2;
        int bottom = top + height;

        context.getMatrices().push();
        context.getMatrices().translate(
                screenWidth * 0.5f, screenHeight - fullHeight * inverseGuiScale, 0.0f);
        context.getMatrices().scale(inverseGuiScale, inverseGuiScale, 1.0f);
        try {
            fillRoundedRect(context,
                    left + BOX_BORDER_THICKNESS, top + BOX_BORDER_THICKNESS,
                    right - BOX_BORDER_THICKNESS, bottom - BOX_BORDER_THICKNESS,
                    BOX_CORNER_RADIUS - BOX_BORDER_THICKNESS, 0xBF000000);
            drawRoundedBorder(context, left, top, right, bottom,
                    BOX_CORNER_RADIUS, BOX_BORDER_THICKNESS, 0xFFFFFFFF);
            if (DIALOG_BOX.showText.isEmpty()) return;

            List<OrderedText> shownLines = renderer.wrapLines(
                    dialogueText(DIALOG_BOX.showText), maxContentWidth);
            int textX = -contentWidth / 2;
            int textY = top + Math.round(LINE_HEIGHT * 0.6666f);
            for (OrderedText line : shownLines) {
                context.drawText(renderer, line, textX, textY, 0xFFFFFFFF, false);
                textY += LINE_HEIGHT;
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    private static void drawRoundedBorder(DrawContext context, int left, int top,
                                          int right, int bottom, int radius,
                                          int thickness, int color) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0 || thickness <= 0) return;

        int outerRadius = Math.max(0, Math.min(radius, Math.min(width / 2, height / 2)));
        int innerWidth = width - thickness * 2;
        int innerHeight = height - thickness * 2;
        int innerRadius = Math.max(0, outerRadius - thickness);
        for (int row = 0; row < height; row++) {
            int outerInset = roundedInset(row, height, outerRadius);
            int outerLeft = left + outerInset;
            int outerRight = right - outerInset;
            if (row < thickness || row >= height - thickness
                    || innerWidth <= 0 || innerHeight <= 0) {
                context.fill(outerLeft, top + row, outerRight, top + row + 1, color);
                continue;
            }

            int innerInset = roundedInset(row - thickness, innerHeight, innerRadius);
            int innerLeft = left + thickness + innerInset;
            int innerRight = right - thickness - innerInset;
            if (innerLeft > outerLeft) {
                context.fill(outerLeft, top + row, innerLeft, top + row + 1, color);
            }
            if (outerRight > innerRight) {
                context.fill(innerRight, top + row, outerRight, top + row + 1, color);
            }
        }
    }

    private static int roundedInset(int row, int height, int radius) {
        if (radius <= 0 || row >= radius && row < height - radius) return 0;
        double dy = row < radius ? radius - row - 0.5 : row - (height - radius) + 0.5;
        return (int) Math.ceil(radius - Math.sqrt(Math.max(0.0,
                radius * radius - dy * dy)));
    }

    private static Text dialogueText(String value) {
        return Text.literal(value);
    }

    private static void fillRoundedRect(DrawContext context, int left, int top,
                                        int right, int bottom, int radius, int color) {
        if (right <= left || bottom <= top) return;
        int actualRadius = Math.max(0,
                Math.min(radius, Math.min((right - left) / 2, (bottom - top) / 2)));
        if (actualRadius == 0) {
            context.fill(left, top, right, bottom, color);
            return;
        }

        context.fill(left, top + actualRadius, right, bottom - actualRadius, color);
        for (int row = 0; row < actualRadius; row++) {
            double dy = actualRadius - row - 0.5;
            int inset = (int) Math.ceil(actualRadius
                    - Math.sqrt(actualRadius * actualRadius - dy * dy));
            context.fill(left + inset, top + row, right - inset, top + row + 1, color);
            context.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color);
        }
    }

    public static void clear() {
        activeWorld = null;
        activePlayer = null;
        conversation = null;
        conversationEchoId = -1;
        ECHOES_PLAYER_IS_INSIDE.clear();
        DIALOG_BOX.clear();
    }

    private static final class Conversation {
        private final List<DialogueEvent> events;
        private boolean complete;

        private Conversation(List<DialogueEvent> events) {
            this.events = new ArrayList<>(events);
        }

        private void update() {
            if (events.isEmpty()) {
                complete = true;
                return;
            }
            DialogueEvent event = events.getFirst();
            event.update();
            if (event.isOver()) events.removeFirst();
        }
    }

    private abstract static class DialogueEvent {
        private final int initialWait;
        private int age;
        private boolean activated;

        private DialogueEvent(int initialWait) {
            this.initialWait = initialWait;
        }

        private void update() {
            if (!activated && age == initialWait) {
                activated = true;
                activate();
            }
            age++;
        }

        protected abstract void activate();

        protected final boolean hasPassedInitialWait() {
            return age > initialWait;
        }

        protected boolean isOver() {
            return true;
        }
    }

    private static final class TextEvent extends DialogueEvent {
        private final String translationKey;
        private final int extraLinger;

        private TextEvent(int initialWait, String translationKey, int extraLinger) {
            super(initialWait);
            this.translationKey = translationKey;
            this.extraLinger = extraLinger;
        }

        @Override
        protected void activate() {
            DIALOG_BOX.newMessage(Text.translatable(translationKey).getString(), extraLinger);
        }

        @Override
        protected boolean isOver() {
            return hasPassedInitialWait() && DIALOG_BOX.currentMessage() == null;
        }
    }

    private static final class SpecialEvent extends DialogueEvent {
        private final String eventName;

        private SpecialEvent(int initialWait, String eventName) {
            super(initialWait);
            this.eventName = eventName;
        }

        @Override
        protected void activate() {
            runSpecialEvent(eventName);
        }
    }

    private static final class DialogueBox {
        private final Queue<Message> messages = new ArrayDeque<>();
        private int showCharacter;
        private int lingerCounter;
        private String showText = "";
        private float sizeFactor;
        private float lastSizeFactor;

        private Message currentMessage() {
            return messages.peek();
        }

        private void update() {
            Message message = currentMessage();
            if (message == null) return;
            lastSizeFactor = sizeFactor;
            if (sizeFactor < 1.0f && lingerCounter < 1) {
                sizeFactor = Math.min(sizeFactor + 1.0f / BOX_ANIMATION_UPDATES, 1.0f);
                return;
            }
            if (showCharacter < message.text.length()) {
                showCharacter++;
                showText = message.text.substring(0, showCharacter);
                return;
            }

            lingerCounter++;
            if (lingerCounter <= message.linger) return;
            showText = "";
            if (sizeFactor > 0.0f) {
                sizeFactor = Math.max(0.0f, sizeFactor - 1.0f / BOX_ANIMATION_UPDATES);
                return;
            }
            messages.remove();
            if (!messages.isEmpty()) initializeNextMessage();
        }

        private void newMessage(String sourceText, int extraLinger) {
            String displayText = sourceText.replace("<LINE>", "\n").replace("<WWLINE>", "");
            messages.add(new Message(displayText,
                    sourceText.length() + BASE_LINGER_UPDATES + extraLinger));
            if (messages.size() == 1) initializeNextMessage();
        }

        private void initializeNextMessage() {
            showCharacter = 0;
            showText = "";
            lastSizeFactor = 0.0f;
            sizeFactor = 0.0f;
            lingerCounter = 0;
        }

        private void clear() {
            messages.clear();
            showCharacter = 0;
            lingerCounter = 0;
            showText = "";
            sizeFactor = 0.0f;
            lastSizeFactor = 0.0f;
        }
    }

    private record Message(String text, int linger) {
    }
}

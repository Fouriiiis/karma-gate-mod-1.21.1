package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.network.SpawnGraffitiPayload;
import net.brickcraftdream.librainworldmc.client.gui.widgets.container.ScreenLayoutContainerWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.core.WidgetRenderUtil;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledButtonWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledIconButtonWidget;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.InputStream;
import java.util.*;

/**
 * A screen that displays all available graffiti textures in a grid.
 * When a texture is selected, it spawns the graffiti entity with that texture.
 */
public class GraffitiPickerScreen extends Screen {
    
    private static final int THUMBNAIL_SIZE = 64;
    private static final int PADDING = 8;
    private static final int COLUMNS = 6;

    private final double spawnX, spawnY, spawnZ;
    private final Direction facing;
    private final java.util.function.Consumer<String> onTextureSelected;
    private ScreenLayoutContainerWidget parentContainer;

    private final List<GraffitiTexture> textures = new ArrayList<>();

    public GraffitiPickerScreen(double x, double y, double z, Direction facing) {
        this(x, y, z, facing, null);
    }

    public GraffitiPickerScreen(java.util.function.Consumer<String> onTextureSelected) {
        this(0.0, 0.0, 0.0, Direction.NORTH, onTextureSelected);
    }

    private GraffitiPickerScreen(double x, double y, double z, Direction facing,
                                 java.util.function.Consumer<String> onTextureSelected) {
        super(Text.literal("Select Graffiti"));
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.facing = facing;
        this.onTextureSelected = onTextureSelected;
    }
    
    @Override
    protected void init() {
        super.init();

        loadTextures();

        int rows = (textures.size() + COLUMNS - 1) / COLUMNS;

        Map<ClickableWidget, ScreenLayoutContainerWidget.AreaWidgetLayout> layoutMap = new HashMap<>();

        int gridWidth = COLUMNS * (THUMBNAIL_SIZE + PADDING) - PADDING;
        int startX = (this.width - gridWidth) / 2;
        int startY = 40;

        for (int i = 0; i < textures.size(); i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;

            int x = startX + col * (THUMBNAIL_SIZE + PADDING);
            int y = startY + row * (THUMBNAIL_SIZE + PADDING);

            int finalI = i;
            layoutMap.put(
                new LabeledIconButtonWidget(x, y, THUMBNAIL_SIZE, THUMBNAIL_SIZE, Text.empty(),
                    textures.get(finalI).isVideo() ? Identifier.of("librainworldmc", "atlas_elements/rainworld/rainworld_white") : textures.get(finalI).textureId(),
                    0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, THUMBNAIL_SIZE - 2, THUMBNAIL_SIZE - 2,
                    () -> selectTexture(textures.get(finalI)), List.of(Text.literal(textures.get(finalI).fileName())), (widget, bool) -> {}) {
                        @Override
                        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
                            if(textures.get(finalI).isVideo()) {
                                context.fill(getX(), getY(), getX() + THUMBNAIL_SIZE, getY() + THUMBNAIL_SIZE, 0xFF1A1A2E);
                                context.fill(getX() + 20, getY() + 14, getX() + 24, getY() + 50, 0xFF88CCFF);
                                context.fill(getX() + 24, getY() + 18, getX() + 28, getY() + 46, 0xFF88CCFF);
                                context.fill(getX() + 28, getY() + 22, getX() + 32, getY() + 42, 0xFF88CCFF);
                                context.fill(getX() + 32, getY() + 26, getX() + 36, getY() + 38, 0xFF88CCFF);
                                context.drawText(this.textRenderer, "MP4", getX() + 40, getY() + 28, 0xFFAADDFF, true);
                                String name = textures.get(finalI).fileName().replace(".mp4", "");
                                if (name.length() > 9) name = name.substring(0, 7) + "..";
                                context.drawText(this.textRenderer, name, getX() + 2, getY() + THUMBNAIL_SIZE - 12, 0xFFCCCCCC, true);
                            }
                            else {
                                super.renderWidget(context, mouseX, mouseY, delta);
                            }
                        }
                    }
                ,
                    (area, widget) -> {
                        widget.setX(area.x() + col * (THUMBNAIL_SIZE + PADDING));
                        widget.setY(area.y() + row * (THUMBNAIL_SIZE + PADDING));
                        widget.setWidth(THUMBNAIL_SIZE);
                        widget.setHeight(THUMBNAIL_SIZE);
                    }
            );
        }

        int offset = 80;
        parentContainer = ScreenLayoutContainerWidget.builder(startX - PADDING * 2, startY - PADDING * 2, gridWidth + PADDING * 4, this.height - offset + 20)
                .root()
                .splitVertical()
                .areaHolderOnly()

                .addArea("grid", this.height - offset - 30)
                .addWidgets(layoutMap)
                .end()

                .addArea("cancel", 20)
                .addWidget(new LabeledButtonWidget(0, 0, 0, 0, Text.empty(), Text.literal("Cancel"), button -> this.close()),
                        (area, widget) -> {
                            widget.setX(area.x() + area.width() / 2 - area.width() / 8);
                            widget.setY(area.y());
                            widget.setWidth(area.width() / 4);
                            widget.setHeight(20);
                        })
                .autoMinSizeFromWidgets()
                .end()

                .endRoot()
                .backgroundColor(0)
                .build();

        this.addDrawableChild(parentContainer);
        this.addSelectableChild(parentContainer);
    }
    
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {}
    
    private void loadTextures() {
        textures.clear();
        
        MinecraftClient client = MinecraftClient.getInstance();

        String path = "textures/graffiti";
        
        try {
            Map<Identifier, Resource> resources = client.getResourceManager().findResources(
                "textures/graffiti",
                id -> id.getPath().endsWith(".png") && id.getNamespace().equals(KarmaGateMod.MOD_ID)
            );
            
            for (Identifier resourceId : resources.keySet()) {
                String fullPath = resourceId.getPath();
                String fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                Identifier textureId = Identifier.of(KarmaGateMod.MOD_ID, fullPath);
                textures.add(new GraffitiTexture(fileName, textureId, false));
            }

            Map<Identifier, Resource> videoResources = client.getResourceManager().findResources(
                "textures/graffiti",
                id -> id.getPath().endsWith(".mp4") && id.getNamespace().equals(KarmaGateMod.MOD_ID)
            );

            for (Identifier resourceId : videoResources.keySet()) {
                String fullPath = resourceId.getPath();
                String fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                textures.add(new GraffitiTexture(fileName, null, true));
            }
            
            KarmaGateMod.LOGGER.info("Loaded {} graffiti textures ({} video)",
                textures.size(), videoResources.size());
            
        } catch (Exception e) {
            KarmaGateMod.LOGGER.error("Failed to load graffiti textures", e);
        }
    }
    
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xE0101010);
        
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
    
    private void selectTexture(GraffitiTexture texture) {
        if (onTextureSelected != null) {
            onTextureSelected.accept(texture.fileName());
            this.close();
            return;
        }

        SpawnGraffitiPayload payload = new SpawnGraffitiPayload(
            spawnX, spawnY, spawnZ,
            facing.getId(),
            texture.fileName()
        );

        ClientPlayNetworking.send(payload);

        KarmaGateMod.LOGGER.info("Selected graffiti texture: {}", texture.fileName());

        this.close();
    }
    
    @Override
    public boolean shouldPause() {
        return false;
    }

    private record GraffitiTexture(String fileName, Identifier textureId, boolean isVideo) {}
}

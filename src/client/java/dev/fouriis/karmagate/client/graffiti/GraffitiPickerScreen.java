package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.network.SpawnGraffitiPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A screen that displays all available graffiti textures in a grid.
 * When a texture is selected, it spawns the graffiti entity with that texture.
 */
public class GraffitiPickerScreen extends Screen {
    
    private static final int THUMBNAIL_SIZE = 64;
    private static final int PADDING = 8;
    private static final int COLUMNS = 6;
    
    // Pending spawn data (only used when placing new graffiti)
    private final double spawnX, spawnY, spawnZ;
    private final Direction facing;
    private final java.util.function.Consumer<String> onTextureSelected;
    
    // Available textures
    private final List<GraffitiTexture> textures = new ArrayList<>();
    
    // Scroll offset
    private int scrollOffset = 0;
    private int maxScroll = 0;
    
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
        
        // Load all graffiti textures
        loadTextures();
        
        // Calculate max scroll
        int rows = (textures.size() + COLUMNS - 1) / COLUMNS;
        int contentHeight = rows * (THUMBNAIL_SIZE + PADDING);
        int viewHeight = this.height - 60; // Account for title and padding
        maxScroll = Math.max(0, contentHeight - viewHeight);
        
        // Add cancel button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> {
            this.close();
        }).dimensions(this.width / 2 - 50, this.height - 30, 100, 20).build());
    }
    
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Don't render the default blurred background - we'll draw our own solid background in render()
    }
    
    private void loadTextures() {
        textures.clear();
        
        MinecraftClient client = MinecraftClient.getInstance();
        
        // Find all graffiti textures
        String path = "textures/graffiti";
        Identifier baseId = Identifier.of(KarmaGateMod.MOD_ID, path);
        
        try {
            // Get all resources matching the graffiti textures path
            Map<Identifier, Resource> resources = client.getResourceManager().findResources(
                "textures/graffiti",
                id -> id.getPath().endsWith(".png") && id.getNamespace().equals(KarmaGateMod.MOD_ID)
            );
            
            for (Identifier resourceId : resources.keySet()) {
                // Extract just the filename
                String fullPath = resourceId.getPath();
                String fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                
                // Create the texture identifier
                Identifier textureId = Identifier.of(KarmaGateMod.MOD_ID, fullPath);
                
                textures.add(new GraffitiTexture(fileName, textureId));
            }
            
            KarmaGateMod.LOGGER.info("Loaded {} graffiti textures", textures.size());
            
        } catch (Exception e) {
            KarmaGateMod.LOGGER.error("Failed to load graffiti textures", e);
        }
    }
    
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Draw solid dark background (no blur)
        context.fill(0, 0, this.width, this.height, 0xE0101010);
        
        // Draw title
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        
        // Calculate grid position
        int gridWidth = COLUMNS * (THUMBNAIL_SIZE + PADDING) - PADDING;
        int startX = (this.width - gridWidth) / 2;
        int startY = 30;
        
        // Enable scissor to clip scrolling content
        context.enableScissor(0, startY, this.width, this.height - 40);
        
        // Draw texture grid
        for (int i = 0; i < textures.size(); i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            
            int x = startX + col * (THUMBNAIL_SIZE + PADDING);
            int y = startY + row * (THUMBNAIL_SIZE + PADDING) - scrollOffset;
            
            // Skip if out of view
            if (y + THUMBNAIL_SIZE < startY || y > this.height - 40) {
                continue;
            }
            
            GraffitiTexture tex = textures.get(i);
            
            // Check if hovered
            boolean hovered = mouseX >= x && mouseX < x + THUMBNAIL_SIZE && 
                              mouseY >= y && mouseY < y + THUMBNAIL_SIZE &&
                              mouseY >= startY && mouseY < this.height - 40;
            
            // Draw background
            int bgColor = hovered ? 0xFF4080FF : 0xFF303030;
            context.fill(x - 2, y - 2, x + THUMBNAIL_SIZE + 2, y + THUMBNAIL_SIZE + 2, bgColor);
            
            // Draw texture
            context.drawTexture(tex.textureId(), x, y, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        }
        
        context.disableScissor();
        
        // Draw scrollbar if needed
        if (maxScroll > 0) {
            int scrollbarX = this.width - 10;
            int scrollbarHeight = this.height - 70;
            int scrollbarY = 30;
            
            // Background
            context.fill(scrollbarX, scrollbarY, scrollbarX + 6, scrollbarY + scrollbarHeight, 0xFF404040);
            
            // Handle
            int handleHeight = Math.max(20, (int)(scrollbarHeight * (float)scrollbarHeight / (scrollbarHeight + maxScroll)));
            int handleY = scrollbarY + (int)((scrollbarHeight - handleHeight) * (float)scrollOffset / maxScroll);
            context.fill(scrollbarX, handleY, scrollbarX + 6, handleY + handleHeight, 0xFFAAAAAA);
        }
        
        super.render(context, mouseX, mouseY, delta);
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Calculate grid position
            int gridWidth = COLUMNS * (THUMBNAIL_SIZE + PADDING) - PADDING;
            int startX = (this.width - gridWidth) / 2;
            int startY = 30;
            
            // Check if click is in grid area
            if (mouseY >= startY && mouseY < this.height - 40) {
                for (int i = 0; i < textures.size(); i++) {
                    int col = i % COLUMNS;
                    int row = i / COLUMNS;
                    
                    int x = startX + col * (THUMBNAIL_SIZE + PADDING);
                    int y = startY + row * (THUMBNAIL_SIZE + PADDING) - scrollOffset;
                    
                    if (mouseX >= x && mouseX < x + THUMBNAIL_SIZE && 
                        mouseY >= y && mouseY < y + THUMBNAIL_SIZE) {
                        
                        selectTexture(textures.get(i));
                        return true;
                    }
                }
            }
        }
        
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset -= (int)(verticalAmount * 20);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }
    
    private void selectTexture(GraffitiTexture texture) {
        if (onTextureSelected != null) {
            onTextureSelected.accept(texture.fileName());
            this.close();
            return;
        }

        // Send spawn packet to server
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
    
    /**
     * Represents a graffiti texture entry.
     */
    private record GraffitiTexture(String fileName, Identifier textureId) {}
}

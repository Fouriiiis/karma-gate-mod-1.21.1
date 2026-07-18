package dev.fouriis.karmagate.entity.oracle;

public enum OracleId {
    FIVE_PEBBLES("SS", "FivePebbles", 0xFF66CB, 0xF0A23A, 0x17121F),
    LOOKS_TO_THE_MOON("SL", "LooksToTheMoon", 0x1B4557, 0x7D9A8D, 0x20242B);

    private final String rainWorldId;
    private final String displayName;
    private final int skinColor;
    private final int robeColor;
    private final int armColor;

    OracleId(String rainWorldId, String displayName, int skinColor, int robeColor, int armColor) {
        this.rainWorldId = rainWorldId;
        this.displayName = displayName;
        this.skinColor = skinColor;
        this.robeColor = robeColor;
        this.armColor = armColor;
    }

    public String rainWorldId() {
        return rainWorldId;
    }

    public String displayName() {
        return displayName;
    }

    public int skinColor() {
        return skinColor;
    }

    public int robeColor() {
        return robeColor;
    }

    public int armColor() {
        return armColor;
    }
}

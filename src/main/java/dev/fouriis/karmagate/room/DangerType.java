package dev.fouriis.karmagate.room;

public enum DangerType {
    Rain,
    Flood,
    FloodAndRain,
    None,
    Thunder;

    public static DangerType fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return None;
        }
        for (DangerType type : values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return None;
    }
}

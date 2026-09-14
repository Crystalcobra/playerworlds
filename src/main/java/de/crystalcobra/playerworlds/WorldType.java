package de.crystalcobra.playerworlds;

import java.util.Locale;

public enum WorldType {
    NORMAL,
    VANILLA,
    FLAT;

    public static WorldType byName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}

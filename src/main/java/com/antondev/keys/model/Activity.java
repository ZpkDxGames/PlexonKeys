package com.antondev.keys.model;

import java.util.Locale;

public enum Activity {
    MINING, LOGGING, FISHING, MOBS;

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public static Activity parse(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
}

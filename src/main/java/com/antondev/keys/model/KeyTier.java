package com.antondev.keys.model;

import java.util.Locale;

public enum KeyTier {
    BASIC, RARE, EPIC, LEGENDARY;

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public static KeyTier parse(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
}

package com.antondev.keys.api;

import java.util.Locale;

/** Stable machine-readable origin for public key grants. */
public enum KeySource {
    ACTIVITY,
    ADMIN,
    API,
    VOTE,
    QUEST,
    CRATE,
    DAILY_REWARD,
    OTHER;

    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

package com.antondev.keys.api;

import com.antondev.keys.model.Activity;
import java.util.Map;
import java.util.Objects;

/** Immutable public view of one stable PlexonKeys definition. */
public record KeyDefinitionView(
        String id,
        String displayName,
        String permission,
        boolean enabled,
        boolean visible,
        boolean claimable,
        String physicalMode,
        String crateMapping,
        Map<Activity, Double> acquisitionChances) {
    public KeyDefinitionView {
        id = requireText(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        permission = Objects.requireNonNull(permission, "permission");
        physicalMode = requireText(physicalMode, "physicalMode");
        crateMapping = Objects.requireNonNull(crateMapping, "crateMapping");
        acquisitionChances = Map.copyOf(Objects.requireNonNull(acquisitionChances, "acquisitionChances"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}

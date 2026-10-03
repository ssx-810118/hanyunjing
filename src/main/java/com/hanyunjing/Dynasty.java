package com.hanyunjing;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** The supported catalogue dynasties, in the order shared by every client and agent. */
public enum Dynasty {
    HAN("汉"), TANG("唐"), SONG("宋"), YUAN("元"), MING("明");

    private final String label;
    private static final List<String> LABELS = Arrays.stream(values()).map(Dynasty::label).toList();
    private static final Pattern EXPLICIT = Pattern.compile("(" + String.join("|", LABELS) + ")(?:制|风|代|朝)");

    Dynasty(String label) { this.label = label; }
    public String label() { return label; }
    public static List<String> labels() { return LABELS; }
    public static boolean supports(String value) { return LABELS.contains(value); }

    public static String filter(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!supports(normalized)) throw new IllegalArgumentException("朝代须为" + String.join("、", LABELS));
        return normalized;
    }

    /** A generic request for 汉服 does not imply the Han dynasty; the last explicit change wins. */
    public static Optional<String> explicitIn(String text) {
        if (text == null) return Optional.empty();
        if (supports(text.trim())) return Optional.of(text.trim());
        var matcher = EXPLICIT.matcher(text);
        String found = null;
        while (matcher.find()) found = matcher.group(1);
        return Optional.ofNullable(found);
    }
}

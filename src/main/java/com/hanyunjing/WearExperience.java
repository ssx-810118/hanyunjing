package com.hanyunjing;

import java.util.regex.Pattern;

/** Interprets explicit wear-experience evidence; never selects products. */
final class WearExperience {
    private WearExperience() {}
    private static final String FIRST = "(?:第一次|首次|初次|初穿)";
    private static final Pattern NEGATIVE = Pattern.compile("(?:不是|并非|不算|非)\\s*" + FIRST);
    private static final Pattern NEVER_WORN = Pattern.compile("(?:从未|未曾|从来没有|从来没|没有|没)\\s*穿过");
    private static final Pattern POSITIVE = Pattern.compile(FIRST);

    static Boolean firstWearIn(String text) {
        if (text == null || text.isBlank()) return null;
        if (NEGATIVE.matcher(text).find()) return false;
        if (NEVER_WORN.matcher(text).find() || POSITIVE.matcher(text).find()) return true;
        return text.contains("穿过") ? false : null;
    }
}

package com.verdant.catalog.mapping;

import com.ibm.icu.text.Transliterator;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class TitleNormalizer {

    private static final Transliterator DEVANAGARI_TO_LATIN =
        Transliterator.getInstance("Devanagari-Latin; Latin-ASCII");

    private static final List<Pattern> NOISE = List.of(
        Pattern.compile("\\b(free|fast|express)\\s+(delivery|shipping)\\b"),
        Pattern.compile("\\b(best|top|premium|superior|quality|original)\\b"),
        Pattern.compile("\\b(buy|order|now|offer|sale|discount|combo)\\b"),
        Pattern.compile("\\b(indoor|outdoor|home|office|garden)\\s+(plant|decor)\\b"),
        Pattern.compile("\\bwith\\s+(free\\s+)?pot\\b"),
        Pattern.compile("[!*#@]+")
    );

    private static final Pattern SIZE =
        Pattern.compile("\\b(\\d+)\\s*(inch|inches|in|cm|feet|ft)\\b");

    public Normalized normalize(String raw) {
        if (raw == null || raw.isBlank()) return new Normalized("", null);

        String s = raw;
        if (containsDevanagari(s)) s = DEVANAGARI_TO_LATIN.transliterate(s);

        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
                      .replaceAll("\\p{M}", "")
                      .toLowerCase();

        Integer sizeInches = extractSizeInches(s);
        s = SIZE.matcher(s).replaceAll(" ");

        for (Pattern p : NOISE) s = p.matcher(s).replaceAll(" ");

        s = s.replaceAll("[^a-z0-9 ]", " ")
             .replaceAll("\\s+", " ")
             .trim();

        return new Normalized(s, sizeInches);
    }

    private boolean containsDevanagari(String s) {
        return s.codePoints().anyMatch(cp ->
            Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.DEVANAGARI);
    }

    private Integer extractSizeInches(String s) {
        var m = SIZE.matcher(s);
        if (!m.find()) return null;
        int value = Integer.parseInt(m.group(1));
        return switch (m.group(2)) {
            case "cm" -> Math.round(value / 2.54f);
            case "feet", "ft" -> value * 12;
            default -> value;
        };
    }

    public record Normalized(String text, Integer sizeInches) { }
}

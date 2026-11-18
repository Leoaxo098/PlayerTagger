package net.playertagger.render;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central registry mapping human-readable color names (as stored in
 * {@code config/playertagger.json}) to the ARGB integers the renderer needs.
 *
 * <p>Keeping this in one small, well-documented class is what makes the
 * "each tagged player has their own outline color" requirement easy to extend
 * later — for example wiring up a color picker GUI just means calling
 * {@link #isValidColor(String)} and {@link #resolve(String)} against
 * whatever the user picks, without touching the renderer at all.</p>
 */
public final class GlowColorManager {

    /**
     * Named palette, in assignment order. New tagged players are handed the
     * next color in this list (wrapping around) so that, out of the box,
     * every tag is visually distinct without the user having to think about
     * it.
     */
    private static final Map<String, Integer> PALETTE = new LinkedHashMap<>();

    static {
        PALETTE.put("yellow", 0xFFFF33);
        PALETTE.put("aqua", 0x33FFFF);
        PALETTE.put("light_purple", 0xFF33FF);
        PALETTE.put("green", 0x33FF55);
        PALETTE.put("red", 0xFF3030);
        PALETTE.put("gold", 0xFFAA22);
        PALETTE.put("blue", 0x6677FF);
        PALETTE.put("white", 0xFFFFFF);
        PALETTE.put("dark_green", 0x00E050);
        PALETTE.put("dark_purple", 0xCE40FF);
    }

    private static final String DEFAULT_COLOR = "yellow";

    private GlowColorManager() {
    }

    /**
     * Returns the name of the color that should be assigned to the
     * {@code index}-th tagged player (0-based), cycling through the
     * palette so consecutive tags are visually distinct.
     */
    public static String defaultColorForIndex(int index) {
        if (PALETTE.isEmpty()) {
            return DEFAULT_COLOR;
        }
        String[] names = PALETTE.keySet().toArray(new String[0]);
        return names[Math.floorMod(index, names.length)];
    }

    /**
     * Resolves a stored color name to an ARGB (opaque) integer suitable for
     * the outline renderer. Falls back to the default
     * color for unrecognized names rather than throwing, since a config
     * hand-edited with a typo shouldn't break rendering.
     */
    public static int resolve(String colorName) {
        Integer rgb = PALETTE.get(colorName == null ? null : colorName.toLowerCase(java.util.Locale.ROOT));
        int base = rgb != null ? rgb : PALETTE.get(DEFAULT_COLOR);
        return 0xFF000000 | base;
    }

    public static boolean isValidColor(String colorName) {
        return colorName != null && PALETTE.containsKey(colorName.toLowerCase(java.util.Locale.ROOT));
    }

    public static Iterable<String> availableColorNames() {
        return PALETTE.keySet();
    }
}

package net.playertagger.render;

public final class GlowColorManagerTest {
    public static void main(String[] args) {
        check("dark_green", 0xFF00E050);
        check("dark_purple", 0xFFCE40FF);
        check("red", 0xFFFF3030);
        if (GlowColorManager.resolve("DARK_GREEN") != GlowColorManager.resolve("dark_green")) {
            throw new AssertionError("Saved color names must remain case insensitive");
        }
    }

    private static void check(String name, int expected) {
        if (!GlowColorManager.isValidColor(name) || GlowColorManager.resolve(name) != expected) {
            throw new AssertionError("Unexpected color for " + name);
        }
    }
}

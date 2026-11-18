package net.playertagger.render;

public final class OutlineVisibilityTest {
    public static void main(String[] args) {
        check(true, true, false, true);
        check(false, true, false, false);
        check(true, false, false, false);
        check(true, true, true, false);
    }

    private static void check(boolean enabled, boolean tagged, boolean invisible, boolean expected) {
        if (OutlineVisibility.shouldShow(enabled, tagged, invisible) != expected) {
            throw new AssertionError("Unexpected outline visibility");
        }
    }
}

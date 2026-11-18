package net.playertagger.render;

/** Eligibility for PlayerTagger's own outline. */
public final class OutlineVisibility {
    private OutlineVisibility() { }

    public static boolean shouldShow(boolean enabled, boolean tagged, boolean invisible) {
        return enabled && tagged && !invisible;
    }
}

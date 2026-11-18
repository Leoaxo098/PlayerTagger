package net.playertagger.render;

/** Data carried from entity extraction to the render phase. Zero means no outline. */
public interface PlayerOutlineState {
    int playertagger$getOutlineColor();
    void playertagger$setOutlineColor(int color);
}

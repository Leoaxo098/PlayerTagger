#version 330

uniform sampler2D Mask;
uniform sampler2D MaskDepth;
uniform sampler2D SceneDepth;
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 lastPixel = textureSize(Mask, 0) - 1;
    vec4 center = texelFetch(Mask, pixel, 0);

    if (center.a > 0.0) {
        // Color just inside the silhouette nearby. Fill it as the player recedes.
        float fill = (center.a * 255.0 - 1.0) / 254.0;
        float edge = 0.0;
        if (fill < 1.0) {
            for (int y = -1; y <= 1; ++y) {
                for (int x = -1; x <= 1; ++x) {
                    ivec2 neighbor = clamp(pixel + ivec2(x, y), ivec2(0), lastPixel);
                    if (texelFetch(Mask, neighbor, 0).a == 0.0) edge = 1.0;
                }
            }
        }
        fragColor = vec4(center.rgb, max(fill, edge));
        return;
    }

    float sceneDepth = texelFetch(SceneDepth, pixel, 0).r;
    float closest = 10.0;
    vec4 rim = vec4(0.0);
    for (int y = -3; y <= 3; ++y) {
        for (int x = -3; x <= 3; ++x) {
            float distanceSquared = float(x * x + y * y);
            if (distanceSquared == 0.0 || distanceSquared >= closest) continue;
            ivec2 neighbor = clamp(pixel + ivec2(x, y), ivec2(0), lastPixel);
            vec4 sampleColor = texelFetch(Mask, neighbor, 0);
            if (sampleColor.a == 0.0) continue;
            float colorRadius = 1.5 * (sampleColor.a * 255.0 - 1.0) / 254.0;
            float whiteRadius = colorRadius + 1.5;
            if (distanceSquared > whiteRadius * whiteRadius) continue;

            // A visible source pixel still cannot spread onto closer terrain.
            if (texelFetch(MaskDepth, neighbor, 0).r > sceneDepth) continue;
            closest = distanceSquared;
            rim = distanceSquared <= colorRadius * colorRadius
                    ? vec4(sampleColor.rgb, 1.0) : vec4(1.0);
        }
    }
    if (rim.a == 0.0) discard;
    fragColor = rim;
}

#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;
uniform sampler2D ProbeSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

/** Blobs of ink. Eight at full size cover about 45% of a 16:9 screen once the overlaps are counted. */
const int BLOBS = 8;
/** The pixel-art grid, in screen pixels: every edge is decided once per 4x4 block. */
const float GRID = 4.0;
/** A blob's radius at amount 255, in screen heights. */
const float MAX_RADIUS = 0.19;
/** No ink within this of the middle of the screen, in screen heights: the reticle stays readable. */
const float CLEAR = 0.09;
/** The lighter edge of a blob, in screen heights — about one grid cell. */
const float RIM = 0.007;
/** The two team inks, #BD3754 and #8A57BD, indexed by the team bit in the data pixel. */
const vec3 DATA_INK = vec3(0.7412, 0.2157, 0.3294);
const vec3 IT_INK = vec3(0.5412, 0.3412, 0.7412);

float hash(float n) {
    return fract(sin(n * 127.1 + 311.7) * 43758.5453);
}

void main() {
    vec3 frame = texture(InSampler, texCoord).rgb;
    vec4 probe = texture(ProbeSampler, vec2(0.5));
    float amount = probe.r;
    if (amount <= 0.0) {
        fragColor = vec4(frame, 1.0);
        return;
    }
    // The probe's green is the enemy team's index, and nothing else: the LED's position and size used to
    // ride in the same byte so this pass could paint over it, and there is nothing left to paint over —
    // the LED sits under the hotbar now, and the hotbar is drawn after this pass.
    vec3 ink = floor(probe.g * 255.0 + 0.5) < 0.5 ? DATA_INK : IT_INK;
    float aspect = ScreenSize.x / ScreenSize.y;
    // Pixel art: the whole decision is taken at the centre of a 4x4 block of screen pixels, so every
    // edge — blob, rim and cover — comes out stepped rather than smooth. Units below are screen heights
    // from the middle of the screen, so the ink is the same size on any window.
    vec2 pixel = (floor(gl_FragCoord.xy / GRID) + 0.5) * GRID;
    vec2 p = (pixel / ScreenSize - 0.5) * vec2(aspect, 1.0);
    float t = GameTime * 1200.0;

    float d = 1.0;
    for (int i = 0; i < BLOBS; i++) {
        float fi = float(i);
        // Fixed pseudo-random places, so the ink sits still on the screen and only grows and drips.
        vec2 c = vec2((hash(fi) - 0.5) * 0.9 * aspect, (hash(fi + 13.0) - 0.5) * 0.9);
        float r = MAX_RADIUS * pow(amount, 0.7) * (0.7 + 0.6 * hash(fi + 29.0));
        // The heavier the ink, the further the blob has run down the glass.
        c.y -= 0.05 * amount;
        vec2 q = p - c;
        // A drip rather than a dot: the underside stretches, and the stretch breathes so it reads as
        // running paint. Screen-space down is -y.
        float tail = 0.35 + 0.25 * sin(t * 0.35 + fi);
        if (q.y < 0.0) q.y /= 1.0 + tail;
        float angle = atan(q.y, q.x);
        r *= 1.0 + 0.08 * sin(angle * 5.0 + t * 0.7 + fi * 2.0) + 0.05 * sin(angle * 9.0 - t * 0.5 + fi);
        d = min(d, length(q) - r);
    }
    // The middle of the screen is where the player is aiming: ink there is a blindfold, not a nuisance.
    if (length(p) < CLEAR) d = 1.0;

    if (d < 0.0) {
        fragColor = vec4(d > -RIM ? mix(ink, vec3(1.0), 0.35) : ink, 1.0);
    } else {
        fragColor = vec4(frame, 1.0);
    }
}

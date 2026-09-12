#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;
uniform sampler2D ProbeSampler;
uniform sampler2D Ink1Sampler;
uniform sampler2D Ink2Sampler;
uniform sampler2D Ink3Sampler;
uniform sampler2D Ink4Sampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Ink on the glass, drawn from four overlay textures an artist paints rather than from a field of
// signed-distance blobs. Two flat tones and a procedural shape had no depth and did not read as pixel
// art; a drawing does, and a drawing can be edited without touching a shader.
//
// The contract with the textures (see textures/effect/README.md): 320x180 RGBA, alpha is coverage and is
// either 0 or 255, RGB is a greyscale SHADING map. This pass maps that luminance to four tones of the
// team's colour with hard steps, so one drawing serves both teams, and samples at texel centres with no
// filtering at all, so every texel comes out as a fat block of screen pixels — the texture is the pixel
// grid, which is why the pass no longer snaps to a screen-pixel grid of its own.
//
// State 1 is a little ink at the edges and state 4 is nearly covered; the amount byte picks between
// them in quarters.

/** The overlays' own size. Sampling is snapped to this grid, so it is the pixel art's resolution. */
const vec2 SHEET = vec2(320.0, 180.0);
/** No ink within this of the middle of the screen, in screen heights: the reticle stays readable. */
const float CLEAR = 0.09;
/** The two team inks, #BD3754 and #8A57BD, indexed by the team byte in the data pixel. */
const vec3 DATA_INK = vec3(0.7412, 0.2157, 0.3294);
const vec3 IT_INK = vec3(0.5412, 0.3412, 0.7412);
/** The four tones, as steps on the overlay's luminance. */
const float TONE_SHADOW = 0.3;
const float TONE_BASE = 0.6;
const float TONE_LIGHT = 0.85;

/** The overlay for a state, sampled at the centre of the texel under {@code uv}. */
vec4 overlay(int state, vec2 uv) {
    // Snap to texel centres: NEAREST is asked for in the chain too (bilinear false), and this makes the
    // pass correct whatever the sampler is set to. The overlay is stretched across the whole screen, so
    // on a window that is not 16:9 the texels come out as rectangles rather than squares — which is the
    // right trade for ink that has to reach every edge.
    vec2 at = (floor(uv * SHEET) + 0.5) / SHEET;
    if (state <= 1) return texture(Ink1Sampler, at);
    if (state == 2) return texture(Ink2Sampler, at);
    if (state == 3) return texture(Ink3Sampler, at);
    return texture(Ink4Sampler, at);
}

void main() {
    vec3 frame = texture(InSampler, texCoord).rgb;
    vec4 probe = texture(ProbeSampler, vec2(0.5));
    float amount = probe.r;
    if (amount <= 0.0) {
        fragColor = vec4(frame, 1.0);
        return;
    }
    // The probe's green is the enemy team's index, and nothing else: the LED's position used to ride in
    // the same byte so this pass could paint over it, and there is nothing left to paint over — the LED
    // sits under the hotbar now, and the hotbar is drawn after this pass.
    vec3 ink = floor(probe.g * 255.0 + 0.5) < 0.5 ? DATA_INK : IT_INK;
    // 1..255 in quarters: 1-63, 64-127, 128-191, 192-255.
    int state = int(min(4.0, floor(amount * 255.0 / 64.0) + 1.0));

    vec4 sheet = overlay(state, texCoord);
    if (sheet.a < 0.5) {
        fragColor = vec4(frame, 1.0);
        return;
    }
    // The middle of the screen is where the player is aiming: ink there is a blindfold, not a nuisance.
    // The overlays keep their own clear island, so this is a guard against one that does not.
    float aspect = ScreenSize.x / ScreenSize.y;
    vec2 p = (texCoord - 0.5) * vec2(aspect, 1.0);
    if (length(p) < CLEAR) {
        fragColor = vec4(frame, 1.0);
        return;
    }
    // Four tones of the team's colour, with hard steps. The overlay's grey says which: shadow, base,
    // light, highlight. Nothing is interpolated — that is the whole of the pixel-art look.
    float lum = dot(sheet.rgb, vec3(0.299, 0.587, 0.114));
    vec3 tone = lum < TONE_SHADOW ? ink * 0.55
            : lum < TONE_BASE ? ink
            : lum < TONE_LIGHT ? mix(ink, vec3(1.0), 0.25)
            : mix(ink, vec3(1.0), 0.6);
    fragColor = vec4(tone, 1.0);
}

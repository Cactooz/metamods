#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// The ink meter's data pixel. There is no way for a server to speak to a post effect, so it writes the
// number into the frame instead — and it has to be in the frame before the effect runs. 26.3's
// GameRenderer.render does renderLevel(), then applyPostEffects(), and only then GuiRenderer.render(),
// so nothing on the HUD is on this target; the held item is, drawn inside renderLevel by
// renderItemInHand. So every paint weapon carries a one-model-pixel data LED, the item shader hands its
// custom_model_data tint to the frame unlit and exact, and this pass goes looking for it: red at full
// with green under 16, which nothing in a rendered world is for two samples in a row.
//
// Where it looks is the point of round 7. The LED no longer sits on the gun: each weapon's element is
// solved so that its own firstperson_righthand transform lands it dead centre horizontally and a hair
// above the bottom edge — inside the hotbar, which the GUI draws after this pass has read the frame. So
// the probe stops sweeping the lower half of the screen and reads the one box the LED can be in, which
// is both cheaper and impossible to confuse with a red block on the floor to the shooter's left.
//
// This pass renders to a 1x1 target, so everything below runs once per frame for the whole screen rather
// than once per pixel.

/** The box the LED lands in: the bottom 8% of the height, the middle 30% of the width. */
const float BAND = 0.08;
const float MIDDLE = 0.30;
/**
 * Sampling step as a share of the screen height. The LED comes out about 1.9% of the height tall and
 * 0.86% of the width across at 16:9 — 20 px by 16 px at 1920x1080 — and the step is taken on the height
 * and used on both axes, so at 0.6% it is 6 px and cannot step over the LED in either direction: two
 * columns and three rows land inside it at the worst alignment. The whole search is 96 x 14 = 1344
 * samples for the frame (one fetch each; the confirmation fetch only happens once something matches).
 */
const float STEP_SHARE = 0.006;
/** The confirmation sample's distance, a share of the height: well inside the LED, whatever the step is. */
const float CONFIRM_SHARE = 0.004;

/** The signature: red at full, green below 16 (green carries the team index in its low nibble). */
bool marker(vec2 pixel, out vec3 data) {
    vec4 frame = texture(InSampler, pixel / InSize);
    data = frame.rgb;
    return frame.r > 0.99 && frame.g < 0.0627;
}

/** A second sample carrying the same amount: a lone red pixel in the world is not a flat run of them. */
bool confirms(vec2 at, float hop, vec3 here) {
    vec3 there;
    if (!marker(at + vec2(hop, 0.0), there)) return false;
    return abs(there.b - here.b) <= 0.002;
}

void main() {
    float stride = max(2.0, floor(InSize.y * STEP_SHARE));
    // The second sample is a fixed short hop, not a whole step: the step may be wider than the LED. It is
    // tried both ways, because a scan column can land near either edge of the LED and a one-sided hop
    // would then fall outside it and lose the reading for the whole frame.
    float confirm = max(2.0, floor(InSize.y * CONFIRM_SHARE));
    float left = InSize.x * (0.5 - MIDDLE * 0.5);
    float right = InSize.x * (0.5 + MIDDLE * 0.5);
    for (float y = stride * 0.5; y < InSize.y * BAND; y += stride) {
        for (float x = left + stride * 0.5; x < right; x += stride) {
            vec2 at = vec2(x, y);
            vec3 here;
            if (!marker(at, here)) continue;
            if (!confirms(at, confirm, here) && !confirms(at, -confirm, here)) continue;
            // amount, and the team index the ink pass picks its colour by. Where the LED is no longer
            // matters to anyone: the ink pass has nothing to cover, because the hotbar covers it.
            fragColor = vec4(here.b, here.g, 0.0, 1.0);
            return;
        }
    }
    // No weapon in view, or an idle LED (which the item shader discards outright): no ink, and the ink
    // pass hands the frame straight through.
    fragColor = vec4(0.0);
}

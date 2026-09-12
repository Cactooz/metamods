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
// This pass renders to a 1x1 target, so everything below runs once per frame for the whole screen rather
// than once per pixel.

/** The band the first-person item lands in: the bottom 40% of the screen, either hand's side. */
const float BAND = 0.40;
/**
 * Sampling step as a share of the screen height. The LED is one model pixel of an item held at about a
 * quarter of the screen's height, so it covers some 1.7% of the height — a step of 1% cannot step over
 * it, and at 1920x1080 it puts the whole search at 192 x 43 = 8256 samples for the frame (one fetch
 * each; the confirmation fetch and the footprint walk only happen once something matches).
 */
const float STEP_SHARE = 0.01;
/** The confirmation sample's distance, a share of the height: well inside the LED, whatever the step is. */
const float CONFIRM_SHARE = 0.004;
/** How far the footprint measurement walks, in steps. */
const int REACH = 24;

/** The signature: red at full, green below 16 (green carries the team index in its low nibble). */
bool marker(vec2 pixel, out vec3 data) {
    vec4 frame = texture(InSampler, pixel / InSize);
    data = frame.rgb;
    return frame.r > 0.99 && frame.g < 0.0627;
}

bool marker(vec2 pixel) {
    vec3 ignored;
    return marker(pixel, ignored);
}

/** How far the signature holds from {@code at} along {@code stride}, in pixels. */
float reach(vec2 at, vec2 stride) {
    float far = 0.0;
    for (int i = 1; i <= REACH; i++) {
        if (!marker(at + stride * float(i))) break;
        far = length(stride) * float(i);
    }
    return far;
}

void main() {
    float stride = max(4.0, floor(InSize.y * STEP_SHARE));
    // The second sample is a fixed short hop, not a whole step: the step may be wider than the LED.
    float confirm = max(2.0, floor(InSize.y * CONFIRM_SHARE));
    for (float y = stride * 0.5; y < InSize.y * BAND; y += stride) {
        for (float x = stride * 0.5; x < InSize.x; x += stride) {
            vec2 at = vec2(x, y);
            vec3 here, right;
            if (!marker(at, here)) continue;
            // Two samples a short hop apart, carrying the same amount: a lone red pixel in the world (a
            // redstone block, a lava fleck, someone's red skin) is not a flat run of them.
            if (!marker(at + vec2(confirm, 0.0), right)) continue;
            if (abs(right.b - here.b) > 0.002) continue;
            // Measure the LED's footprint by walking out until the signature stops, so the ink pass can
            // cover exactly as much as it has to and no more.
            // Walked at the confirmation distance, so the reach scales with the screen the LED does.
            float walk = confirm;
            float left = reach(at, vec2(-walk, 0.0));
            float east = reach(at, vec2(walk, 0.0));
            float down = reach(at, vec2(0.0, -walk));
            float up = reach(at, vec2(0.0, walk));
            vec2 centre = at + vec2(east - left, up - down) * 0.5;
            float halfExtent = max(max(left + east, down + up) * 0.5, 1.0);
            // amount, the team plus the half extent (the team needs one bit, so it rides the low one),
            // and where the LED is as a share of the frame.
            fragColor = vec4(here.b, (floor(min(halfExtent, 126.0)) * 2.0 + floor(here.g * 255.0 + 0.5)) / 255.0,
                    centre.x / InSize.x, centre.y / InSize.y);
            return;
        }
    }
    // No weapon in view: no ink, and the ink pass hands the frame straight through.
    fragColor = vec4(0.0);
}

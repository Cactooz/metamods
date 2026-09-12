#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// The ink meter's data pixel. There is no way for a server to speak to a post effect, so it writes the
// number into the frame instead: every player with ink on their screen is kept on a one-glyph title
// whose colour is (255, team, amount). Vanilla draws a title centred, scaled four times, ten text
// pixels above the middle of the screen, so whatever the GUI scale is, the glyph is within ~24 px of
// the centre column and within 255 px of the centre row, and it is at least eight pixels across
// (2 glyph px x 4 title scale x 1 GUI scale).
//
// This pass renders to a 1x1 target, so the search below runs once per frame for the whole screen
// rather than once per pixel; the ink pass then reads the four bytes it writes here.
const float SPAN_X = 24.0;
const float SPAN_Y = 255.0;
/** Sampling step. Half the narrowest the glyph can be, so it cannot fall between two samples. */
const float STEP = 4.0;

/** The signature: red at full, green below 16 (green carries the team index in its low nibble). */
bool marker(vec2 pixel, out vec3 data) {
    vec4 frame = texture(InSampler, pixel / ScreenSize);
    data = frame.rgb;
    return frame.r > 0.99 && frame.g < 0.0627;
}

void main() {
    vec2 centre = ScreenSize * 0.5;
    for (float dy = -SPAN_Y; dy <= SPAN_Y; dy += STEP) {
        for (float dx = -SPAN_X; dx <= SPAN_X; dx += STEP) {
            vec3 here, right;
            if (!marker(centre + vec2(dx, dy), here)) continue;
            // Two samples a step apart, carrying the same amount: a lone red pixel in the world (a
            // redstone block, a lava fleck, someone's red skin) is not eight pixels of flat colour.
            if (!marker(centre + vec2(dx + STEP, dy), right)) continue;
            if (abs(right.b - here.b) > 0.002) continue;
            // amount, team, and where the marker is so the ink pass can paint over it: x to the pixel
            // (it is never more than 128 px off the centre column), y to two pixels.
            fragColor = vec4(here.b, here.g, (dx + 128.0) / 255.0, (dy * 0.5 + 127.5) / 255.0);
            return;
        }
    }
    // Nothing on screen: no ink, and the ink pass hands the frame straight through.
    fragColor = vec4(0.0);
}

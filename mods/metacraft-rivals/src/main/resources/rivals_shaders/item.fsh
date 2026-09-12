#version 330
#extension GL_ARB_separate_shader_objects : require

// RIVALS: vanilla takes globals only under GLINT (for GlintAlpha); the paint gloss wants GameTime in
// every variant, so the include is unconditional here. The Globals block is bound for every ITEM
// pipeline either way: ITEM_SNIPPET is built on MATRICES_FOG_LIGHT_DIR_SNIPPET → GLOBALS_SNIPPET.
#include <minecraft:globals.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif
layout(location = 2) in vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec4 overlayColor;
#endif
layout(location = 5) in vec2 texCoord0;
#ifdef GLINT
layout(location = 6) in vec2 texCoordGlint;
#endif
layout(location = 7) in vec3 viewPos;
layout(location = 8) in vec3 paintPos;
layout(location = 9) in vec4 rawColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

/** Texels to a block: the grid the paint's border, wobble and highlights are all snapped to. */
const float TEXELS = 16.0;
/** Wobble amplitude, in blocks. Above 1/TEXELS, or it cannot move the quantised edge at all. */
const float WOBBLE = 0.07;

#ifndef OIT_ALPHA_ONLY
vec4 calculateFinalColor(vec4 color) {
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);// Glint color modulator?
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}
#endif

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    vec4 color = tex;
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    color *= vertexColor * ColorModulator;

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    // RIVALS_LED: the ink meter's data pixel. The one texture in either atlas at alpha 246/255 = 0.9647 is
    // the mod's own data_led sprite, on a one-pixel cube on every paint weapon, and the window here admits
    // only 245..247 — the three values no texel of any blocks- or items-atlas texture reaches at any mip
    // level 0..4 (244 and 248 are both reachable: burning fire, a jungle door top). Its colour is the
    // custom_model_data tint, handed to the frame unlit and unmodulated so the post effect reads back the
    // exact bytes the server wrote. Before the gloss and before the lighting, and it never discards.
    if (abs(tex.a - 0.9647) < 0.006) {
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, 1.0);
        #else
        fragColor = vec4(rawColor.rgb, 1.0);
        #endif
        return;
    }

    // RIVALS_GLOSS: the same block terrain.fsh carries, for the paint that is a block display rather
    // than a block — stairs, slabs, fences, panes. Both display kinds draw block models through
    // Sheets.cutoutBlockItemSheet(), which is RenderPipelines.ITEM_CUTOUT, which is this pair; the
    // paint texels carry alpha 235/255 = 0.9216 as their marker, and no texture in either atlas the
    // item pipelines draw (blocks and items) has an alpha anywhere in the 233..237 the window admits at
    // mip level 0, so held items, dropped items and the inventory come through untouched. Known limit: at
    // mip level 3 a handful of textures do average to 236 (fire_0/1, soul_fire_0/1, tall_grass_bottom,
    // sniffer_egg and the two nautilus spawn eggs), so a few texels of those, seen small enough to reach
    // that level, take the paint's border test; they decode bits 12 from their red channel and may
    // discard. Narrowing the window would cost the filtering tolerance the marker needs. The in-plane coordinate
    // is the world one (paintPos), not a model one, so a quad's border lines up with the paint block
    // next to it. Derivatives are only defined in uniform control flow: take them before the branch.
    vec3 paintDx = dFdx(paintPos), paintDy = dFdy(paintPos);
    vec3 viewDx = dFdx(viewPos), viewDy = dFdy(viewPos);
    if (abs(tex.a - 0.9216) < 0.008) {
        // Connected paint (spec §5). The texel is one flat colour whose red low nibble says which of the
        // cell's four in-plane neighbours are painted; the face normal (from paintPos) picks the two
        // in-plane axes, and a rounded box — full on connected sides, inset and rounded on the others —
        // decides whether this fragment is paint at all. Inside it: a meniscus rim from the same distance,
        // a three-wave crawling normal, and glint / sheen / fresnel that mix toward light instead of adding.
        int bits = int(mod(floor(tex.r * 255.0 + 0.5), 16.0));
        vec3 nc = abs(normalize(cross(paintDx, paintDy)));
        vec2 p;
        vec2 along;
        if (nc.y >= nc.x && nc.y >= nc.z) { p = paintPos.xz; }
        else if (nc.x >= nc.z) { p = paintPos.zy; }
        else { p = paintPos.xy; }
        along = p;
        p = fract(p);
        // Pixel art, not vector art: everything the border and the look are computed from is snapped to
        // the centre of its 1/16-block texel, so the discard decision is taken once per texel (corners
        // come out stepped rather than smooth), the wobble moves in whole-texel steps, and the
        // highlights are blocky too. Time stays continuous — texels flip, they never slide.
        p = (floor(p * TEXELS) + 0.5) / TEXELS;
        along = (floor(along * TEXELS) + 0.5) / TEXELS;
        vec3 gridPos = (floor(paintPos * TEXELS) + 0.5) / TEXELS;
        float t = GameTime * 1200.0;
        bool negU = (bits & 1) != 0, posU = (bits & 2) != 0, negV = (bits & 4) != 0, posV = (bits & 8) != 0;
        // Inset each unconnected side by 0.06 plus a slow wobble; connected sides run out past the cell.
        float w0 = WOBBLE * sin(along.y * 12.0 + t * 1.1), w1 = WOBBLE * sin(along.y * 12.0 + 2.0 + t * 0.9);
        float w2 = WOBBLE * sin(along.x * 12.0 + 4.0 + t * 1.3), w3 = WOBBLE * sin(along.x * 12.0 + 1.0 + t * 0.8);
        float lo_u = negU ? -1.0 : 0.06 + w0, hi_u = posU ? 2.0 : 0.94 + w1;
        float lo_v = negV ? -1.0 : 0.06 + w2, hi_v = posV ? 2.0 : 0.94 + w3;
        vec2 centre = vec2(lo_u + hi_u, lo_v + hi_v) * 0.5;
        vec2 halfSize = vec2(hi_u - lo_u, hi_v - lo_v) * 0.5;
        // Corner radius only where both sides meeting at that corner are unconnected.
        bool cu = p.x < centre.x ? !negU : !posU;
        bool cv = p.y < centre.y ? !negV : !posV;
        float r = (cu && cv) ? 0.28 : 0.0;
        vec2 q = abs(p - centre) - halfSize + r;
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        if (d > 0.0) discard;
        vec3 n = normalize(cross(viewDx, viewDy));
        vec3 v = normalize(-viewPos);
        vec3 l = normalize(vec3(0.3 + 0.15 * sin(t), 0.8, 0.5 + 0.15 * cos(t)));
        vec3 wave = vec3(
            sin(gridPos.x * 6.0 + t * 0.9) * 0.4 + sin((gridPos.x + gridPos.z) * 11.0 + t * 1.4) * 0.35 + sin(gridPos.z * 17.0 - t * 2.3) * 0.25,
            0.0,
            cos(gridPos.z * 6.0 + t * 0.9) * 0.4 + cos((gridPos.z - gridPos.x) * 11.0 + t * 1.4) * 0.35 + cos(gridPos.x * 17.0 - t * 2.3) * 0.25);
        n = normalize(n + 0.05 * wave);
        vec3 lightened = mix(color.rgb, vec3(1.0), 0.35);
        // The meniscus: the outer 0.08 of the shape is a bevel, lit on the side facing the light.
        float rim = smoothstep(-0.08, 0.0, d);
        vec2 edgeDir = normalize(p - centre + vec2(0.0001));
        float facing = dot(edgeDir, normalize(vec2(l.x, l.z)));
        color.rgb = mix(color.rgb, lightened, 0.30 * rim * max(facing, 0.0));
        color.rgb *= 1.0 - 0.12 * rim * max(-facing, 0.0);
        float glint = pow(max(dot(reflect(-l, n), v), 0.0), 60.0) * 0.55;
        float sheen = pow(max(dot(reflect(-l, n), v), 0.0), 5.0) * 0.14;
        float fresnel = pow(1.0 - max(dot(n, v), 0.0), 4.0) * 0.18;
        color.rgb = mix(color.rgb, vec3(1.0), glint);
        color.rgb = mix(color.rgb, lightened, sheen);
        color.rgb = mix(color.rgb, lightened, fresnel);
        color.a = 1.0;
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}

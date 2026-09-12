#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif
// RIVALS: the two things the paint gloss and the data LED need and vanilla's item pair does not carry.
// Declared for every variant (the alpha-only OIT phase discards paint too, so it runs the same border
// test). viewPos is view space, for the gloss's normal (from its derivatives) and view vector; rawColor
// is the vertex tint before any lighting, which is both the LED's exact colour for the post effect to
// read back and the paint's own colour without the directional term the chunks never get.
layout(location = 7) out vec3 viewPos;
layout(location = 8) out vec4 rawColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    rawColor = Color;
    #ifndef OIT_ALPHA_ONLY
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #endif

    texCoord0 = UV0;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
    #endif

    // RIVALS: view space, for the gloss's normal (from its derivatives) and view vector — the same two
    // uses terrain.vsh's viewPos has. There is deliberately no world position here: for an entity or a
    // display, Position is whatever the render PoseStack left in the buffer (the camera rotation is
    // already baked in), not a camera-relative world offset, so no arithmetic on it recovers one. The
    // paint's in-cell coordinate comes off the sprite instead — see item.fsh.
    viewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
}

#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
// RIVALS: the camera's block position and sub-block offset, so the fragment shader can put a display
// quad back on the world's block grid (see paintPos below). Bound for ITEM_CUTOUT: the pipeline's
// ITEM_SNIPPET is built on MATRICES_FOG_LIGHT_DIR_SNIPPET, which is built on GLOBALS_SNIPPET.
#include <minecraft:globals.glsl>

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
// RIVALS: the two things the paint gloss needs and vanilla's item pair does not carry. Declared for
// every variant (the alpha-only OIT phase discards paint too, so it runs the same border test).
layout(location = 7) out vec3 viewPos;
layout(location = 8) out vec3 paintPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
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
    // uses terrain.vsh's viewPos has.
    viewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
    // RIVALS: the world position, wrapped to keep it small. `Position` here is camera-relative render
    // space (terrain.vsh builds it as worldPos - CameraBlockPos + CameraOffset), so worldPos is
    // Position - CameraOffset + CameraBlockPos. The wrap subtracts a multiple of 1024 blocks, which
    // leaves fract() — the in-cell coordinate the border is cut from — exactly unchanged while keeping
    // float precision far better than a raw world coordinate would; the offset is one value per frame,
    // so it cannot vary across a quad. Paint on a block display therefore lands on the very same
    // 1/16-block grid as the paint blocks beside it.
    paintPos = Position - CameraOffset + vec3(CameraBlockPos & 1023);
}

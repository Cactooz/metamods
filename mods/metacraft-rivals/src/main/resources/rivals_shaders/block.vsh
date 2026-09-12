#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 viewPos;

void main() {
	vec3 pos = Position + ModelOffset;
	vec4 view = ModelViewMat * vec4(pos, 1.0);
	gl_Position = ProjMat * view;
	viewPos = view.xyz;

	sphericalVertexDistance = fog_spherical_distance(pos);
	cylindricalVertexDistance = fog_cylindrical_distance(pos);
	vertexColor = Color * sample_lightmap(Sampler2, UV2);
	texCoord0 = UV0;
}

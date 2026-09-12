#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewPos;

out vec4 fragColor;

void main() {
	vec4 tex = texture(Sampler0, texCoord0);
	vec4 color = tex * vertexColor * ColorModulator;
#ifdef ALPHA_CUTOUT
	if (color.a < ALPHA_CUTOUT) {
		discard;
	}
#endif
	// RIVALS_GLOSS: paint texels carry alpha 229/255 = 0.898 as a marker; everything else is vanilla.
	// The window admits alpha 228..230 (three steps) on purpose, as filtering tolerance, and stays
	// five steps clear of the nearest vanilla value (224/255).
	if (abs(tex.a - 0.898) < 0.004) {
		vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
		vec3 v = normalize(-viewPos);
		float t = GameTime * 1200.0;
		vec3 l = normalize(vec3(0.3 + 0.15 * sin(t), 0.8, 0.5 + 0.15 * cos(t)));
		float spec = pow(max(dot(reflect(-l, n), v), 0.0), 24.0) * 0.55;
		float fresnel = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.25;
		color.rgb += spec + fresnel;
		color.a = 1.0;
	}
	fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

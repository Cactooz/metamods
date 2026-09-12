#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewPos;
in vec3 chunkPos;

out vec4 fragColor;

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
	// Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
	vec2 uvTexelCoords = uv / pixelSize;
	vec2 texelCenter = round(uvTexelCoords) - 0.5f;
	vec2 texelOffset = uvTexelCoords - texelCenter;

	// Move our offset closer to the texel center based on texel size on screen
	texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
	texelOffset = clamp(texelOffset, 0.0f, 1.0f);

	uv = (texelCenter + texelOffset) * pixelSize;
	return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
	vec2 du = dFdx(uv);
	vec2 dv = dFdy(uv);
	vec2 texelScreenSize = sqrt(du * du + dv * dv);
	return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
	vec2 du = dFdx(uv);
	vec2 dv = dFdy(uv);

	vec2 texelScreenSize = sqrt(du * du + dv * dv);
	float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

	float minPixelSize = min(pixelSize.x, pixelSize.y);

	float transitionStart = minPixelSize * 1.0;
	float transitionEnd = minPixelSize * 2.0;
	float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

	float duLength = length(du);
	float dvLength = length(dv);
	float minDerivative = min(duLength, dvLength);
	float maxDerivative = max(duLength, dvLength);

	float effectiveDerivative = sqrt(minDerivative * maxDerivative);

	float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

	float mipLevelLow = floor(mipLevelExact);
	float mipLevelHigh = mipLevelLow + 1.0;
	float mipBlend = fract(mipLevelExact);

	const vec2 offsets[4] = vec2[](
	vec2(0.125, 0.375),
	vec2(-0.125, -0.375),
	vec2(0.375, -0.125),
	vec2(-0.375, 0.125)
	);

	vec4 rgssColorLow = vec4(0.0);
	vec4 rgssColorHigh = vec4(0.0);
	for (int i = 0; i < 4; ++i) {
		vec2 sampleUV = uv + offsets[i] * pixelSize;
		rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
		rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
	}
	rgssColorLow *= 0.25;
	rgssColorHigh *= 0.25;

	vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);

	vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

	return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
	vec4 tex = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize));
	vec4 color = tex * vertexColor;
	color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
#ifdef ALPHA_CUTOUT
	if (color.a < ALPHA_CUTOUT) {
		discard;
	}
#endif
	// RIVALS_GLOSS: paint texels carry alpha 229/255 = 0.898 as a marker; the window admits 228..230
	// (three steps, filtering tolerance) and stays five steps clear of the nearest vanilla value (224).
	if (abs(tex.a - 0.898) < 0.004) {
		// RIVALS_GLOSS: liquid, not metal. Four things sell it, none of them additive white:
		//   1. the splat textures are animation strips (like water_still.png) with a sheen sweeping across;
		//   2. the atlas is sampled at a wobbling UV, under a texel of offset, so the edges jiggle — the
		//      wobble sample is only taken (and only kept) when it is still a paint texel, so it can never
		//      bleed a neighbouring sprite in and non-paint fragments never reach this branch at all;
		//   3. fragments whose 4-neighbourhood leaves the sprite get a meniscus: lit on the side facing the
		//      light, darkened on the far side, which gives the decal thickness instead of decal flatness;
		//   4. three sine waves over chunkPos perturb the normal, so the specular crawls like a skin of
		//      liquid; tangent space is unavailable here, so the perturbation is added in view space.
		vec2 texel = 1.0 / TextureSize;
		vec3 p = chunkPos;
		float t = GameTime * 1200.0;
		// One texel is the ceiling on the wobble; the derivative keeps it sub-texel when the sprite is small.
		float amp = min(0.7 * length(vec2(dFdx(texCoord0.x), dFdy(texCoord0.y))), 0.0006);
		vec2 wobbleUv = texCoord0 + amp * vec2(sin(p.x * 9.0 + p.y * 5.0 + t * 1.7), cos(p.z * 9.0 + p.y * 5.0 + t * 1.3));
		vec4 wobbled = sampleNearest(Sampler0, wobbleUv, texel);
		if (abs(wobbled.a - 0.898) < 0.004) {
			color = wobbled * vertexColor;
			color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
		}
		vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
		vec3 v = normalize(-viewPos);
		vec3 l = normalize(vec3(0.3 + 0.15 * sin(t), 0.8, 0.5 + 0.15 * cos(t)));
		vec3 wave = vec3(
			sin(p.x * 6.0 + t * 0.9) * 0.4 + sin((p.x + p.z) * 11.0 + t * 1.4) * 0.35 + sin(p.z * 17.0 - t * 2.3) * 0.25,
			0.0,
			cos(p.z * 6.0 + t * 0.9) * 0.4 + cos((p.z - p.x) * 11.0 + t * 1.4) * 0.35 + cos(p.x * 17.0 - t * 2.3) * 0.25);
		n = normalize(n + 0.05 * wave);
		vec3 lightened = mix(color.rgb, vec3(1.0), 0.35);
		// The meniscus: sum the directions in which the sprite ends, and light that bevel from one side.
		vec2 offsets[4] = vec2[](vec2(texel.x, 0.0), vec2(-texel.x, 0.0), vec2(0.0, texel.y), vec2(0.0, -texel.y));
		vec2 edgeDir = vec2(0.0);
		for (int i = 0; i < 4; ++i) {
			if (abs(textureLod(Sampler0, texCoord0 + offsets[i], 0.0).a - 0.898) >= 0.004) edgeDir += offsets[i];
		}
		if (length(edgeDir) > 0.0) {
			float facing = dot(normalize(edgeDir), normalize(l.xz));
			color.rgb = mix(color.rgb, lightened, 0.30 * max(facing, 0.0));
			color.rgb *= 1.0 - 0.12 * max(-facing, 0.0);
		}
		float glint = pow(max(dot(reflect(-l, n), v), 0.0), 60.0) * 0.55;
		float sheen = pow(max(dot(reflect(-l, n), v), 0.0), 5.0) * 0.14;
		float fresnel = pow(1.0 - max(dot(n, v), 0.0), 4.0) * 0.18;
		color.rgb = mix(color.rgb, vec3(1.0), glint);
		color.rgb = mix(color.rgb, lightened, sheen);
		color.rgb = mix(color.rgb, lightened, fresnel);
		color.a = 1.0;
	}
	fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

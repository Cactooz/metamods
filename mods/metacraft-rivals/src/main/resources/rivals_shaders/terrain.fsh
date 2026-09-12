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

/** Texels to a block: the grid the paint's border, wobble and highlights are all snapped to. */
const float TEXELS = 16.0;

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
	// Derivatives are only defined in uniform control flow: take them before the paint branch.
	vec3 chunkDx = dFdx(chunkPos), chunkDy = dFdy(chunkPos);
	vec3 viewDx = dFdx(viewPos), viewDy = dFdy(viewPos);
	if (abs(tex.a - 0.898) < 0.004) {
		// Connected paint (spec §5). The texel is one flat colour whose red low nibble says which of the
		// cell's four in-plane neighbours are painted; the face normal (from chunkPos) picks the two
		// in-plane axes, and a rounded box — full on connected sides, inset and rounded on the others —
		// decides whether this fragment is paint at all. Inside it: a meniscus rim from the same distance,
		// a three-wave crawling normal, and glint / sheen / fresnel that mix toward light instead of adding.
		int bits = int(mod(floor(tex.r * 255.0 + 0.5), 16.0));
		vec3 nc = abs(normalize(cross(chunkDx, chunkDy)));
		vec2 p;
		vec2 along;
		if (nc.y >= nc.x && nc.y >= nc.z) { p = chunkPos.xz; }
		else if (nc.x >= nc.z) { p = chunkPos.zy; }
		else { p = chunkPos.xy; }
		along = p;
		p = fract(p);
		// Pixel art, not vector art: everything the border and the look are computed from is snapped to
		// the centre of its 1/16-block texel, so the discard decision is taken once per texel (corners
		// come out stepped rather than smooth), the wobble moves in whole-texel steps, and the
		// highlights are blocky too. Time stays continuous — texels flip, they never slide. The radius
		// (0.28) and the inset (0.06) stay in block units: about 4.5 and 1 texels.
		p = (floor(p * TEXELS) + 0.5) / TEXELS;
		along = (floor(along * TEXELS) + 0.5) / TEXELS;
		vec3 gridPos = (floor(chunkPos * TEXELS) + 0.5) / TEXELS;
		float t = GameTime * 1200.0;
		bool negU = (bits & 1) != 0, posU = (bits & 2) != 0, negV = (bits & 4) != 0, posV = (bits & 8) != 0;
		// Inset each unconnected side by 0.06 plus a slow wobble; connected sides run out past the cell.
		float w0 = 0.02 * sin(along.y * 12.0 + t * 1.1), w1 = 0.02 * sin(along.y * 12.0 + 2.0 + t * 0.9);
		float w2 = 0.02 * sin(along.x * 12.0 + 4.0 + t * 1.3), w3 = 0.02 * sin(along.x * 12.0 + 1.0 + t * 0.8);
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
	fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

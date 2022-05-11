$input vTexCoord0, v_viewRay

#include <forward_pipeline.sh>

SAMPLER2D(u_color, 0);
SAMPLER2D(u_attr0, 1);
SAMPLER2D(u_attr1, 2);
SAMPLER2D(u_noise, 3);
SAMPLERCUBE(u_probe, 4);
SAMPLER2D(u_depthTex, 5);           // input: minimum depth pyramid

#define sample_count uAAAParams[1].z

#define uv_ratio vec2_splat(uAAAParams[0].y)

#include <aaa_utils.sh>
#include <hiz_trace.sh>

void main() {
	vec4 jitter = texture2D(u_noise, mod(gl_FragCoord.xy, vec2(64, 64)) / vec2(64, 64));

	// sample normal/depth
	vec2 uv = GetAttributeTexCoord(vTexCoord0, textureSize(u_attr0, 0).xy);
	vec4 attr0 = texture2D(u_attr0, uv);

	vec3 n = attr0.xyz;

	// compute ray origin & direction
	vec3 ray_o = GetRayOrigin(uMainProjection, v_viewRay, attr0.w);
	vec3 ray_d = reflect(normalize(ray_o), n);

	// roughness
	float roughness = texture2D(u_attr1, uv).z;
	roughness = pow(roughness, 2.5);

	vec3 right = cross(ray_d, vec3(0, 0, 1));
	vec3 up = cross(ray_d, right);

	const float z_min = 0.1;

	//
	vec4 color = vec4_splat(0.);

	for (int i = 0; i < int(sample_count); ++i) {
		float r = roughness * (float(i) + jitter.y) / sample_count;
		float spread = r * 3.141592 * 0.5 * 0.99;
		float cos_spread = cos(spread), sin_spread = sin(spread);

		for (int j = 0; j < int(sample_count); ++j) {
			float angle = float(j + jitter.w) / sample_count * 2. * 3.141592;
			vec3 ray_d_spread = (right * cos(angle) + up * sin(angle)) * sin_spread + ray_d * cos_spread;

			vec2 hit_pixel;
			vec3 hit_point;
			// [FG] Purposedly bumped the max_iterations from 64 to 128 to get rid of 'reflection holes' artifacts in the final render.
			if (TraceScreenRay(ray_o - v_viewRay * 0.05, ray_d_spread, uMainProjection, z_min, /*jitter.z,*/ 128, hit_pixel, hit_point)) {
				// use hit pixel velocity to compensate the fact that we are sampling the previous frame
				uv = hit_pixel * uv_ratio / uResolution.xy;
				vec2 vel = GetVelocityVector(uv);

				attr0 = texelFetch(u_attr0, ivec2(hit_pixel), 0);

				float log_depth = ComputeRayLogDepth(uMainProjection, hit_point);

				if ((dot(attr0.xyz, ray_d_spread) < 0.0) && (hit_point.z <= log_depth)) { // ray facing the collision
					color += vec4(texture2D(u_color, uv - vel * uv_ratio).xyz, 1.0);
				} else {
					color += vec4(0.0, 0.0, 0.0, 0.0); // backface hit
				}
			} else {
				vec3 world_ray_o = mul(uMainInvView, vec4(ray_o, 1.0)).xyz;
				vec3 world_ray_d = mul(uMainInvView, vec4(ray_d_spread, 0.0)).xyz;
				color += vec4(textureCubeLod(u_probe, ReprojectProbe(world_ray_o, world_ray_d), 0).xyz, 0.);
			}
		}
	}

#if 1
	if (isNan(color.x) || isNan(color.y) || isNan(color.z))
		color = vec4(1.0, 0.0, 0.0, 0.0);
#endif

	gl_FragColor = color / (sample_count * sample_count);
}

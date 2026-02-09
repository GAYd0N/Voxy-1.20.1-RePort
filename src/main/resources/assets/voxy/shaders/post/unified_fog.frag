#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform mat4 invProjMat;

#ifdef USE_ENV_FOG
layout(location = 4) uniform vec3 endParams;
layout(location = 5) uniform vec3 fogColour;
#endif

#ifdef USE_ATMOSPHERIC_FOG
layout(location = 6) uniform vec4 atmosphericFogParams; // x: density, y: falloff, z: start, w: unused
layout(location = 7) uniform vec3 atmosphericFogColor;
#endif

out vec4 outColor;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip*2.0f-1.0f, 1.0f);
    return view.xyz/view.w;
}

void main() {
    float depth = texture(depthTex, UV).r;
    if (depth >= 1.0) {
        // For sky pixels, we might want to apply atmospheric fog too,
        // but typically Minecraft sky has its own fog.
        // For now, let's skip pixels that are at infinite distance if not using atmospheric fog.
        #ifndef USE_ATMOSPHERIC_FOG
        discard;
        #endif
        // If using atmospheric fog, we might want to apply it to sky, 
        // but reconstruction of sky position from depth 1.0 is tricky.
        // Let's assume a large distance for sky if needed, or just discard.
        #ifdef USE_ATMOSPHERIC_FOG
        // Reconstruct position for sky using depth 1.0 (or slightly less)
        // Actually, let's just use a very far point.
        #endif
    }

    vec3 point = rev3d(vec3(UV, depth));
    float dist = length(point.xyz);
    
    vec3 finalFogColor = vec3(0.0);
    float finalFogAmount = 0.0;

    #ifdef USE_ENV_FOG
    {
        float fogLerp = clamp(fma(min(dist, endParams.x), endParams.y, endParams.z), 0.0, 1.0);
        // Blend environmental fog
        finalFogColor = mix(finalFogColor, fogColour, fogLerp);
        finalFogAmount = max(finalFogAmount, fogLerp);
    }
    #endif

    #ifdef USE_ATMOSPHERIC_FOG
    {
        float density = atmosphericFogParams.x;
        float falloff = atmosphericFogParams.y;
        float start = atmosphericFogParams.z;
        
        float atmosphericFogAmount = 1.0 - exp(-pow(max(0.0, dist - start) * density, falloff));
        atmosphericFogAmount = clamp(atmosphericFogAmount, 0.0, 1.0);
        
        // Combine with existing fog or just set
        if (finalFogAmount > 0.0) {
            finalFogColor = mix(finalFogColor, atmosphericFogColor, atmosphericFogAmount);
            finalFogAmount = mix(finalFogAmount, 1.0, atmosphericFogAmount);
        } else {
            finalFogColor = atmosphericFogColor;
            finalFogAmount = atmosphericFogAmount;
        }
    }
    #endif

    if (finalFogAmount <= 0.0) {
        discard;
    }

    outColor = vec4(finalFogColor, finalFogAmount);
}

package com.ljyh.mei.ui.component.player.component.mesh

internal object ShaderSource {
    const val MESH_SHADER = """
uniform shader albumTexture;
uniform float2 textureSize;
uniform float time;
// Precomputed once per frame: scale, contrast, saturation.
uniform float3 audioResponse;

float gradientNoise(float2 position) {
    return fract(52.9829189 * fract(dot(position, float2(0.06711056, 0.00583715))));
}

float2 rotate(float2 value, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return float2(c * value.x - s * value.y, s * value.x + c * value.y);
}

half4 main(float2 uv) {
    // drawVertices supplies interpolated mesh UVs as shader coordinates. Using these for
    // grain keeps the dither amplitude, with at most 1/255 deviation from screen-space noise.
    float dither = gradientNoise(uv * 1440.0) / 255.0 - 0.5 / 255.0;
    float2 centered = uv - float2(0.2);
    float2 rotated = rotate(centered, time * 2.0);
    float2 finalUV = rotated / audioResponse.x + float2(0.5);
    half4 result = albumTexture.eval(finalUV * textureSize);
    result.rgb = (result.rgb - half3(0.5)) * audioResponse.y + half3(0.5);
    float luminance = dot(result.rgb, half3(0.2126, 0.7152, 0.0722));
    result.rgb = mix(half3(luminance), result.rgb, audioResponse.z);
    result.rgb += half3(dither);
    float dist = distance(uv, float2(0.5));
    float vignette = 1.0 - smoothstep(0.3, 0.8, dist);
    result.rgb *= 0.6 + vignette * 0.4;
    // The old RGBA8 FBO clamped RGB before its quad applied SRC_ALPHA. Canvas expects
    // premultiplied output, so retain that texture-alpha factor here; the layer owns fade.
    result.rgb = clamp(result.rgb, half3(0.0), half3(1.0)) * result.a;
    return result;
}
"""
}

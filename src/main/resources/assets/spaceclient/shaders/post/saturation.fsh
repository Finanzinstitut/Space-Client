#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform SaturationConfig {
    float SaturationAmount;
};

const vec3 Gray = vec3(0.3, 0.59, 0.11);

out vec4 fragColor;

void main() {
    vec4 InTexel = texture(InSampler, texCoord);
    float Luma = dot(InTexel.rgb, Gray);
    vec3 Chroma = InTexel.rgb - Luma;
    fragColor = vec4((Chroma * SaturationAmount) + Luma, 1.0);
}

#version 330

uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    if (texture(Sampler0, texCoord0).a < 0.1) discard;
    fragColor = vertexColor;
}

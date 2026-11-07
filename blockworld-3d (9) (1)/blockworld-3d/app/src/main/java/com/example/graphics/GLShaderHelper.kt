package com.example.graphics

import android.opengl.GLES20
import android.util.Log

object GLShaderHelper {

    private const val TAG = "GLShaderHelper"

    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compilation error: $error")
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error creating shader: $error")
        }
        return shader
    }

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Program linking error: $error")
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Error linking program: $error")
        }
        return program
    }

    /**
     * Main 3D World Shader with Diffuse, Ambient, Specular, and Atmospheric Fog.
     */
    val WORLD_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uModelMatrix;
        uniform mat4 uNormalMatrix;
        
        attribute vec4 aPosition;
        attribute vec3 aNormal;
        attribute vec2 aTexCoord;

        varying vec3 vPosition;
        varying vec3 vNormal;
        varying vec2 vTexCoord;

        void main() {
            vec4 worldPos = uModelMatrix * aPosition;
            vPosition = worldPos.xyz;
            vNormal = normalize((uNormalMatrix * vec4(aNormal, 0.0)).xyz);
            vTexCoord = aTexCoord;
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    val WORLD_FRAGMENT_SHADER = """
        precision mediump float;

        uniform int uFogEnabled;
        uniform vec3 uFogColor;
        uniform float uFogBrightness;
        uniform float uFogOpacity;
        uniform float uFogDensity;
        uniform float uFogStartDistance;
        uniform float uFogEndDistance;
        uniform float uFogSoftness;
        uniform float uFogGlow;

        uniform float uObjectBrightness;
        // Dedicated live Environment & Object View controls. These uniforms deliberately
        // do not share names with the legacy GameSettings uniforms, which used to overwrite
        // the live screen's values every frame.
        uniform float uLiveObjectBrightness;
        uniform int uLiveFogEnabled;
        uniform vec3 uLiveFogColor;
        uniform float uLiveFogDensity;
        uniform float uLiveFogGlow;

        uniform float uObjectSaturation;
        uniform float uObjectOpacity;
        uniform float uObjectContrast;
        uniform float uObjectTemperature;
        uniform float uObjectTextureEdgeSoftness;

        uniform vec3 uSunColor;
        uniform float uSunIntensity;
        uniform float uAmbientIntensity;

        uniform vec4 uColor;
        uniform vec3 uLightDir;
        uniform vec3 uCameraPos;
        uniform float uHitFlash;
        uniform sampler2D uTexture;
        uniform int uUseTexture;

        varying vec3 vPosition;
        varying vec3 vNormal;
        varying vec2 vTexCoord;

        void main() {
            vec3 normal = normalize(vNormal);
            vec3 lightDir = normalize(uLightDir);
            float distanceToCamera = length(uCameraPos - vPosition);
            vec3 finalColor;
            float finalAlpha = uColor.a;

            // A zero/unset brightness uniform falls back to neutral brightness.
            // Keep the valid slider range above zero so a user's setting is respected.
            float objectBrightness = (uObjectBrightness > 0.0)
                ? uObjectBrightness : 1.0;

            if (uUseTexture == 1) {
                vec4 texel = texture2D(uTexture, vTexCoord);
                // glTF base-colour maps are authored in sRGB; lighting calculations are linear.
                vec3 textureLinear = pow(max(texel.rgb, vec3(0.0)), vec3(2.2));
                vec3 baseLinear = textureLinear * uColor.rgb;
                float diffuse = max(dot(normal, lightDir), 0.0);
                vec3 illumination = vec3(0.84) + vec3(0.16, 0.155, 0.14) * diffuse;
                vec3 litLinear = max(baseLinear * illumination, vec3(0.0));
                vec3 litSrgb = pow(litLinear, vec3(0.45454545));
                finalColor = litSrgb * objectBrightness;
                finalAlpha = texel.a * uColor.a;
            } else {
                vec3 ambient = vec3(0.42, 0.46, 0.52);
                float diffuse = max(dot(normal, lightDir), 0.0);
                vec3 diffuseLight = diffuse * vec3(1.0, 0.98, 0.92) * 0.75;
                float groundBounce = max(dot(normal, vec3(0.0, -1.0, 0.0)), 0.0) * 0.15;
                vec3 viewDir = normalize(uCameraPos - vPosition);
                vec3 reflectDir = reflect(-lightDir, normal);
                float spec = pow(max(dot(viewDir, reflectDir), 0.0), 16.0) * 0.35;
                vec3 litColor = (ambient + diffuseLight + vec3(groundBounce)) * uColor.rgb + vec3(spec);
                finalColor = litColor * objectBrightness;
            }

            // Live Environment & Object View settings. This independent pass is applied
            // after base material lighting so the screen updates visuals immediately and
            // cannot be overwritten by the legacy settings uniforms above.
            float liveBrightness = clamp(uLiveObjectBrightness, 0.0, 2.0);
            finalColor *= liveBrightness;

            if (uLiveFogEnabled == 1) {
                float liveDensity = clamp(uLiveFogDensity, 0.0, 0.08);
                float liveFogFactor = 1.0 - exp(-liveDensity * max(distanceToCamera, 0.0));
                liveFogFactor = clamp(liveFogFactor, 0.0, 1.0);
                vec3 liveFogColor = max(uLiveFogColor, vec3(0.0));
                finalColor = mix(finalColor, liveFogColor, liveFogFactor);
                finalColor += liveFogColor * liveFogFactor * clamp(uLiveFogGlow, 0.0, 2.0) * 0.15;
            }

            // Legacy GameSettings fog remains disabled by the live settings bridge to avoid
            // an old default fog state making the new Enable Fog switch appear ineffective.
            if (uFogEnabled == 1) {
                float fogDensity = max(uFogDensity, 0.0);
                float densityFactor = 1.0 - exp(-fogDensity * distanceToCamera);
                float fogStart = (uFogEndDistance > uFogStartDistance)
                    ? max(uFogStartDistance, 0.0) : 35.0;
                float fogEnd = (uFogEndDistance > fogStart)
                    ? uFogEndDistance : 80.0;
                float distanceFactor = clamp(
                    (distanceToCamera - fogStart) / (fogEnd - fogStart), 0.0, 1.0
                );
                float softness = clamp(uFogSoftness, 0.0, 1.0);
                distanceFactor = mix(distanceFactor, smoothstep(0.0, 1.0, distanceFactor), softness);
                float fogOpacity = (uFogOpacity > 0.0)
                    ? clamp(uFogOpacity, 0.0, 1.0) : 1.0;
                float fogFactor = clamp(densityFactor * distanceFactor * fogOpacity, 0.0, 1.0);
                float fogBrightness = (uFogBrightness > 0.0) ? uFogBrightness : 1.0;
                vec3 effectiveFogColor = max(uFogColor, vec3(0.0)) * fogBrightness;
                finalColor = mix(finalColor, effectiveFogColor, fogFactor);
                // Fog glow adds a restrained emissive halo in fogged regions.
                finalColor += effectiveFogColor * fogFactor * max(uFogGlow, 0.0);
            }

            finalColor = mix(finalColor, vec3(1.0), clamp(uHitFlash, 0.0, 1.0) * 0.7);
            gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), finalAlpha);
        }
    """.trimIndent()

    /**
     * Sky Dome Shader creating atmospheric blue gradient with sun glare (Requirement 3).
     */
    val SKY_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        varying vec3 vWorldDir;
        
        void main() {
            vWorldDir = aPosition.xyz;
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    val SKY_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec3 uSunDir;
        uniform int uSkyMode; // 0: Vibrant Blue, 1: Sunset, 2: Space, 3: HDR
        uniform vec3 uSkyTint;
        uniform float uSkyTintStrength;
        uniform float uSkyBrightness;
        varying vec3 vWorldDir;
        
        void main() {
            vec3 dir = normalize(vWorldDir);
            float y = dir.y;
            
            vec3 zenithColor;
            vec3 horizonColor;
            vec3 groundColor;
            
            if (uSkyMode == 1) {
                // Sunset Horizon
                zenithColor = vec3(0.2, 0.15, 0.45);
                horizonColor = vec3(1.0, 0.55, 0.2);
                groundColor = vec3(0.35, 0.15, 0.2);
            } else if (uSkyMode == 2) {
                // Cyber Midnight Space
                zenithColor = vec3(0.02, 0.03, 0.08);
                horizonColor = vec3(0.12, 0.18, 0.35);
                groundColor = vec3(0.02, 0.02, 0.05);
            } else {
                // Vibrant Blue Sky (Roblox & Nintendo style)
                zenithColor = vec3(0.12, 0.48, 0.95); // Deep Azure
                horizonColor = vec3(0.72, 0.88, 1.0);  // Soft Sky Blue / Cyan
                groundColor = vec3(0.45, 0.72, 0.92);
            }
            
            vec3 sky;
            if (y >= 0.0) {
                float t = pow(clamp(y, 0.0, 1.0), 0.55);
                sky = mix(horizonColor, zenithColor, t);
                
                // Stylized clouds layer around horizon (y between 0.15 and 0.45)
                float cloudBand = sin(dir.x * 6.0 + dir.z * 5.0) * cos(dir.z * 6.0);
                if (cloudBand > 0.4 && y > 0.12 && y < 0.48) {
                    sky = mix(sky, vec3(1.0, 1.0, 1.0), (cloudBand - 0.4) * 0.75);
                }
            } else {
                sky = mix(horizonColor, groundColor, clamp(-y * 2.0, 0.0, 1.0));
            }
            
            // Sun disc & atmospheric glare
            float sunDot = max(dot(dir, normalize(uSunDir)), 0.0);
            float sunDisc = step(0.997, sunDot);
            float sunGlow = pow(sunDot, 64.0) * 0.65;
            sky += vec3(1.0, 0.95, 0.8) * (sunDisc + sunGlow);
            
            sky = mix(sky, max(uSkyTint, vec3(0.0)), clamp(uSkyTintStrength, 0.0, 1.0));
            sky *= max(uSkyBrightness, 0.0);
            gl_FragColor = vec4(clamp(sky, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * Simple Unlit / Alpha Particle Shader.
     */
    val PARTICLE_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    val PARTICLE_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()
}

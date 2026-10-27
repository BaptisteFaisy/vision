package dev.sam3vision.app.ihear

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import dev.sam3vision.core.iseedesign.IseePortrait

/**
 * La boule iSee du design « Portrait » : une sphère de verre (reflet de boîte à lumière,
 * irisation de bulle de savon au bord, liseré) qui tient un liquide marbré Amber. Aucun
 * pixel : verre et liquide seulement. Le liquide passe au bleu quand le user parle, à l'ambre
 * quand iSee parle, et ondule avec la voix.
 *
 * API 33+ : un shader AGSL (même rendu que la maquette web). En dessous : repli en dégradés.
 */
data class OrbLook(
    /** Temps en secondes (anime le marbre). */
    val time: Float,
    /** 0..1 : le user parle (liquide bleu). */
    val you: Float,
    /** 0..1 : iSee parle (liquide ambre). */
    val see: Float,
    /** 0..1 : amplitude de la voix. */
    val amp: Float,
    /** 0..1 : niveau du liquide dans la boule. */
    val level: Float,
)

private const val ORB_AGSL = """
uniform float2 uC;
uniform float uR;
uniform float uTime;
uniform float uYou;
uniform float uSee;
uniform float uAmp;
uniform float uLevel;
uniform half4 uIdle[5];
uniform half4 uPalYou[5];
uniform half4 uPalSee[5];

float h21(float2 p) { p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float n2(float2 p) {
  float2 i = floor(p); float2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h21(i), h21(i + float2(1, 0)), f.x), mix(h21(i + float2(0, 1)), h21(i + float2(1, 1)), f.x), f.y);
}
float fbm(float2 p) {
  float s = 0.0; float a = 0.5;
  for (int i = 0; i < 5; i++) { s += a * n2(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
  return s;
}
half3 cyc(int which, float s) {
  float x = fract(s) * 5.0; float k = smoothstep(0.15, 0.85, fract(x)); int i = int(floor(x)); int j = i == 4 ? 0 : i + 1;
  half3 a; half3 b;
  if (which == 0) { a = uIdle[i].rgb; b = uIdle[j].rgb; } else if (which == 1) { a = uPalYou[i].rgb; b = uPalYou[j].rgb; } else { a = uPalSee[i].rgb; b = uPalSee[j].rgb; }
  return mix(a, b, half(k));
}
half3 film(float d) {
  float3 I = 0.5 - 0.5 * cos(4.0 * 3.14159 * 1.33 * d / float3(650.0, 540.0, 450.0));
  float m = (I.r + I.g + I.b) / 3.0;
  return half3(clamp(m + (I - m) * 1.15, 0.0, 1.0));
}
float sdBox(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

half4 main(float2 fc) {
  float2 q = (fc - uC) / uR; q.y = -q.y;
  float d = length(q);
  float aa = 1.4 / uR;
  if (d > 1.0 + aa) return half4(0);
  float z = sqrt(max(0.0, 1.0 - min(d * d, 1.0)));
  half3 col = mix(half3(0.95, 0.965, 0.985), half3(1.0), half(z * 0.4));
  half3 accent = mix(mix(half3(0.55, 0.62, 0.8), half3(0.04, 0.37, 1.0), half(uYou)), half3(0.96, 0.64, 0.0), half(uSee * (1.0 - uYou)));
  col = mix(col, accent, half((0.05 + 0.18 * (uYou + uSee) * uAmp) * smoothstep(1.0, 0.2, length(q - float2(0.0, -0.2)))));
  // Le liquide : surface ondulée, marbre en cinq teintes, ménisque blanc.
  float2 p = q / 0.93;
  float stir = 0.018 + 0.06 * uAmp * (uSee + uYou);
  float surf = -1.0 + 2.0 * uLevel + stir * sin(p.x * 5.5 + uTime * 2.4) + 0.4 * stir * sin(p.x * 11.0 - uTime * 3.3);
  float e = 1.6 / (uR * 0.93);
  float a = smoothstep(e, -e, p.y - surf) * smoothstep(1.0, 1.0 - e * 2.0, length(p));
  if (a > 0.0) {
    float2 m = p * 1.35 + float2(0.0, uTime * 0.05);
    float w = fbm(m + 1.3 * float2(fbm(m + uTime * 0.06), fbm(m + float2(5.2, 1.3) - uTime * 0.05)));
    float s = w * 1.6 + p.y * 0.18 + uTime * 0.02;
    float y = clamp(uYou, 0.0, 1.0); float ee = clamp(uSee, 0.0, 1.0) * (1.0 - y);
    half3 c = cyc(0, s) * half(1.0 - y - ee) + cyc(1, s) * half(y) + cyc(2, s) * half(ee);
    c = mix(c, half3(1.0), half(0.2 * smoothstep(0.025, 0.0, abs(fract(s * 2.0) - 0.5))));
    float lz = sqrt(max(0.0, 1.0 - dot(p, p)));
    c *= half(0.74 + 0.34 * lz); c += half(0.14 * pow(lz, 3.0));
    c = mix(c, half3(1.0), half(0.75 * smoothstep(0.035, 0.0, abs(p.y - surf))));
    col = mix(col, c, half(a));
  }
  col *= half(mix(1.0, 0.88, pow(1.0 - z, 3.0)));
  // Le verre : irisation au bord, boîte à lumière, point chaud, liseré.
  float fres = pow(1.0 - z, 2.6);
  half3 fcol = film(300.0 + 620.0 * fbm(q * 1.6 + float2(0.0, uTime * 0.05) + z) + 180.0 * (0.5 - 0.5 * q.y));
  col = mix(col, fcol, half((0.03 + 0.7 * fres) * 0.45)) + fcol * half(fres * 0.08);
  float2 r = 2.0 * z * q;
  float box = smoothstep(0.035, -0.02, sdBox(r - float2(-0.43, 0.5), float2(0.2, 0.085), 0.07));
  float hot = pow(max(dot(float3(q, z), normalize(float3(-0.45, 0.55, 0.7))), 0.0), 220.0);
  col += half(box * 0.7 + hot);
  float2 dq = d > 1e-4 ? q / d : float2(0.0);
  col += half3(0.8, 0.88, 1.0) * half(smoothstep(0.72, 1.0, d) * smoothstep(-0.1, 0.8, dot(dq, normalize(float2(0.6, -0.55)))) * 0.3);
  col += accent * half(0.12 * smoothstep(0.1, 1.0, -q.y) * smoothstep(1.0, 0.75, d));
  float edge = smoothstep(1.0, 1.006, d) * smoothstep(1.03, 1.0, d);
  col = mix(col, col * 0.86, half(edge));
  float alpha = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, d);
  return half4(col * half(alpha), half(alpha));
}
"""

/** La boule, dessinée centrée en [center] (pixels) avec le rayon [radius]. */
@Composable
fun rememberOrbPainter(): (DrawScope, Offset, Float, OrbLook) -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { ::drawOrbFallback }
    val shader = remember { orbShader() }
    return remember(shader) { { scope, c, r, look -> scope.drawOrbAgsl(shader, c, r, look) } }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun orbShader(): RuntimeShader = RuntimeShader(ORB_AGSL).apply {
    setColorUniform("uIdle", IseePortrait.IDLE)
    setColorUniform("uPalYou", IseePortrait.PAL_YOU)
    setColorUniform("uPalSee", IseePortrait.PAL_SEE)
}

/** Les palettes passent en tableau de couleurs AGSL (half4[5]). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.setColorUniform(name: String, argb: IntArray) {
    val v = FloatArray(argb.size * 4)
    argb.forEachIndexed { i, c ->
        v[i * 4] = ((c shr 16) and 0xFF) / 255f
        v[i * 4 + 1] = ((c shr 8) and 0xFF) / 255f
        v[i * 4 + 2] = (c and 0xFF) / 255f
        v[i * 4 + 3] = 1f
    }
    setFloatUniform(name, v)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawOrbAgsl(shader: RuntimeShader, c: Offset, r: Float, look: OrbLook) {
    shader.setFloatUniform("uC", c.x, c.y)
    shader.setFloatUniform("uR", r)
    shader.setFloatUniform("uTime", look.time)
    shader.setFloatUniform("uYou", look.you)
    shader.setFloatUniform("uSee", look.see)
    shader.setFloatUniform("uAmp", look.amp)
    shader.setFloatUniform("uLevel", look.level)
    drawCircle(brush = ShaderBrush(shader), radius = r * 1.01f, center = c)
}

/** Repli sans AGSL : verre en dégradé radial, liquide en bas, reflet en haut à gauche. */
private fun drawOrbFallback(scope: DrawScope, c: Offset, r: Float, look: OrbLook) = with(scope) {
    val liquid = when {
        look.you > 0.5f -> listOf(Color(IseePortrait.PAL_YOU[3]), Color(IseePortrait.YOU))
        look.see > 0.5f -> listOf(Color(IseePortrait.PAL_SEE[3]), Color(IseePortrait.SEE))
        else -> listOf(Color(IseePortrait.IDLE[2]), Color(IseePortrait.IDLE[0]))
    }
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFE9EEF6)), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.4f), r, c)
    // Le liquide : le disque intérieur, coupé sous la surface.
    val top = c.y + r - 2f * r * look.level
    clipRect(top = top) {
        drawCircle(Brush.verticalGradient(liquid, startY = top, endY = c.y + r), r * 0.93f, c)
    }
    drawCircle(Color.White.copy(alpha = 0.85f), r * 0.14f, Offset(c.x - r * 0.42f, c.y - r * 0.48f))
    drawCircle(Color(IseePortrait.INK).copy(alpha = 0.12f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.012f))
}

/** Un Canvas qui ne dessine que la boule (le reste de la scène est en Compose). */
@Composable
fun IseeOrb(center: Offset, radius: Float, look: OrbLook, modifier: Modifier = Modifier) {
    val paint = rememberOrbPainter()
    Canvas(modifier) { paint(this, center, radius, look) }
}

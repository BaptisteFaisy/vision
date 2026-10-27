// Généré depuis le shader Portrait : python tools/export-caregiver-orb.py
export const fragmentShader = `precision highp float;
uniform vec2 uSize;

uniform vec2 uC;
uniform float uR;
uniform float uTime;
uniform float uYou;
uniform float uSee;
uniform float uAmp;
uniform float uLevel;
uniform vec4 uIdle[5];
uniform vec4 uPalYou[5];
uniform vec4 uPalSee[5];

float h21(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float n2(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h21(i), h21(i + vec2(1, 0)), f.x), mix(h21(i + vec2(0, 1)), h21(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec2 p) {
  float s = 0.0; float a = 0.5;
  for (int i = 0; i < 5; i++) { s += a * n2(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
  return s;
}
// SkSL n'accepte que des indices constants : on choisit donc la teinte par comparaisons,
// jamais \`palette[i]\` — sinon le shader refuse de se construire sur l'appareil.
vec3 pick5(vec4 c0, vec4 c1, vec4 c2, vec4 c3, vec4 c4, int i) {
  return i == 0 ? c0.rgb : i == 1 ? c1.rgb : i == 2 ? c2.rgb : i == 3 ? c3.rgb : c4.rgb;
}
vec3 cyc(vec4 c0, vec4 c1, vec4 c2, vec4 c3, vec4 c4, float s) {
  float x = fract(s) * 5.0; float k = smoothstep(0.15, 0.85, fract(x)); int i = int(floor(x)); int j = i == 4 ? 0 : i + 1;
  return mix(pick5(c0, c1, c2, c3, c4, i), pick5(c0, c1, c2, c3, c4, j), float(k));
}
vec3 cycIdle(float s) { return cyc(uIdle[0], uIdle[1], uIdle[2], uIdle[3], uIdle[4], s); }
vec3 cycYou(float s) { return cyc(uPalYou[0], uPalYou[1], uPalYou[2], uPalYou[3], uPalYou[4], s); }
vec3 cycSee(float s) { return cyc(uPalSee[0], uPalSee[1], uPalSee[2], uPalSee[3], uPalSee[4], s); }
vec3 film(float d) {
  vec3 I = 0.5 - 0.5 * cos(4.0 * 3.14159 * 1.33 * d / vec3(650.0, 540.0, 450.0));
  float m = (I.r + I.g + I.b) / 3.0;
  return vec3(clamp(m + (I - m) * 1.15, 0.0, 1.0));
}
float sdBox(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }

vec3 liquid(vec3 col, vec2 q) {
  // Le liquide : surface ondulée, marbre en cinq teintes, ménisque blanc.
  vec2 p = q / 0.93;
  float stir = 0.018 + 0.06 * uAmp * (uSee + uYou);
  float surf = -1.0 + 2.0 * uLevel + stir * sin(p.x * 5.5 + uTime * 2.4) + 0.4 * stir * sin(p.x * 11.0 - uTime * 3.3);
  float e = 1.6 / (uR * 0.93);
  float a = smoothstep(e, -e, p.y - surf) * smoothstep(1.0, 1.0 - e * 2.0, length(p));
  if (a > 0.0) {
    vec2 m = p * 1.35 + vec2(0.0, uTime * 0.05);
    float w = fbm(m + 1.3 * vec2(fbm(m + uTime * 0.06), fbm(m + vec2(5.2, 1.3) - uTime * 0.05)));
    float s = w * 1.6 + p.y * 0.18 + uTime * 0.02;
    float y = clamp(uYou, 0.0, 1.0); float ee = clamp(uSee, 0.0, 1.0) * (1.0 - y);
    vec3 c = cycIdle(s) * float(1.0 - y - ee) + cycYou(s) * float(y) + cycSee(s) * float(ee);
    c = mix(c, vec3(1.0), float(0.2 * smoothstep(0.025, 0.0, abs(fract(s * 2.0) - 0.5))));
    float lz = sqrt(max(0.0, 1.0 - dot(p, p)));
    c *= float(0.74 + 0.34 * lz); c += float(0.14 * pow(lz, 3.0));
    c = mix(c, vec3(1.0), float(0.75 * smoothstep(0.035, 0.0, abs(p.y - surf))));
    col = mix(col, c, float(a));
  }
  return col;
}

vec4 orb(vec2 fc) {
  vec2 q = (fc - uC) / uR; q.y = -q.y;
  float d = length(q);
  float aa = 1.4 / uR;
  if (d > 1.0 + aa) return vec4(0);
  float z = sqrt(max(0.0, 1.0 - min(d * d, 1.0)));
  vec3 col = mix(vec3(0.95, 0.965, 0.985), vec3(1.0), float(z * 0.4));
  vec3 accent = mix(mix(vec3(0.55, 0.62, 0.8), vec3(0.04, 0.37, 1.0), float(uYou)), vec3(0.96, 0.64, 0.0), float(uSee * (1.0 - uYou)));
  col = mix(col, accent, float((0.05 + 0.18 * (uYou + uSee) * uAmp) * smoothstep(1.0, 0.2, length(q - vec2(0.0, -0.2)))));
  col = liquid(col, q);
  col *= float(mix(1.0, 0.88, pow(1.0 - z, 3.0)));
  // Le verre : irisation au bord, boîte à lumière, point chaud, liseré.
  float fres = pow(1.0 - z, 2.6);
  vec3 fcol = film(300.0 + 620.0 * fbm(q * 1.6 + vec2(0.0, uTime * 0.05) + z) + 180.0 * (0.5 - 0.5 * q.y));
  col = mix(col, fcol, float((0.03 + 0.7 * fres) * 0.45)) + fcol * float(fres * 0.08);
  vec2 r = 2.0 * z * q;
  float box = smoothstep(0.035, -0.02, sdBox(r - vec2(-0.43, 0.5), vec2(0.2, 0.085), 0.07));
  float hot = pow(max(dot(vec3(q, z), normalize(vec3(-0.45, 0.55, 0.7))), 0.0), 220.0);
  col += float(box * 0.7 + hot);
  vec2 dq = d > 1e-4 ? q / d : vec2(0.0);
  col += vec3(0.8, 0.88, 1.0) * float(smoothstep(0.72, 1.0, d) * smoothstep(-0.1, 0.8, dot(dq, normalize(vec2(0.6, -0.55)))) * 0.3);
  col += accent * float(0.12 * smoothstep(0.1, 1.0, -q.y) * smoothstep(1.0, 0.75, d));
  float edge = smoothstep(1.0, 1.006, d) * smoothstep(1.03, 1.0, d);
  col = mix(col, col * 0.86, float(edge));
  float alpha = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, d);
  col = vec3(dot(col, vec3(0.2126, 0.7152, 0.0722)));
  return vec4(col * float(alpha), float(alpha));
}

void main() { gl_FragColor = orb(vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y)); }
`;

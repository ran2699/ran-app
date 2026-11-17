uniform shader content;

uniform float r0x; uniform float r0y; uniform float r0z; uniform float r0w; uniform float r0r;
uniform float r1x; uniform float r1y; uniform float r1z; uniform float r1w; uniform float r1r;
uniform float r2x; uniform float r2y; uniform float r2z; uniform float r2w; uniform float r2r;
uniform float r3x; uniform float r3y; uniform float r3z; uniform float r3w; uniform float r3r;

uniform float bevel;
uniform float refraction;
uniform float dispersion;
uniform float blurR;
uniform float tintA;
uniform float rimA;
uniform float falloff;

float sdRR(float2 p, float4 rc, float r) {
    float2 c = float2((rc.x + rc.z) * 0.5, (rc.y + rc.w) * 0.5);
    float2 h = float2((rc.z - rc.x) * 0.5, (rc.w - rc.y) * 0.5);
    float2 q = abs(p - c) - (h - r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float lum(half4 c) {
    return 0.299 * float(c.r) + 0.587 * float(c.g) + 0.114 * float(c.b);
}

half4 main(float2 p) {
    float4 rects[4];
    rects[0] = float4(r0x, r0y, r0z, r0w);
    rects[1] = float4(r1x, r1y, r1z, r1w);
    rects[2] = float4(r2x, r2y, r2z, r2w);
    rects[3] = float4(r3x, r3y, r3z, r3w);
    float radii[4];
    radii[0] = r0r;
    radii[1] = r1r;
    radii[2] = r2r;
    radii[3] = r3r;

    float bestD = 1.0e9;
    float4 hit = float4(0.0);
    float hitR = 0.0;
    bool found = false;

    for (int i = 0; i < 4; i++) {
        float4 rc = rects[i];
        if (rc.z > rc.x && rc.w > rc.y) {
            float d = sdRR(p, rc, radii[i]);
            if (d < bestD) { bestD = d; hit = rc; hitR = radii[i]; found = true; }
        }
    }
    if (!found) return content.eval(p);

    // ---- 外投影：玻璃外一圈柔和投影，让它在白底上"浮起来" ----
    if (bestD > 0.0) {
        float sh = 1.0 - clamp(bestD / max(falloff * 1.35, 1.0), 0.0, 1.0);
        sh = sh * sh;
        float drop = sh * 0.16 * rimA;
        half4 bg = content.eval(p);
        bg.rgb = bg.rgb * half(1.0 - drop);
        return bg;
    }

    // ---- 玻璃内部 ----
    float e = 1.5;
    float ddx = sdRR(p + float2(e, 0.0), hit, hitR) - sdRR(p - float2(e, 0.0), hit, hitR);
    float ddy = sdRR(p + float2(0.0, e), hit, hitR) - sdRR(p - float2(0.0, e), hit, hitR);
    float2 nrm = normalize(float2(ddx, ddy) + float2(0.0001, 0.0001));

    float din = -bestD;
    float t = clamp(din / max(bevel, 1.0), 0.0, 1.0);
    float bend = 1.0 - t;
    bend = bend * bend * bend;

    float2 base = p - nrm * refraction * bend;

    // 双环 8 tap 模糊
    half4 acc = content.eval(base) * half(2.0);
    float wsum = 2.0;
    for (int ring = 0; ring < 2; ring++) {
        float rr = blurR * (0.45 + 0.55 * float(ring));
        float phase = float(ring) * 0.3926991;
        for (int k = 0; k < 8; k++) {
            float ang = float(k) * 0.7853982 + phase;
            acc += content.eval(base + float2(cos(ang), sin(ang)) * rr);
            wsum += 1.0;
        }
    }
    half4 col = acc / half(wsum);

    float ca = dispersion * bend;
    if (ca > 0.02) {
        col.r = content.eval(base + nrm * ca).r;
        col.b = content.eval(base - nrm * ca).b;
    }

    col.rgb = mix(col.rgb, half3(1.0), half(tintA));

    // ---- 自适应边缘：背景亮→暗轮廓；背景暗→白色高光（苹果的做法）----
    half4 outside = content.eval(p + nrm * (din + 6.0));
    float bgLum = lum(outside);
    float lightBg = smoothstep(0.35, 0.75, bgLum);

    float band = smoothstep(2.6, 0.0, din) * rimA;

    // 暗背景：顶部为主的白色高光
    float topBoost = smoothstep(0.55, 0.0, p.y - hit.y);
    float specDark = band * (0.35 + 0.65 * topBoost) * (1.0 - lightBg);
    col.rgb = col.rgb + half3(half(specDark));

    // 亮背景：细暗轮廓 + 内侧顶部白线
    float outline = smoothstep(1.9, 0.0, din) * 0.18 * rimA * lightBg;
    col.rgb = col.rgb * half(1.0 - outline);
    float hairline = smoothstep(2.2, 0.0, din) * smoothstep(0.0, 6.0, din) * 0.22 * rimA * lightBg;
    col.rgb = col.rgb + half3(half(hairline));

    // 底部内暗边，增加厚度感
    float botEdge = smoothstep(7.0, 0.0, hit.w - p.y);
    col.rgb = col.rgb * half(1.0 - 0.10 * botEdge * rimA);

    return half4(col.rgb, 1.0);
}

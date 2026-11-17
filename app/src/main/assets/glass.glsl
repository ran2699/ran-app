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

    // 取最近的矩形（内外都算），以便矩形外还能做渐进羽化
    float bestD = 1.0e9;
    float4 hit = float4(0.0);
    float hitR = 0.0;
    bool found = false;

    for (int i = 0; i < 4; i++) {
        float4 rc = rects[i];
        if (rc.z > rc.x && rc.w > rc.y) {
            float d = sdRR(p, rc, radii[i]);
            if (d < bestD) {
                bestD = d;
                hit = rc;
                hitR = radii[i];
                found = true;
            }
        }
    }

    if (!found) return content.eval(p);

    // 渐进强度：矩形内为 1，向外在 falloff 距离内平滑衰减到 0
    float strength = 1.0;
    if (bestD > 0.0) {
        if (falloff <= 1.0) return content.eval(p);
        strength = 1.0 - clamp(bestD / falloff, 0.0, 1.0);
        strength = strength * strength * (3.0 - 2.0 * strength);
    }
    if (strength <= 0.004) return content.eval(p);

    float e = 1.5;
    float ddx = sdRR(p + float2(e, 0.0), hit, hitR) - sdRR(p - float2(e, 0.0), hit, hitR);
    float ddy = sdRR(p + float2(0.0, e), hit, hitR) - sdRR(p - float2(0.0, e), hit, hitR);
    float2 nrm = normalize(float2(ddx, ddy) + float2(0.0001, 0.0001));

    float din = max(0.0, -bestD);
    float t = clamp(din / max(bevel, 1.0), 0.0, 1.0);
    float bend = 1.0 - t;
    bend = bend * bend * bend;

    float2 base = p - nrm * refraction * bend * strength;

    half4 src = content.eval(p);
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

    float ca = dispersion * bend * strength;
    if (ca > 0.02) {
        col.r = content.eval(base + nrm * ca).r;
        col.b = content.eval(base - nrm * ca).b;
    }

    // 与原始画面按强度混合，羽化区自然过渡
    col = mix(src, col, half(strength));

    col.rgb = mix(col.rgb, half3(1.0), half(tintA * strength));

    float topEdge = smoothstep(7.0, 0.0, p.y - hit.y);
    float rim = smoothstep(2.4, 0.0, din) * rimA * (0.42 + 0.58 * topEdge) * strength;
    col.rgb = col.rgb + half3(half(rim));

    // 1px 灰描边：白底上也能看出玻璃的轮廓（苹果玻璃在浅色背景就靠这个）
    float outline = smoothstep(1.8, 0.0, din) * 0.13 * strength;
    col.rgb = col.rgb * half(1.0 - outline);

    float botEdge = smoothstep(6.0, 0.0, hit.w - p.y);
    col.rgb = col.rgb * half(1.0 - 0.12 * botEdge * rimA * strength);

    return half4(col.rgb, 1.0);
}

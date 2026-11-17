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

    float4 hit = float4(0.0);
    float hitR = 0.0;
    float hitD = 0.0;
    bool found = false;

    for (int i = 0; i < 4; i++) {
        float4 rc = rects[i];
        if (rc.z > rc.x && rc.w > rc.y) {
            float d = sdRR(p, rc, radii[i]);
            if (d < 0.0 && !found) {
                hit = rc;
                hitR = radii[i];
                hitD = d;
                found = true;
            }
        }
    }

    if (!found) {
        return content.eval(p);
    }

    float e = 1.5;
    float ddx = sdRR(p + float2(e, 0.0), hit, hitR) - sdRR(p - float2(e, 0.0), hit, hitR);
    float ddy = sdRR(p + float2(0.0, e), hit, hitR) - sdRR(p - float2(0.0, e), hit, hitR);
    float2 nrm = normalize(float2(ddx, ddy) + float2(0.0001, 0.0001));

    float t = clamp(-hitD / max(bevel, 1.0), 0.0, 1.0);
    float bend = (1.0 - t) * (1.0 - t);

    float2 base = p - nrm * refraction * bend;

    half4 acc = content.eval(base);
    float wsum = 1.0;
    for (int k = 0; k < 8; k++) {
        float ang = float(k) * 0.7853982;
        float2 off = float2(cos(ang), sin(ang)) * blurR;
        acc += content.eval(base + off);
        wsum += 1.0;
    }
    half4 col = acc / half(wsum);

    float ca = dispersion * bend;
    if (ca > 0.01) {
        col.r = content.eval(base + nrm * ca).r;
        col.b = content.eval(base - nrm * ca).b;
    }

    col.rgb = mix(col.rgb, half3(1.0), half(tintA));

    float edge = (1.0 - smoothstep(0.0, 3.2, -hitD)) * rimA;
    col.rgb = col.rgb + half3(half(edge));

    return half4(col.rgb, 1.0);
}

uniform shader content;
uniform float4 rects[8];
uniform float  radii[8];
uniform int    count;
uniform float  bevel;
uniform float  refraction;
uniform float  dispersion;
uniform float  blurR;
uniform float  tintA;
uniform float  rimA;

float sdRR(float2 p, float2 c, float2 hs, float r) {
    float2 q = abs(p - c) - (hs - r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 p) {
    float hitD = 0.0;
    float4 hit = float4(-1.0, -1.0, -1.0, -1.0);
    float hitR = 0.0;

    for (int i = 0; i < 8; i++) {
        if (i < count) {
            float4 rc = rects[i];
            if (rc.z > rc.x) {
                float2 cc = float2((rc.x + rc.z) * 0.5, (rc.y + rc.w) * 0.5);
                float2 hh = float2((rc.z - rc.x) * 0.5, (rc.w - rc.y) * 0.5);
                float d = sdRR(p, cc, hh, radii[i]);
                if (d < 0.0 && hit.x < 0.0) { hit = rc; hitR = radii[i]; hitD = d; }
            }
        }
    }

    if (hit.x < 0.0) return content.eval(p);

    float2 cc = float2((hit.x + hit.z) * 0.5, (hit.y + hit.w) * 0.5);
    float2 hh = float2((hit.z - hit.x) * 0.5, (hit.w - hit.y) * 0.5);

    float e = 1.5;
    float ddx = sdRR(p + float2(e, 0.0), cc, hh, hitR) - sdRR(p - float2(e, 0.0), cc, hh, hitR);
    float ddy = sdRR(p + float2(0.0, e), cc, hh, hitR) - sdRR(p - float2(0.0, e), cc, hh, hitR);
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

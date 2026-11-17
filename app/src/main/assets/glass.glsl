// RAN Liquid Glass · v2
// 约定：长度类 uniform 单位 dp（内部乘 dens 转设备像素）；r*x..r5w 矩形已是设备像素。
uniform shader content;

uniform float r0x; uniform float r0y; uniform float r0z; uniform float r0w; uniform float r0r;
uniform float r1x; uniform float r1y; uniform float r1z; uniform float r1w; uniform float r1r;
uniform float r2x; uniform float r2y; uniform float r2z; uniform float r2w; uniform float r2r;
uniform float r3x; uniform float r3y; uniform float r3z; uniform float r3w; uniform float r3r;
uniform float r4x; uniform float r4y; uniform float r4z; uniform float r4w; uniform float r4r;
uniform float r5x; uniform float r5y; uniform float r5z; uniform float r5w; uniform float r5r;

uniform float dens;
uniform float bevel;
uniform float refraction;
uniform float dispersion;
uniform float blurR;
uniform float tintA;
uniform float rimA;
uniform float falloff;
uniform float shadowA;
uniform float shadowY;
uniform float shadowR;
uniform float satA;
uniform float detail;
uniform float edgeA;
uniform float innerH;

float sdRR(float2 p, float4 rc, float r) {
    float2 c = float2((rc.x + rc.z) * 0.5, (rc.y + rc.w) * 0.5);
    float2 h = float2((rc.z - rc.x) * 0.5, (rc.w - rc.y) * 0.5);
    float rr = min(r, min(h.x, h.y));
    float2 q = abs(p - c) - (h - rr);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rr;
}

float lum(half4 c) { return 0.299 * float(c.r) + 0.587 * float(c.g) + 0.114 * float(c.b); }

float hash(float n) { return fract(sin(n * 12.9898) * 43758.5453); }

half4 main(float2 p) {
    float4 rects[6];
    rects[0] = float4(r0x, r0y, r0z, r0w);
    rects[1] = float4(r1x, r1y, r1z, r1w);
    rects[2] = float4(r2x, r2y, r2z, r2w);
    rects[3] = float4(r3x, r3y, r3z, r3w);
    rects[4] = float4(r4x, r4y, r4z, r4w);
    rects[5] = float4(r5x, r5y, r5z, r5w);
    float radii[6];
    radii[0] = r0r; radii[1] = r1r; radii[2] = r2r;
    radii[3] = r3r; radii[4] = r4r; radii[5] = r5r;

    float bestD = 1.0e9;
    float4 hit = float4(0.0);
    float hitR = 0.0;
    bool found = false;
    for (int i = 0; i < 6; i++) {
        float4 rc = rects[i];
        if (rc.z > rc.x + 1.0 && rc.w > rc.y + 1.0) {
            float d = sdRR(p, rc, radii[i]);
            if (d < bestD) { bestD = d; hit = rc; hitR = radii[i]; found = true; }
        }
    }
    if (!found) return content.eval(p);

    float D = dens > 0.5 ? dens : 3.0;
    float bev = max(bevel, 2.0) * D;
    float refr = max(refraction, 0.0) * D;
    float disp = max(dispersion, 0.0) * D;
    float blur = max(blurR, 1.0) * D;
    float fll = max(falloff, 4.0) * D;
    float shY = shadowY * D;
    float shR = max(shadowR, 0.0) * D;

    /* ---------- 玻璃之外：两层投影（宽环境影 + 近边接触影） ---------- */
    if (bestD > 0.0) {
        half4 bg = content.eval(p);
        float4 rcs = float4(hit.x, hit.y + shY, hit.z, hit.w + shY);
        float dS = sdRR(p, rcs, hitR + shR * 0.5);
        float t1 = clamp(dS / fll, 0.0, 1.0);
        float a1 = shadowA * (1.0 - t1) * (1.0 - t1);
        float t2 = clamp(dS / (6.0 * D), 0.0, 1.0);
        float a2 = shadowA * 0.5 * (1.0 - t2) * (1.0 - t2);
        float a = clamp(a1 + a2, 0.0, 0.34);
        bg.rgb = bg.rgb * half(1.0 - a);
        return bg;
    }

    /* ---------- 内部：边缘法线 ---------- */
    float e = 1.5;
    float ddx = sdRR(p + float2(e, 0.0), hit, hitR) - sdRR(p - float2(e, 0.0), hit, hitR);
    float ddy = sdRR(p + float2(0.0, e), hit, hitR) - sdRR(p - float2(0.0, e), hit, hitR);
    float2 nrm = normalize(float2(ddx, ddy) + float2(1.0e-4));

    float din = -bestD;
    float bb = clamp(din / bev, 0.0, 1.0);
    float bend = 1.0 - bb;
    bend = bend * bend * bend;

    // 向外采样：把矩形外侧的内容压进边缘窄带（透镜边缘的压缩感）。
    // 若写成向内（p - nrm*...），映射在边缘会折返，文字被折叠成双影条纹。
    float2 base = p + nrm * (refr * bend);

    /* ---------- 模糊核：中心 + 四环 12 tap（49 次采样）
       环半径 0.2~1.0×blurR，角度与半径各加确定性抖动，避免 16 点星芒和斑点 ---------- */
    half4 acc = content.eval(base) * half(1.6);
    float wsum = 1.6;
    for (int ring = 0; ring < 4; ring++) {
        float ph = float(ring) * 0.3141;
        float rr = blur * (0.2 + 0.8 * (float(ring) / 3.0));
        for (int k = 0; k < 12; k++) {
            float j = hash(float(k * 3 + ring * 31));
            float ang = (float(k) * 0.0833333 + j * 0.06) * 6.2831853 + ph;
            acc += content.eval(base + float2(cos(ang), sin(ang)) * (rr * (0.85 + 0.30 * j)));
            wsum += 1.0;
        }
    }
    half4 col = acc / half(wsum);
    if (detail > 0.001) col = mix(col, content.eval(p), half(detail));

    /* ---------- 色散：只在最外侧窄带走，幅度控制在亚像素级 ---------- */
    float ca = disp * bend * bend;
    if (ca > 0.05) {
        col.r = half(mix(float(col.r), float(content.eval(base + nrm * ca).r), 0.45));
        col.b = half(mix(float(col.b), float(content.eval(base - nrm * ca).b), 0.45));
    }

    if (satA > 0.0001 || satA < -0.0001) {
        float l = lum(col);
        col.rgb = mix(half3(half(l)), col.rgb, half(1.0 + satA));
    }
    col.rgb = mix(col.rgb, half3(1.0), half(clamp(tintA, 0.0, 1.0)));

    /* ---------- 边缘：亮底走暗描边，暗底走白色镜面 ---------- */
    half4 outside = content.eval(p + nrm * (din + 3.0 * D));
    float lightBg = smoothstep(0.32, 0.72, lum(outside));

    float ringK = smoothstep(1.7 * D, 0.0, din);
    float topW = smoothstep(0.5, 0.0, (p.y - hit.y) / max(hit.w - hit.y, 1.0));
    float spec = ringK * rimA * (0.40 + 0.60 * topW) * (1.0 - lightBg);
    col.rgb = col.rgb + half3(half(clamp(spec, 0.0, 1.0) * 0.70));

    // 亮底：紧贴边缘的一道细暗线，把玻璃从白底上分出来
    float outline = smoothstep(1.1 * D, 0.0, din) * edgeA * lightBg;
    col.rgb = col.rgb * half(1.0 - clamp(outline, 0.0, 0.5) * 0.42);

    // 亮底：细白线在暗线内侧一拍，制造玻璃边厚
    float hair = smoothstep(3.0 * D, 2.0 * D, din) * smoothstep(1.0 * D, 2.0 * D, din);
    col.rgb = col.rgb + half3(half(0.26 * rimA * lightBg * hair));

    float vg = clamp((p.y - hit.y) / max(hit.w - hit.y, 1.0), 0.0, 1.0);

    // 内侧柔和暗边：四周略暗、越靠边越暗，给出球面/厚度感
    col.rgb = col.rgb * half(1.0 - 0.09 * innerH * exp(-din / (5.0 * D)));

    // 底边反光（暗底上最明显，白底上几乎看不见）
    col.rgb = col.rgb + half3(half(smoothstep(0.88, 1.0, vg) * 0.10 * rimA));

    // 竖向厚度：底部略暗
    col.rgb = col.rgb * half(1.0 - 0.10 * innerH * vg);

    return half4(col.rgb, 1.0);
}
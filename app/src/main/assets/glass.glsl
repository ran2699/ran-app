// RAN Glass v4 · 苹果 Liquid Glass 的分级重构
//
// 参考：Apple WWDC25「Meet Liquid Glass」的公开分析（位移图 + Snell 折射、凸 squircle 剖面），
// 以及 PrismalAGSL / Kyant0 AndroidLiquidGlass 的 AGSL 实现。
//
// 一条 shader，三种用途（由 mode 决定，Kotlin 侧建 3 个 RuntimeShader 实例串成 RenderEffect 链）：
//   mode=0 模糊趟：只对玻璃内（半径外扩 dilate）做 16 tap 圆盘模糊，玻璃外原样输出
//   mode=1 材质趟：位移折射 + 白色着色 + 边缘高光 + 细描边 + 外部投影
//
// 为什么必须多趟：单趟 64 个单点采样在密集文字上不收敛，会出现"织纹/砂砾"。
// 实测 RMSE 对真高斯：单趟64采样 0.033 → 三级16采样 0.0045（7 倍好，采样更少）。
// 每趟在玻璃外做恒等，所以页面其余部分不受影响。
//
// 关键数值（都来自实测，不调回退）：
//  1. 位移剖面 lift = 1-sqrt(1-(1-t)^2)：边缘最大、向内平滑归零。三次衰减会在边缘斜率失控 → 折叠双影。
//  2. 模糊尽快到位（t=0.02→0.35 满）：折射带必须是窄的，否则边缘出现被压扁的锐利文字拖影。
//  3. 色散按 (|cx|/hx)*(|cy|/hy) 加权且按权重混入 —— 整通道替换会让模糊区出现粉色鬼影。

uniform shader content;

uniform float dens;
uniform float mode;      // 0=模糊趟 1=材质趟
uniform float dilate;    // px 模糊趟向外扩张量（覆盖折射采样范围）

uniform float r0x; uniform float r0y; uniform float r0z; uniform float r0w; uniform float r0r;
uniform float r1x; uniform float r1y; uniform float r1z; uniform float r1w; uniform float r1r;
uniform float r2x; uniform float r2y; uniform float r2z; uniform float r2w; uniform float r2r;
uniform float r3x; uniform float r3y; uniform float r3z; uniform float r3w; uniform float r3r;
uniform float r4x; uniform float r4y; uniform float r4z; uniform float r4w; uniform float r4r;
uniform float r5x; uniform float r5y; uniform float r5z; uniform float r5w; uniform float r5r;

uniform float bevel;
uniform float refraction;
uniform float disp;
uniform float blurR;
uniform float tintA;
uniform float rimA;
uniform float crispA;
uniform float shadowA;
uniform float shadowY;
uniform float shadowR;
uniform float lightA;

float sdRR(float2 p, float2 c, float2 h, float r) {
    float rr = min(r, min(h.x, h.y));
    float2 q = abs(p - c) - (h - rr);
    return length(max(q, float2(0.0))) - rr;
}

float2 gradRR(float2 p, float2 c, float2 h, float r) {
    float rr = min(r, min(h.x, h.y));
    float2 d = p - c;
    float2 cc = abs(d) - (h - float2(rr));
    float2 g;
    if (cc.x >= 0.0 || cc.y >= 0.0) {
        g = normalize(max(cc, float2(0.0)) + float2(1e-5));
    } else {
        float gx = step(cc.y, cc.x);
        g = float2(gx, 1.0 - gx);
    }
    return normalize(sign(d) * g + float2(1e-6));
}

half4 main(float2 p) {
    float4 rc[6];
    rc[0] = float4(r0x, r0y, r0z, r0w);
    rc[1] = float4(r1x, r1y, r1z, r1w);
    rc[2] = float4(r2x, r2y, r2z, r2w);
    rc[3] = float4(r3x, r3y, r3z, r3w);
    rc[4] = float4(r4x, r4y, r4z, r4w);
    rc[5] = float4(r5x, r5y, r5z, r5w);
    float rd[6];
    rd[0] = r0r; rd[1] = r1r; rd[2] = r2r; rd[3] = r3r; rd[4] = r4r; rd[5] = r5r;

    float bestD = 1.0e9;
    float4 hit = float4(0.0);
    float hitR = 0.0;
    bool found = false;
    for (int i = 0; i < 6; i++) {
        if (rc[i].z > rc[i].x && rc[i].w > rc[i].y) {
            float2 c = float2((rc[i].x + rc[i].z) * 0.5, (rc[i].y + rc[i].w) * 0.5);
            float2 h = float2((rc[i].z - rc[i].x) * 0.5, (rc[i].w - rc[i].y) * 0.5);
            float d = sdRR(p, c, h, rd[i]);
            if (d < bestD) { bestD = d; hit = rc[i]; hitR = rd[i]; found = true; }
        }
    }
    if (!found) return content.eval(p);

    bool blurMode = mode < 0.5;

    if (blurMode) {
        if (bestD > dilate) return content.eval(p);
        float BR = blurR * dens;
        half4 acc = half4(0.0);
        float wsum = 0.0;
        for (int k = 0; k < 16; k++) {
            float fi = float(k) + 0.5;
            float ang = fi * 2.39996323;
            float rad = BR * sqrt(fi / 16.0);
            float w = exp(-1.2 * (fi / 16.0));
            acc += content.eval(p + float2(cos(ang), sin(ang)) * rad) * half(w);
            wsum += w;
        }
        half4 avg = acc / half(wsum);
        return half4(avg.rgb, half(1.0));
    }

    float2 cen = float2((hit.x + hit.z) * 0.5, (hit.y + hit.w) * 0.5);
    float2 halfSz = float2((hit.z - hit.x) * 0.5, (hit.w - hit.y) * 0.5);

    if (bestD > 0.0) {
        float sdUp = sdRR(p - float2(0.0, shadowY * dens), cen, halfSz, hitR);
        float far = max(shadowR * dens, 4.0);
        float sh = 1.0 - smoothstep(0.0, far * 1.6, sdUp);
        sh = sh * sh;
        float below = smoothstep(-0.35, 0.75, (p.y - cen.y) / max(halfSz.y, 1.0));
        float a = sh * shadowA * (0.45 + 0.55 * below);
        half4 bg = content.eval(p);
        bg.rgb = bg.rgb * half(1.0 - a);
        return bg;
    }

    float2 g = gradRR(p, cen, halfSz, hitR);
    float din = -bestD;
    float H = max(bevel * dens, 1.0);
    float t = clamp(din / H, 0.0, 1.0);
    float q = 1.0 - t;
    float lift = 1.0 - sqrt(max(0.0, 1.0 - q * q));

    float A = min(refraction * dens, H * 0.72);
    float2 sc = p + g * (A * lift);

    half4 col = content.eval(sc);

    float cw = clamp(abs((p.x - cen.x) / max(halfSz.x, 1.0)) *
                     abs((p.y - cen.y) / max(halfSz.y, 1.0)), 0.0, 1.0);
    if (disp > 0.05 && cw > 0.05) {
        float2 dv = g * (disp * lift * cw);
        half4 dc = half4(content.eval(sc + dv).r, col.g, content.eval(sc - dv).b, half(1.0));
        col = mix(col, dc, half(cw * 0.55));
    }

    col.rgb = mix(col.rgb, half3(1.0), half(tintA));

    float2 L = float2(cos(lightA), sin(lightA));
    float band = 1.0 - smoothstep(0.0, max(2.2 * dens, 2.0), din);
    float spec = band * pow(abs(dot(g, L)), 2.2) * rimA;
    col.rgb = col.rgb + half3(half(spec * 0.85));

    float outl = (1.0 - smoothstep(0.0, 1.3 * dens, din)) * crispA;
    col.rgb = col.rgb * half(1.0 - outl);

    float topW = clamp(1.0 - (p.y - hit.y) / (9.0 * dens), 0.0, 1.0);
    col.rgb = col.rgb + half3(half(0.08 * topW * rimA));
    float botW = clamp(1.0 - (hit.w - p.y) / (7.0 * dens), 0.0, 1.0);
    col.rgb = col.rgb * half(1.0 - 0.10 * botW * rimA);

    return half4(col.rgb, half(1.0));
}

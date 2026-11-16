              module,
              entryPoint: "vs",
              buffers: [
                {
                  arrayStride: 8,
                  attributes: [
                    { shaderLocation: 0, offset: 0, format: "float32x2" },
                  ],
                },
              ],
            },
            fragment: {
              module,
              entryPoint: "fs",
              targets: [
                {
                  format: "rgba8unorm",
                  blend: {
                    color: {
                      srcFactor: "one",
                      dstFactor: "one-minus-src-alpha",
                    },
                    alpha: {
                      srcFactor: "one",
                      dstFactor: "one-minus-src-alpha",
                    },
                  },
                },
              ],
            },
            primitive: { topology: "triangle-list" },
          });
        }
        WebGPUBackend._externalFrames ||= new WeakMap();
        let entry = WebGPUBackend._externalFrames.get(source);
        if (!entry || entry.device !== device || entry.frame !== renderFrame) {
          entry = {
            device,
            frame: renderFrame,
            texture: device.importExternalTexture({ source }),
          };
          WebGPUBackend._externalFrames.set(source, entry);
        }
        return {
          bindGroup: device.createBindGroup({
            layout: this._externalVideoLayout,
            entries: [
              { binding: 0, resource: entry.texture },
              { binding: 1, resource: this._sampler },
            ],
          }),
          external: true,
        };
      } catch (e) {
        return null;
      }
    }

    blitVideo(vid, dstX, dstY, dstW, dstH, srcRect, source = vid) {
      if (!this.texture) return false;
      const device = this.device;
      const external = this.getExternalVideo(source);
      if (external) {
        if (!this._externalVideoUniform) {
          this._externalVideoUniform = device.createBuffer({
            size: 48,
            usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
          });
          this._externalVideoGroup = device.createBindGroup({
            layout: this._dynamicUniformLayout,
            entries: [
              {
                binding: 0,
                resource: { buffer: this._externalVideoUniform, size: 48 },
              },
            ],
          });
        }
        device.queue.writeBuffer(
          this._externalVideoUniform,
          0,
          new Float32Array([
            srcRect.u,
            srcRect.v,
            srcRect.uw,
            srcRect.vh,
            1,
            0,
            0,
            0,
            dstX,
            dstY,
            dstW,
            dstH,
          ]),
        );
        const encoder = device.createCommandEncoder();
        const pass = encoder.beginRenderPass({
          colorAttachments: [
            {
              view: this.texture.createView(),
              loadOp: "load",
              storeOp: "store",
            },
          ],
        });
        pass.setPipeline(this._externalVideoPipe);
        pass.setVertexBuffer(0, this._vb);
        pass.setBindGroup(0, external.bindGroup);
        pass.setBindGroup(1, this._externalVideoGroup, [0]);
        pass.setViewport(dstX, dstY, dstW, dstH, 0, 1);
        pass.draw(6);
        pass.end();
        device.queue.submit([encoder.finish()]);
        this.renderer._invalidateDynamicRegion(dstX, dstY, dstW, dstH);
        return true;
      }
      if (uploadScaledVideo(this, vid, dstX, dstY, dstW, dstH, srcRect, source))
        return true;
      const vw = source.displayWidth || vid.videoWidth;
      const vh = source.displayHeight || vid.videoHeight;
      if (!vw || !vh) return false;

      try {
        if (!this._vCanvas) {
          this._vCanvas = document.createElement("canvas");
          this._vCtx = this._vCanvas.getContext("2d");
        }
        if (this._vCanvas.width !== vw || this._vCanvas.height !== vh) {
          this._vCanvas.width = vw;
          this._vCanvas.height = vh;
        }
        this._vCtx.drawImage(source, 0, 0, vw, vh);

        if (
          !this._videoTex ||
          this._videoTexW !== vw ||
          this._videoTexH !== vh
        ) {
          if (this._videoTex) this._videoTex.destroy();
          this._videoTex = device.createTexture({
            size: [vw, vh],
            format: "rgba8unorm",
            usage:
              GPUTextureUsage.TEXTURE_BINDING |
              GPUTextureUsage.COPY_DST |
              GPUTextureUsage.RENDER_ATTACHMENT,
          });
          this._videoTexW = vw;
          this._videoTexH = vh;
          this._videoBindGroup = device.createBindGroup({
            layout: this._texSampLayout,
            entries: [
              { binding: 0, resource: this._videoTex.createView() },
              { binding: 1, resource: this._sampler },
            ],
          });
        }

        device.queue.copyExternalImageToTexture(
          { source: this._vCanvas },
          { texture: this._videoTex },
          { width: vw, height: vh },
        );
        device.queue.writeBuffer(
          this._blitUniform,
          0,
          new Float32Array([srcRect.u, srcRect.v, srcRect.uw, srcRect.vh]),
        );

        const enc = device.createCommandEncoder();
        const pass = enc.beginRenderPass({
          colorAttachments: [
            {
              view: this.texture.createView(),
              loadOp: "load",
              storeOp: "store",
            },
          ],
        });
        pass.setPipeline(this._blitPipe);
        pass.setVertexBuffer(0, this._vb);
        pass.setBindGroup(0, this._videoBindGroup);
        pass.setBindGroup(1, this._blitBindGroup);
        pass.setViewport(
          dstX,
          dstY,
          Math.max(1, dstW),
          Math.max(1, dstH),
          0,
          1,
        );
        pass.draw(6);
        pass.end();
        device.queue.submit([enc.finish()]);
        this.renderer._invalidateDynamicRegion(dstX, dstY, dstW, dstH);
        return true;
      } catch (e) {
        return false;
      }
    }

    compositeVideo(layers, x, y, width, height) {
      if (this._videoFadeFailed || !this.texture) return false;
      const device = this.device;
      this._videoContents ||= new Map();
      try {
        const size = layers.length * 256;
        if (!this._videoFadeUniform || this._videoFadeUniform.size < size) {
          if (this._videoFadeUniform) this._videoFadeUniform.destroy();
          this._videoFadeUniform = device.createBuffer({
            size: Math.max(size, 2048),
            usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
          });
          this._videoFadeGroup = device.createBindGroup({
            layout: this._dynamicUniformLayout,
            entries: [
              {
                binding: 0,
                resource: { buffer: this._videoFadeUniform, size: 48 },
              },
            ],
          });
        }
        const uniforms = new Float32Array(layers.length * 64);
        const entries = layers.map(({ content, opacity }, i) => {
          if (content.external) {
            uniforms.set(content.uv, i * 64);
            uniforms[i * 64 + 4] = opacity;
            uniforms.set(
              [
                content.clip[0],
                content.clip[1],
                content.clip[2],
                content.clip[3],
              ],
              i * 64 + 8,
            );
            return content.external;
          }
          uniforms[i * 64] = opacity;
          let entry = this._videoContents.get(content);
          if (
            !entry ||
            entry.texture.width !== width ||
            entry.texture.height !== height
          ) {
            if (entry) entry.texture.destroy();
            const texture = device.createTexture({
              size: [width, height],
              format: "rgba8unorm",
              usage:
                GPUTextureUsage.TEXTURE_BINDING |
                GPUTextureUsage.COPY_DST |
                GPUTextureUsage.RENDER_ATTACHMENT,
            });
            entry = {
              texture,
              version: -1,
              bindGroup: device.createBindGroup({
                layout: this._texSampLayout,
                entries: [
                  { binding: 0, resource: texture.createView() },
                  { binding: 1, resource: this._sampler },
                ],
              }),
            };
            this._videoContents.set(content, entry);
          }
          if (entry.version !== content.version) {
            device.queue.copyExternalImageToTexture(
              { source: content.canvas },
              { texture: entry.texture, premultipliedAlpha: true },
              [width, height],
            );
            entry.version = content.version;
          }
          return entry;
        });
        device.queue.writeBuffer(this._videoFadeUniform, 0, uniforms);
        if (
          !this._videoCompositeTexture ||
          this._videoCompositeTexture.width < width ||
          this._videoCompositeTexture.height < height
        ) {
          const w = Math.max(width, this._videoCompositeTexture?.width || 0);
          const h = Math.max(height, this._videoCompositeTexture?.height || 0);
          if (this._videoCompositeTexture)
            this._videoCompositeTexture.destroy();
          this._videoCompositeTexture = device.createTexture({
            size: [w, h],
            format: "rgba8unorm",
            usage:
              GPUTextureUsage.RENDER_ATTACHMENT |
              GPUTextureUsage.TEXTURE_BINDING,
          });
          this._videoCompositeGroup = device.createBindGroup({
            layout: this._texSampLayout,
            entries: [
              {
                binding: 0,
                resource: this._videoCompositeTexture.createView(),
              },
              { binding: 1, resource: this._sampler },
            ],
          });
        }
        device.queue.writeBuffer(
          this._blitUniform,
          0,
          new Float32Array([
            0,
            0,
            width / this._videoCompositeTexture.width,
            height / this._videoCompositeTexture.height,
          ]),
        );
        const encoder = device.createCommandEncoder();
        const pass = encoder.beginRenderPass({
          colorAttachments: [
            {
              view: this._videoCompositeTexture.createView(),
              loadOp: "clear",
              clearValue: { r: 0, g: 0, b: 0, a: 0 },
              storeOp: "store",
            },
          ],
        });
        pass.setPipeline(this._videoFadePipe);
        pass.setVertexBuffer(0, this._vb);
        pass.setViewport(0, 0, width, height, 0, 1);
        entries.forEach((entry, i) => {
          pass.setPipeline(
            entry.external ? this._externalVideoPipe : this._videoFadePipe,
          );
          pass.setBindGroup(0, entry.bindGroup);
          pass.setBindGroup(1, this._videoFadeGroup, [i * 256]);
          pass.draw(6);
        });
        pass.end();
        const resolve = encoder.beginRenderPass({
          colorAttachments: [
            {
              view: this.texture.createView(),
              loadOp: "load",
              storeOp: "store",
            },
          ],
        });
        resolve.setPipeline(this._videoResolvePipe);
        resolve.setVertexBuffer(0, this._vb);
        resolve.setBindGroup(0, this._videoCompositeGroup);
        resolve.setBindGroup(1, this._blitBindGroup);
        resolve.setViewport(x, y, width, height, 0, 1);
        resolve.draw(6);
        resolve.end();
        device.queue.submit([encoder.finish()]);
        this.renderer._invalidateDynamicRegion(x, y, width, height);
        return true;
      } catch (e) {
        this._videoFadeFailed = true;
        return false;
      }
    }

    beginFrame(width, height, time, composite = false) {
      this._compositeActive = composite;
      if (
        composite &&
        (!this._compositeTexture ||
          this._compositeTexture.width !== width ||
          this._compositeTexture.height !== height)
      ) {
        if (this._compositeTexture) this._compositeTexture.destroy();
        this._compositeTexture = this.device.createTexture({
          size: [width, height],
          format: this.format,
          usage:
            GPUTextureUsage.RENDER_ATTACHMENT |
            GPUTextureUsage.COPY_SRC |
            GPUTextureUsage.TEXTURE_BINDING,
        });
      }
      this._frameTime = time;
      this._drawQueue.length = 0;
      this._enc = this.device.createCommandEncoder();
    }

    drawLens(lens, p) {
      if (p.shadow && !this._shadows)
        this._shadows = new LiquidShadowCache(this);
      const shadow = this._shadows ? this._shadows.get(lens, p) : null;
      p.shadowEntry = shadow;
      const cx = Math.max(0, p.x);
      const cy = Math.max(0, this.canvas.height - p.y - p.h);
      const cw = Math.min(this.canvas.width, p.x + p.w) - cx;
      const ch = Math.min(this.canvas.height, this.canvas.height - p.y) - cy;
      if (cw <= 0 || ch <= 0) return;
      p = {
        ...p,
        w: cw,
        h: ch,
        subX: p.subX - (cx - p.x),
        subY: p.subY - Math.max(0, -p.y),
      };
      this._drawQueue.push({ p, x: cx, y: cy, w: cw, h: ch });
    }

    endFrame() {
      const device = this.device;
      const draws = this._drawQueue;

      this._ensureUniformCapacity(Math.max(1, draws.length));

      if (draws.length) {
        const data = new Float32Array(GPU_UNIFORM_FLOATS * draws.length);
        for (let i = 0; i < draws.length; i++) {
          const p = draws[i].p;
          const o = i * GPU_UNIFORM_FLOATS;
          if (draws[i].content) {
            data.set(p.uv, o);
            continue;
          }
          data[o] = p.w;
          data[o + 1] = p.h;
          data[o + 2] = p.texW;
          data[o + 3] = p.texH;
          data[o + 4] = p.bounds[0];
          data[o + 5] = p.bounds[1];
          data[o + 6] = p.bounds[2];
          data[o + 7] = p.bounds[3];
          data[o + 8] = p.subX;
          data[o + 9] = p.subY;
          data[o + 10] = p.boxW;
          data[o + 11] = p.boxH;
          data[o + 12] = p.refraction;
          data[o + 13] = p.aberration;
          data[o + 14] = p.bevelDepth;
          data[o + 15] = p.bevelWidth;
          data[o + 16] = p.frost;
          data[o + 17] = p.radius;
          data[o + 18] = this._frameTime;
          data[o + 19] = p.specular;
          data[o + 20] = p.revealProgress;
          data[o + 21] = p.revealType;
          data[o + 22] = p.tiltX;
          data[o + 23] = p.tiltY;
          data[o + 24] = p.magnify;
          data[o + 28] = p.tint[0];
          data[o + 29] = p.tint[1];
          data[o + 30] = p.tint[2];
          data[o + 31] = p.tint[3];
          data.set(p.stackMapping, o + 32);
          data.set(p.interaction, o + 40);
          data[o + 44] = p.interactionRadius;
          data.set(p.shadowEntry ? p.shadowMapping : INTERACTION_OFF, o + 48);
          if (p.stackRegion) {
            const r = p.stackRegion;
            data[o + 36] = r.x / this.canvas.width;
            data[o + 37] =
              (this.canvas.height - r.y - r.h) / this.canvas.height;
            data[o + 38] = r.w / this.canvas.width;
            data[o + 39] = r.h / this.canvas.height;
          }
        }
        device.queue.writeBuffer(this._uniformBuf, 0, data);
      }

      if (
        draws.some((d) => d.p.stackRegion) &&
        (!this._stackTexture ||
          this._stackTexture.width !== this.canvas.width ||
          this._stackTexture.height !== this.canvas.height)
      ) {
        if (this._stackTexture) this._stackTexture.destroy();
        this._stackTexture = device.createTexture({
          size: [this.canvas.width, this.canvas.height],
          format: this.format,
          usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST,
        });
        this._lensBindGroup = null;
      }
      if (!this._lensBindGroup || this._lensSnapshot !== this.texture) {
        this._lensSnapshot = this.texture;
        this._lensBindGroup = device.createBindGroup({
          layout: this._lensTexLayout,
          entries: [
            { binding: 0, resource: this.texture.createView() },
            { binding: 1, resource: this._sampler },
            {
              binding: 2,
              resource: (this._stackTexture || this.texture).createView(),
            },
            { binding: 3, resource: this.texture.createView() },
          ],
        });
      }
      const presentationTexture = this.ctx.getCurrentTexture();
      if (
        !this._outputTexture ||
        this._outputTexture.width !== this.canvas.width ||
        this._outputTexture.height !== this.canvas.height
      ) {
        if (this._outputTexture) this._outputTexture.destroy();
        this._outputTexture = device.createTexture({
          size: [this.canvas.width, this.canvas.height],
          format: this.format,
          usage:
            GPUTextureUsage.RENDER_ATTACHMENT |
            GPUTextureUsage.COPY_SRC |
            GPUTextureUsage.TEXTURE_BINDING,
        });
      }
      const visibleTexture = this._outputTexture;
      const visibleView = visibleTexture.createView();
      const target = this._compositeActive
        ? this._compositeTexture
        : visibleTexture;
      const view = this._compositeActive ? target.createView() : visibleView;
      const beginPass = (loadOp, output = view) => {
        const pass = this._enc.beginRenderPass({
          colorAttachments: [
            {
              view: output,
              clearValue: { r: 0, g: 0, b: 0, a: 0 },
              loadOp,
              storeOp: "store",
            },
          ],
        });
        pass.setPipeline(this._lensPipe);
        pass.setVertexBuffer(0, this._vb);
        pass.setBindGroup(0, this._lensBindGroup);
        return pass;
      };
      if (this._compositeActive) beginPass("clear", visibleView).end();
      let pass = beginPass("clear");
      for (let i = 0; i < draws.length; i++) {
        const d = draws[i];
        if (d.content) {
          if (d.backdrop) {
            pass.end();
            this._backdrops ||= new LiquidBackdropFilter(this);
            this._backdrops.draw(d.backdrop.content, d.backdrop.rect, d.p);
            pass = beginPass("load");
          }
          pass.setPipeline(this._contentPipe);
          pass.setBindGroup(0, d.content.bindGroup);
          pass.setBindGroup(1, this._uniformBindGroup, [i * 256]);
          pass.setViewport(d.x, d.y, d.w, d.h, 0, 1);
          pass.draw(6);
          pass.setPipeline(this._lensPipe);
          continue;
        }
        const r = d.p.stackRegion;
        if (r) {
          pass.end();
          const origin = [r.x, this.canvas.height - r.y - r.h];
          this._enc.copyTextureToTexture(
            { texture: target, origin },
            { texture: this._stackTexture, origin },
            [r.w, r.h],
          );
          pass = beginPass("load");
        }
        const shadow = d.p.shadowEntry;
        if (
          shadow &&
          (!shadow.lensGroup ||
            shadow.snapshot !== this.texture ||
            shadow.stack !== this._stackTexture)
        ) {
          shadow.snapshot = this.texture;
          shadow.stack = this._stackTexture;
          shadow.lensGroup = device.createBindGroup({
            layout: this._lensTexLayout,
            entries: [
              { binding: 0, resource: this.texture.createView() },
              { binding: 1, resource: this._sampler },
              {
                binding: 2,
                resource: (this._stackTexture || this.texture).createView(),
              },
              { binding: 3, resource: shadow.textures[0].createView() },
            ],
          });
        }
        pass.setBindGroup(0, shadow ? shadow.lensGroup : this._lensBindGroup);
        pass.setViewport(d.x, d.y, d.w, d.h, 0, 1);
        pass.setBindGroup(1, this._uniformBindGroup, [i * 256]);
        pass.draw(6);
        if (this._compositeActive) {
          pass.end();
          const visiblePass = beginPass("load", visibleView);
          visiblePass.setBindGroup(
            0,
            shadow ? shadow.lensGroup : this._lensBindGroup,
          );
          visiblePass.setViewport(d.x, d.y, d.w, d.h, 0, 1);
          visiblePass.setBindGroup(1, this._uniformBindGroup, [i * 256]);
          visiblePass.draw(6);
          visiblePass.end();
          pass = beginPass("load");
        }
      }
      pass.end();

      this._enc.copyTextureToTexture(
        { texture: visibleTexture },
        { texture: presentationTexture },
        [this.canvas.width, this.canvas.height],
      );
      device.queue.submit([this._enc.finish()]);
      this._enc = null;
    }

    clearRegions(rects) {
      if (!rects.length) return;
      const device = this.device;
      const enc = device.createCommandEncoder();
      const pass = enc.beginRenderPass({
        colorAttachments: [
          {
            view: this.ctx.getCurrentTexture().createView(),
            loadOp: "load",
            storeOp: "store",
          },
        ],
      });
      pass.setPipeline(this._clearPipe);
      pass.setVertexBuffer(0, this._vb);
      rects.forEach(({ x, y, w, h }) => {
        const cx = Math.max(0, Math.min(this.canvas.width, x));
        const cy = Math.max(
          0,
          Math.min(this.canvas.height, this.canvas.height - y - h),
        );
        const cw = Math.max(0, Math.min(this.canvas.width - cx, w));
        const ch = Math.max(0, Math.min(this.canvas.height - cy, h));
        if (cw > 0 && ch > 0) {
          pass.setScissorRect(cx, cy, cw, ch);
          pass.draw(6);
        }
      });
      pass.end();
      device.queue.submit([enc.finish()]);
    }
  }

  /* --------------------------------------------------
   *  Shared renderer (one per page)
   * ------------------------------------------------*/
  class LiquidContentLayer {
    constructor(renderer, lens, el) {
      this.renderer = renderer;
      this.lens = lens;
      this.el = el;
      this.before = el !== lens.el;
      const sheet = renderer._dynamicStyleSheet;
      const index = sheet.insertRule(
        `[data-liquidgl-content="${lens._order}"] {}`,
        sheet.cssRules.length,
      );
      this.maskStyle = sheet.cssRules[index].style;
      this.canvas = document.createElement("canvas");
      this.version = 0;
      this.dirty = true;
      this.lastCapture = -Infinity;
      this.animations = 0;
      this.opacity = parseFloat(getComputedStyle(el).opacity) || 1;
      this.invalidate = () => {
        if (this._destroyed) return;
        this.dirty = true;
        this._forceCapture = true;
      };
      this.observer = new MutationObserver((records) => {
        if (
          records.some(
            (record) =>
              record.attributeName !== "data-liquidgl-content" &&
              record.attributeName !== "data-liquidgl-hide" &&
              !this.ignored(record.target),
          )
        )
          this.dirty = true;
      });
      this.observer.observe(el, {
        attributes: true,
        childList: true,
        characterData: true,
        subtree: true,
      });
      listen(this, el, "load", this.invalidate, true);
      if (document.fonts)
        listen(this, document.fonts, "loadingdone", this.invalidate);
      listen(this, el, "transitionrun", () => {
        this.animations++;
        this.invalidate();
      });
      listen(this, el, "animationstart", () => {
        this.animations++;
        this.invalidate();
      });
      const finish = () => {
        this.animations = Math.max(0, this.animations - 1);
        this.invalidate();
      };
      for (const event of [
        "transitionend",
        "transitioncancel",
        "animationend",
        "animationcancel",
      ]) {
        listen(this, el, event, finish);
      }
      this.resizeObserver = new ResizeObserver(this.invalidate);
      this.resizeObserver.observe(el);
    }

    destroy() {
      if (this._destroyed) return;
      this._destroyed = true;
      this.observer.disconnect();
      this.resizeObserver.disconnect();
      this._cleanups?.splice(0).forEach((cleanup) => cleanup());
      if (
        this.el.getAttribute("data-liquidgl-content") ===
        String(this.lens._order)
      )
        this.el.removeAttribute("data-liquidgl-content");
      this._mask?.parentElement.remove();
      removeRule(this.renderer._dynamicStyleSheet, this.maskStyle);
      for (const renderer of renderers) {
        if (renderer.backend) releaseCached(renderer.backend, this);
      }
      this.canvas.width = this.canvas.height = 0;
      this._paintState = null;
    }

    updateMask() {
      if (this._destroyed) return;
      const rect = this.el.getBoundingClientRect();
      const ordered =
        this.renderer._sceneLenses || this.renderer._orderedLenses;
      const start = ordered.indexOf(this.lens) + (this.before ? 0 : 1);
      const paths = [];
      for (let i = start; i < ordered.length; i++) {
        const lens = ordered[i];
        const metrics = lens.rectPx;
        if (!metrics || !lens.renderer.hasTexture) continue;
        const r = {
          ...metrics,
          right: metrics.left + metrics.width,
          bottom: metrics.top + metrics.height,
        };
        const bounds = lens._fluidDrawRect || r;
        if (
          !r ||
          !bounds ||
          bounds.left >= rect.right ||
          bounds.right <= rect.left ||
          bounds.top >= rect.bottom ||
          bounds.bottom <= rect.top
        )
          continue;
        const radius = Math.min(lens.radiusCss || 0, r.width / 2, r.height / 2);
        const fluid =
          lens.options.interaction === "fluid" && lens._fluid?.value;
        const reach = fluid
          ? unitOption(lens.options.interactionRadius, 0.35, 5) *
            Math.min(r.width, r.height)
          : 0;
        const points = [];
        const corners = [
          [r.left + radius, r.top + radius, Math.PI],
          [r.right - radius, r.top + radius, Math.PI * 1.5],
          [r.right - radius, r.bottom - radius, 0],
          [r.left + radius, r.bottom - radius, Math.PI * 0.5],
        ];
        for (const [cx, cy, angle] of corners) {
          for (let j = 0; j <= 12; j++) {
            const a = angle + (j / 12) * Math.PI * 0.5;
            const bx = cx + Math.cos(a) * radius,
              by = cy + Math.sin(a) * radius;
            let x = bx,
              y = by;
            if (reach > 0) {
              for (let k = 0; k < 6; k++) {
                const t = Math.min(
                  1,
                  Math.hypot(
                    x - r.left - fluid[0] * r.width,
                    y - r.top - fluid[1] * r.height,
                  ) / reach,
                );
                const influence = 1 - t * t * (3 - 2 * t);
                x = bx + fluid[2] * r.width * influence;
                y = by + fluid[3] * r.height * influence;
              }
            }
            points.push(
              `${(x - rect.left).toFixed(2)},${(y - rect.top).toFixed(2)}`,
            );
          }
        }
        paths.push(`M${points.join("L")}Z`);
      }
      const path = paths.length
        ? `M0,0H${rect.width}V${rect.height}H0Z${paths.join("")}`
        : "";
      if (path === this.maskPath) return;
      this.maskPath = path;
      if (!path) {
        this.maskStyle.maskImage = "";
        this.maskStyle.webkitMaskImage = "";
        return;
      }
      if (typeof document.getCSSCanvasContext === "function") {
        const name = `liquidgl-mask-${this.lens._order}`;
        const dpr = Math.min(2, window.devicePixelRatio || 1);
        const width = Math.ceil(rect.width * dpr);
        const height = Math.ceil(rect.height * dpr);
        const ctx = document.getCSSCanvasContext("2d", name, width, height);
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.clearRect(0, 0, width, height);
        ctx.setTransform(width / rect.width, 0, 0, height / rect.height, 0, 0);
        ctx.globalCompositeOperation = "source-over";
        ctx.fillStyle = "white";
        ctx.fillRect(0, 0, rect.width, rect.height);
        ctx.globalCompositeOperation = "destination-out";
        ctx.fill(new Path2D(paths.join("")));
        ctx.globalCompositeOperation = "source-over";
        this.maskStyle.webkitMaskImage = `-webkit-canvas(${name})`;
        this.maskStyle.webkitMaskSize = "100% 100%";
        this.maskStyle.webkitMaskRepeat = "no-repeat";
        return;
      }
      if (!this._mask) {
        const ns = "http://www.w3.org/2000/svg";
        const svg = document.createElementNS(ns, "svg");
        svg.setAttribute("width", "0");
        svg.setAttribute("height", "0");
        svg.setAttribute("data-liquid-ignore", "");
        svg.style.position = "absolute";
        this._mask = document.createElementNS(ns, "mask");
        this._mask.id = `liquidgl-mask-${this.lens._order}`;
        this._mask.setAttribute("maskUnits", "userSpaceOnUse");
        this._mask.setAttribute("x", "0");
        this._mask.setAttribute("y", "0");
        this._maskBackground = document.createElementNS(ns, "rect");
        this._maskBackground.setAttribute("fill", "white");
        this._maskShape = document.createElementNS(ns, "path");
        this._maskShape.setAttribute("fill", "black");
        this._mask.append(this._maskBackground, this._maskShape);
        svg.appendChild(this._mask);
        document.body.appendChild(svg);
      }
      this._mask.setAttribute("width", rect.width);
      this._mask.setAttribute("height", rect.height);
      this._maskBackground.setAttribute("width", rect.width);
      this._maskBackground.setAttribute("height", rect.height);
      this._maskShape.setAttribute("d", paths.join(""));
      this.maskStyle.maskImage = `url("#${this._mask.id}")`;
    }

    ignored(el) {
      return Array.from(renderers).some((renderer) =>
        renderer.lenses.some(
          (lens) =>
            lens.el !== this.el && (lens.el === el || lens.el.contains(el)),
        ),
      );
    }

    update(now) {
      if (this._updateFrame === renderFrame) return this._frameRect;
      this._updateFrame = renderFrame;
      this._frameRect = null;
      const rect = this.el.getBoundingClientRect();
      const style = window.getComputedStyle(this.el);
      this.backdrop = this.before ? backdropFilter(style) : null;
      this.opacity = Number(style.opacity);
      if (this.backdrop) {
        this.backdropRadii = [
          "borderTopLeftRadius",
          "borderTopRightRadius",
          "borderBottomRightRadius",
          "borderBottomLeftRadius",
        ].map((name) => {
          const value = style[name];
          const radius = parseFloat(value) || 0;
          return Math.min(
            rect.width / 2,
            rect.height / 2,
            value.includes("%")
              ? (radius * Math.min(rect.width, rect.height)) / 100
              : radius,
          );
        });
      }
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const scale = Math.min(
        dpr,
        this.renderer.backend.maxTextureSize /
          Math.max(1, rect.width, rect.height),
      );
      const resized =
        this.width !== rect.width ||
        this.height !== rect.height ||
        this.scale !== scale;
      if (rect.width <= 0 || rect.height <= 0) return null;
      if (resized || this.dirty || this.animations) {
        const plan = NaughtyDOM.measure(this.el, {
          scale,
          rootOpacity: this.before
            ? this.opacity
            : parseFloat(this.lens.originalOpacity || "1") *
              (this.lens._revealProgress ?? 1),
          ignoreElements: (el) =>
            el !== this.el &&
            (el.hasAttribute("data-liquid-ignore") || this.ignored(el)),
        });
        if (!plan) return null;
        this._paintState = NaughtyDOM.paintLayer(
          plan,
          this.canvas,
          this._paintState,
          resized || this._forceCapture,
        );
        if (plan.assets.length) Promise.all(plan.assets).then(this.invalidate);
        this.width = rect.width;
        this.height = rect.height;
        this.scale = scale;
        this.dirty = false;
        this._forceCapture = false;
        this.lastCapture = now;
        if (this._paintState.changed) this.version++;
      }
      if (!this.version) return null;
      return (this._frameRect = rect);
    }
  }

  function contentViewport(
    rect,
    canvas,
    origin = canvas.getBoundingClientRect(),
  ) {
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const left = (rect.left - origin.left) * dpr,
      top = (rect.top - origin.top) * dpr;
    const width = rect.width * dpr,
      height = rect.height * dpr;
    const x = Math.max(0, left),
      y = Math.max(0, top);
    const w = Math.min(canvas.width, left + width) - x;
    const h = Math.min(canvas.height, top + height) - y;
    if (w <= 0 || h <= 0) return null;
    return {
      x,
      y,
      w,
      h,
      uv: [(x - left) / width, (y - top) / height, w / width, h / height],
    };
  }

  class liquidGLRenderer {
    constructor(
      snapshotSelector,
      snapshotResolution = 1.0,
      engine = "auto",
      anchor = document.body,
    ) {
      this._anchor = anchor;
      this._engine = engine;
      this._destroyed = false;
      this._cleanups = [];
      this._naughtyQueued = false;
      this.canvas = document.createElement("canvas");
      this.canvas.style.cssText = `position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:0;`;
      this.canvas.setAttribute("data-liquid-ignore", "");
      this._anchor.appendChild(this.canvas);

      this.backend = null;
      this._backendFailed = false;
      this._pendingLensActivation = [];

      this.lenses = [];
      this._orderedLenses = [];
      this._contentLayers = [];
      this.hasTexture = false;
      this.textureWidth = 0;
      this.textureHeight = 0;
      this.scaleFactor = 1;
      this.startTime = Date.now();
      this._scrollUpdateCounter = 0;

      this._backendReady = this._selectBackend();

      this.snapshotTarget =
        document.querySelector(snapshotSelector) || document.body;
      if (!this.snapshotTarget) this.snapshotTarget = document.body;

      this._isScrolling = false;
      this._pendingViewportResize = false;
      let lastScrollY = window.scrollY;
      let scrollTimeout;
      const scrollCheck = () => {
        if (this._destroyed) return;
        if (window.scrollY !== lastScrollY) {
          this._isScrolling = true;
          lastScrollY = window.scrollY;
          clearTimeout(scrollTimeout);
          scrollTimeout = setTimeout(() => {
            this._isScrolling = false;
            if (this._pendingViewportResize) {
              this._pendingViewportResize = false;
              onResize();
            }
          }, 200);
        }
        this._scrollRaf = requestAnimationFrame(scrollCheck);
      };
      this._scrollRaf = requestAnimationFrame(scrollCheck);

      const onResize = debounce(() => {
        if (this._destroyed || this._capturing) return;
        if (this._isScrolling) {
          this._pendingViewportResize = true;
          return;
        }
        this._pendingViewportResize = false;

        if (window.visualViewport && window.visualViewport.scale !== 1) {
          return;
        }

        this._dynamicNodes.forEach((node) => {
          const meta = this._dynMeta.get(node.el);
          if (meta) {
            meta.needsRecapture = true;
            meta.prevDrawRect = null;
            meta.lastCapture = null;
          }
        });

        this._resizeCanvas();
        this.lenses.forEach((l) => l.updateMetrics());
        this.captureSnapshot();
      }, RECAPTURE_INTERVAL_MS);
      listen(
        this,
        window,
        "resize",
        () => {
          this._resizeCanvas();
          onResize();
        },
        { passive: true },
      );
      this._cleanups.push(() => {
        clearTimeout(scrollTimeout);
        onResize.cancel();
        cancelAnimationFrame(this._scrollRaf);
      });

      if ("ResizeObserver" in window) {
        const observer = new ResizeObserver(onResize);
        observer.observe(this.snapshotTarget);
        this._cleanups.push(() => observer.disconnect());
      }

      /* --------------------------------------------------
       *  Dynamic DOM elements (non-video, e.g. animating text)
       * ------------------------------------------------*/
      this._dynamicNodes = [];
      this._dynMeta = new Map();
      this._lastDynamicUpdate = 0;

      const styleEl = document.createElement("style");
      styleEl.id = "liquid-gl-dynamic-styles";
      document.head.appendChild(styleEl);
      this._styleEl = styleEl;
      this._dynamicStyleSheet = styleEl.sheet;

      this._snapshotResolution = Math.max(
        0.1,
        Math.min(3.0, snapshotResolution),
      );
      this._pendingReveal = [];

      this._resizeCanvas();
      this.captureSnapshot();

      /* --------------------------------------------------
       *  Dynamic media (video) support
       * ------------------------------------------------*/
      this._videoNodes = Array.from(
        this.snapshotTarget.querySelectorAll("video"),
      );
      this._videoNodes = this._videoNodes.filter((v) => !this._isIgnored(v));
      this._tmpCanvas = document.createElement("canvas");
      this._tmpCtx = this._tmpCanvas.getContext("2d");

      this._videoFrameState = new WeakMap();

      this._videoAlphaState = new WeakMap();

      this.canvas.style.opacity = "0";

      this.useExternalTicker = false;

      /* --------------------------------------------------
       *  Inline worker for heavy dynamic nodes
       * ------------------------------------------------*/
      this._workerEnabled =
        typeof OffscreenCanvas !== "undefined" &&
        typeof Worker !== "undefined" &&
        typeof ImageBitmap !== "undefined";

      if (this._workerEnabled) {
        const workerSrc = `
          /* dynamic-element worker (runs in its own thread) */
          self.onmessage = async (e) => {
            const { id, width, height, snap, dyn } = e.data;
            const off = new OffscreenCanvas(width, height);
            const ctx = off.getContext('2d');

            ctx.drawImage(snap, 0, 0, width, height);
            ctx.drawImage(dyn, 0, 0, width, height);

            const bmp = await off.transferToImageBitmap();
            self.postMessage({ id, bmp }, [bmp]);
          };
        `;
        const blob = new Blob([workerSrc], { type: "application/javascript" });
        this._workerUrl = URL.createObjectURL(blob);
        this._dynWorker = new Worker(this._workerUrl, {
          type: "module",
        });

        this._dynJobs = new Map();

        this._dynWorker.onmessage = (e) => {
          const { id, bmp } = e.data;
          const meta = this._dynJobs.get(id);
          if (meta) {
            this._dynJobs.delete(id);
            const { x, y } = meta;
            if (!this._destroyed && this.backend && this.hasTexture) {
              this.backend.uploadRegion(x, y, bmp);
              this._renderSignature = null;
            }
          }
          bmp.close();
        };
      }
    }

    /* ----------------------------- */
    async _selectBackend() {
      const chain = ENGINE_CHAINS[this._engine] || ENGINE_CHAINS.auto;
      let backend = null;

      if (chain[0] === "webgpu") {
        try {
          backend = await WebGPUBackend.create(this.canvas);
        } catch (e) {
          backend = null;
        }
      }

      if (this._destroyed) {
        destroyBackend(backend);
        return false;
      }
      if (!backend) {
        const glChain = chain.filter((c) => c !== "webgpu");
        if (glChain.length) {
          try {
            backend = new WebGLBackend(this.canvas, glChain);
          } catch (e) {
            backend = null;
          }
        }
      }

      this.backend = backend;

      if (!backend) {
        this._backendFailed = true;
        console.warn(
          "liquidGL: No GPU backend available – lenses will keep their original styles.",
        );
        return false;
      }

      backend.renderer = this;
      const pending = this._pendingLensActivation.splice(0);
      pending.forEach((ln) => ln._activate());
      return true;
    }

    /* ----------------------------- */
    _resizeCanvas() {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const viewportWidth = document.documentElement.clientWidth || innerWidth;
      const viewportHeight = Math.max(
        document.documentElement.clientHeight,
        innerHeight,
      );
      const width = Math.trunc(viewportWidth * dpr);
      const height = Math.trunc(viewportHeight * dpr);
      if (this.canvas.width !== width) this.canvas.width = width;
      if (this.canvas.height !== height) this.canvas.height = height;
      this.canvas.style.width = `${viewportWidth}px`;
      this.canvas.style.height = `${viewportHeight}px`;
      if (this.backend) this.backend.resize();
    }

    /* ----------------------------- */
    async captureSnapshot() {
      if (this._destroyed || this._capturing) return;
      this._capturing = true;

      const ready = await this._backendReady;
      if (!ready || this._destroyed) {
        this._capturing = false;
        return false;
      }

      const attemptCapture = async (
        attempt = 1,
        maxAttempts = 3,
        delayMs = 500,
      ) => {
        if (this._destroyed) return false;
        const generation = this._captureGeneration || 0;
        try {
          const fullW = this.snapshotTarget.scrollWidth;
          const fullH = this.snapshotTarget.scrollHeight;
          const maxTex = (this.backend && this.backend.maxTextureSize) || 8192;

          let scale = Math.min(
            this._snapshotResolution,
            maxTex / fullW,
            maxTex / fullH,
          );

          const maxArea = maxTex * maxTex;
          if (fullW * fullH * scale * scale > maxArea) {
            scale = Math.sqrt(maxArea / (fullW * fullH));
            console.warn(
              `liquidGL: snapshot area capped, resolution reduced to ${scale.toFixed(
                3,
              )} for a ${fullW}x${fullH} document.`,
            );
          }

          this.scaleFactor = Math.max(0.1, scale);

          const lensElements = new Set(
            Array.from(renderers)
              .flatMap((renderer) => renderer.lenses)
              .flatMap((lens) => [
                lens.el,
                lens._shadowEl,
                lens.options.content && lens._contentRoot,
              ])
              .filter(Boolean),
          );

          const ignoreElementsFunc = (element) => {
            if (!element || !element.hasAttribute) return false;
            if (element === this.canvas) {
              return true;
            }
            return (
              element.hasAttribute("data-liquid-ignore") ||
              element.closest("[data-liquid-ignore]")
            );
          };

          const naughtyIgnore = (el) =>
            ignoreElementsFunc(el) || lensElements.has(el);

          let backgroundColor = null;
          if (
            this.snapshotTarget === document.body ||
            this.snapshotTarget === document.documentElement
          ) {
            const rootStyle = window.getComputedStyle(document.documentElement);
            let color = parseTintColor(rootStyle.backgroundColor);
            if (!color?.[3] && rootStyle.backgroundImage === "none") {
              color = parseTintColor(
                window.getComputedStyle(document.body).backgroundColor,
              );
            }
            const [r, g, b, a] = color || [1, 1, 1, 1];
            backgroundColor = `rgb(${[r, g, b].map((channel) => (channel * a + 1 - a) * 255).join(",")})`;
          }
          const snapCanvas = await NaughtyDOM.rasteriseAsync(
            this.snapshotTarget,
            {
              width: fullW,
              height: fullH,
              scale: scale,
              backgroundColor,
              ignoreElements: naughtyIgnore,
            },
          );

          if (this._destroyed || generation !== (this._captureGeneration || 0))
            return false;
          if (!this._uploadTexture(snapCanvas, !!backgroundColor)) {
            throw new Error("liquidGL: snapshot could not be uploaded.");
          }
          return true;
        } catch (e) {
          if (this._destroyed) return false;
          console.error("liquidGL snapshot failed on attempt " + attempt, e);
          if (attempt < maxAttempts) {
            console.log(
              `Retrying snapshot capture (${attempt + 1}/${maxAttempts})...`,
            );
            await new Promise((resolve) => {
              this._retryResolve = resolve;
              this._retryTimeout = setTimeout(resolve, delayMs);
            });
            this._retryResolve = null;
            return await attemptCapture(attempt + 1, maxAttempts, delayMs);
          } else {
            console.error("liquidGL: All snapshot attempts failed.", e);
            return false;
          }
        } finally {
          this._capturing = false;
          if (!this._destroyed && generation !== (this._captureGeneration || 0))
            this.captureSnapshot();
        }
      };

      return await attemptCapture();
    }

    /* ----------------------------- */
    _uploadTexture(srcCanvas, opaque = false) {
      if (!srcCanvas) {
        console.error("liquidGL: snapshot produced no canvas.");
        return false;
      }

      if (!(srcCanvas instanceof HTMLCanvasElement)) {
        const tmp = document.createElement("canvas");
        tmp.width = srcCanvas.width || 0;
        tmp.height = srcCanvas.height || 0;
        if (tmp.width === 0 || tmp.height === 0) {
          console.error("liquidGL: snapshot canvas has zero dimensions.");
          return false;
        }
        try {
          const ctx = tmp.getContext("2d");
          ctx.drawImage(srcCanvas, 0, 0);
          srcCanvas = tmp;
        } catch (e) {
          console.error(
            "liquidGL: Unable to convert OffscreenCanvas for upload",
            e,
          );
          return false;
        }
      }

      if (srcCanvas.width === 0 || srcCanvas.height === 0) {
        console.error(
          "liquidGL: snapshot canvas has zero dimensions, requested area " +
            `${srcCanvas.width}x${srcCanvas.height} exceeds a browser limit.`,
        );
        return false;
      }
      this.staticSnapshotCanvas = srcCanvas;
      if (!this.backend || !this.backend.uploadSnapshot(srcCanvas)) {
        return false;
      }

      this.hasTexture = true;
      this._snapshotOpaque = opaque;
      this._renderSignature = null;
      this.textureWidth = srcCanvas.width;
      this.textureHeight = srcCanvas.height;
      this._invalidateDynamicRegion(
        0,
        0,
        this.textureWidth,
        this.textureHeight,
      );

      if (this._videoFrameState) this._videoFrameState = new WeakMap();

      this.render();

      if (this._pendingReveal.length) {
        this._pendingReveal.forEach((ln) => ln._reveal());
        this._pendingReveal.length = 0;
      }

      return true;
    }

    /* ----------------------------- */
    removeLens(lens) {
      this.lenses = this.lenses.filter((item) => item !== lens);
      this._orderedLenses = this._orderedLenses.filter((item) => item !== lens);
      this._pendingLensActivation = this._pendingLensActivation.filter(
        (item) => item !== lens,
      );
      this._pendingReveal = this._pendingReveal.filter((item) => item !== lens);
      lens._contentLayer?.destroy();
      this._contentLayers = this._contentLayers.filter(
        (layer) => layer.lens !== lens,
      );
      lens._contentLayer = null;
      for (const renderer of renderers) {
        if (renderer.backend) releaseCached(renderer.backend, lens);
        renderer._sceneLenses = null;
        renderer._renderSignature = null;
        renderer._captureGeneration = (renderer._captureGeneration || 0) + 1;
        for (const layer of renderer._contentLayers) layer.invalidate();
      }
      if (!this.lenses.length) this.destroy();
      else this._updateZIndex();
      renderAll();
      for (const renderer of renderers) renderer.captureSnapshot();
    }

    destroy() {
      if (this._destroyed) return;
      this._destroyed = true;
      renderers.delete(this);
      this._cleanups.splice(0).forEach((cleanup) => cleanup());
      cancelAnimationFrame(this._revealRaf);
      cancelAnimationFrame(this._dirtyRaf);
      if (this._captureIdle != null) cancelIdleCallback(this._captureIdle);
      clearTimeout(this._captureTimeout);
      clearTimeout(this._retryTimeout);
      this._retryResolve?.();
      for (const [el, meta] of this._dynMeta) {
        cancelAnimationFrame(meta._rafId);
        if (meta.hoverClassName) el.classList.remove(meta.hoverClassName);
      }
      this._dynMeta.clear();
      this._dynamicNodes.length = 0;
      this._dynWorker?.terminate();
      if (this._workerUrl) URL.revokeObjectURL(this._workerUrl);
      this._dynJobs?.clear();
      for (const renderer of renderers) {
        if (renderer.backend && this._output)
          releaseCached(renderer.backend, this._output);
      }
      destroyBackend(this.backend);
      this.backend = null;
      this._styleEl.remove();
      this.canvas.remove();
      this.canvas.width = this.canvas.height = 0;
      this._output = this._outputCanvas = this.staticSnapshotCanvas = null;
      this._videoNodes.length = 0;
      this._videoComposites = null;
      if (window.__liquidGLRenderer__ === this)
        window.__liquidGLRenderer__ = renderers.values().next().value;
      if (!renderers.size) {
        cancelAnimationFrame(renderRaf);
        renderRaf = null;
        stopSync?.();
        stopSync = null;
        for (const [video, state] of videoFrames) {
          state.stopped = true;
          video.cancelVideoFrameCallback?.(state.callbackId);
        }
        videoFrames.clear();
        for (const image of videoImages.values()) image.heldSource?.close();
        videoImages.clear();
      } else startRendering();
    }

    _updateZIndex() {
      const maxZ = Math.max(...this.lenses.map((ln) => this._localLensZ(ln)));
      this.canvas.style.zIndex =
        this._anchor !== document.body ||
        this.lenses.some((ln) => ln.options.zIndex != null)
          ? maxZ - 1
          : Math.max(0, maxZ - 1);
    }

    addLens(element, options) {
      const lens = new liquidGLLens(this, element, options);
      lens._contentRoot =
        typeof options.content === "string"
          ? document.querySelector(options.content)
          : element;
      for (const layer of this._contentLayers) layer.invalidate();
      lens._order = lensOrder++;
      this.lenses.push(lens);
      this._orderedLenses = this.lenses
        .slice()
        .sort(
          (a, b) =>
            (a.options.zIndex ?? 0) - (b.options.zIndex ?? 0) ||
            a._order - b._order,
        );

      this._updateZIndex();
      if (element !== this._anchor) {
        let branch = element;
        while (branch.parentElement && branch.parentElement !== this._anchor) {
          branch = branch.parentElement;
        }
        if (
          branch.parentElement === this._anchor &&
          branch.compareDocumentPosition(this.canvas) &
            Node.DOCUMENT_POSITION_FOLLOWING
        ) {
          this._anchor.insertBefore(this.canvas, branch);
        }
      }

      if (this.backend) {
        lens._activate();
      } else {
        this._pendingLensActivation.push(lens);
      }

      if (!this.hasTexture) {
        this._pendingReveal.push(lens);
      } else {
        lens._reveal();
      }
      return lens;
    }

    /* ----------------------------- */
    _prepareContentLayers() {
      for (const lens of this.lenses) {
        if (
          !lens._activated ||
          lens.options.content === false ||
          lens._contentLayer
        )
          continue;
        const root = lens._contentRoot;
        if (
          !root ||
          !root.childElementCount ||
          ((this._sceneLenses || this.lenses).length === 1 &&
            !lens.options.content)
        )
          continue;
        const layer = new LiquidContentLayer(this, lens, root);
        lens._contentLayer = layer;
        this._contentLayers.push(layer);
      }
    }

    _renderContent(lens, before) {
      const layer = lens._contentLayer;
      if (!layer || layer.before !== before) return;
      this._drawContentLayer(layer);
    }

    _drawContentLayer(layer) {
      const lens = layer.lens;
      if (!layer.el.isConnected) {
        layer.el.removeAttribute("data-liquidgl-content");
        return;
      }
      const rect = layer.update(performance.now());
      if (!rect) return;
      if (this.backend.drawContent(layer, rect)) {
        if (!layer.el.hasAttribute("data-liquidgl-content")) {
          layer.el.setAttribute("data-liquidgl-content", String(lens._order));
        }
        const dpr = Math.min(2, window.devicePixelRatio || 1);
        this._frameLensRects.push({
          x: Math.floor((rect.left - this._frameCanvasRect.left) * dpr),
          y: Math.floor(
            this.canvas.height -
              (rect.bottom - this._frameCanvasRect.top) * dpr,
          ),
          w: Math.ceil(rect.width * dpr),
          h: Math.ceil(rect.height * dpr),
        });
      }
    }

    render() {
      if (this._destroyed || this._tearingDown || rendering || document.hidden)
        return;
      rendering = true;
      renderFrame++;
      const tiltMeasurements = [];
      try {
        const ordered = orderedRenderers();
        const lenses = ordered.flatMap((renderer) => renderer._orderedLenses);
        for (const lens of lenses) {
          if (
            lens._mirrorActive &&
            lens._tiltMeasureStyle &&
            lens.renderer.lenses.some(
              (child) => child !== lens && lens.el.contains(child.el),
            )
          ) {
            tiltMeasurements.push(lens);
            lens._tiltMeasureStyle.setProperty(
              "transform",
              lens._tiltBaseTransform,
              "important",
            );
          }
        }
        for (const lens of lenses) lens.updateMetrics();
        for (const renderer of ordered) {
          renderer._sceneLenses = lenses;
          renderer._prepareContentLayers();
        }
        const lower = [];
        const visible = (rect, margin = 0) =>
          rect &&
          rect.width > 0 &&
          rect.height > 0 &&
          rect.left < innerWidth + margin &&
          rect.top < innerHeight + margin &&
          rect.left + rect.width > -margin &&
          rect.top + rect.height > -margin;
        for (const renderer of ordered) {
          if (!renderer.backend || !renderer.hasTexture) continue;
          const active =
            renderer.lenses.some((lens) =>
              visible(
                lens.rectPx,
                lens.options.interaction === "fluid"
                  ? (lens.options.shadow ? 70 : 0) +
                      Math.min(
                        lens.rectPx?.width || 0,
                        lens.rectPx?.height || 0,
                      ) *
                        unitOption(lens.options.interactionStrength, 0.5, 5) *
                        0.12
                  : 0,
              ),
            ) ||
            renderer._contentLayers.some((layer) =>
              visible(layer.el.getBoundingClientRect()),
            );
          if (renderer._visible !== active) {
            renderer._visible = active;
            renderer.canvas.style.visibility = active ? "visible" : "hidden";
          }
          if (!active) continue;
          if (renderer._needsFrame(lower)) renderer._renderFrame(lower);
          lower.push(renderer);
        }
      } finally {
        for (const lens of tiltMeasurements) {
          lens._tiltMeasureStyle.removeProperty("transform");
        }
        rendering = false;
      }
    }

    _needsFrame(lower) {
      const state = [
        innerWidth,
        innerHeight,
        window.devicePixelRatio,
        window.scrollX,
        window.scrollY,
      ];
      const rectState = (rect) =>
        state.push(rect?.left, rect?.top, rect?.width, rect?.height);
      rectState(this.canvas.getBoundingClientRect());
      rectState(this.snapshotTarget.getBoundingClientRect());
      let animated = false;
      this._videoSampleAreas = [];
      for (const lens of this._sceneLenses) {
        rectState(lens.rectPx);
        state.push(
          lens.radiusCss,
          lens.tiltX,
          lens.tiltY,
          lens._revealProgress,
          lens._mirrorActive,
          lens._fluid?.value,
        );
        const options = lens.options;
        if (lens.renderer === this)
          state.push(
            options.refraction,
            options.aberration,
            options.bevelDepth,
            options.bevelWidth,
            options.frost,
            options.specular,
            options.magnify,
            options.shadow,
            options.tint,
            options.interaction,
            options.interactionStrength,
            options.interactionRadius,
            options.interactionViscosity,
          );
        if (lens.renderer === this) {
          const rect = lens.rectPx;
          if (rect) {
            const mag = Math.max(0.001, Math.min(3, options.magnify ?? 1));
            const displacement =
              (Math.abs(options.refraction) + Math.abs(options.bevelDepth)) *
              (1 + Math.abs(options.aberration || 0));
            const frost =
              Math.max(1, (options.frost || 0) * 4) /
              Math.min(
                this.scaleFactor,
                Math.min(2, window.devicePixelRatio || 1),
              );
            const fluidReach =
              options.interaction === "fluid"
                ? Math.min(rect.width, rect.height) *
                  0.12 *
                  unitOption(options.interactionStrength, 0.5, 5)
                : 0;
            const dx =
              rect.width / mag / 2 +
              frost +
              ((displacement +
                Math.abs(Math.tan(((lens.tiltY || 0) * Math.PI) / 180)) *
                  0.05) *
                this.textureWidth) /
                this.scaleFactor +
              Math.max(
                fluidReach,
                Math.abs(lens._fluid?.value[2] || 0) * rect.width,
              ) /
                mag;
            const dy =
              rect.height / mag / 2 +
              frost +
              ((displacement +
                Math.abs(Math.tan(((lens.tiltX || 0) * Math.PI) / 180)) *
                  0.05) *
                this.textureHeight) /
                this.scaleFactor +
              Math.max(
                fluidReach,
                Math.abs(lens._fluid?.value[3] || 0) * rect.height,
              ) /
                mag;
            this._videoSampleAreas.push({
              left: rect.left + rect.width / 2 - dx,
              top: rect.top + rect.height / 2 - dy,
              width: dx * 2,
              height: dy * 2,
            });
          }
          animated ||= !!(
            options.specular &&
            rect &&
            rect.left < innerWidth &&
            rect.top < innerHeight &&
            rect.left + rect.width > 0 &&
            rect.top + rect.height > 0
          );
          animated ||= !!lens._fluid?.value?.some(
            (value, i) => i > 1 && Math.abs(value) > 0.00001,
          );
        }
      }
      const pointer = this._fluidPointer;
      state.push(pointer?.active, pointer?.x, pointer?.y, pointer?.time);
      animated ||= !!(
        pointer?.active &&
        (pointer.vx || pointer.vy) &&
        performance.now() - pointer.time < 250
      );
      const now = performance.now();
      for (const renderer of [...lower, this]) {
        if (renderer !== this) state.push(renderer._output?.version);
        for (const layer of renderer._contentLayers) {
          const rect = layer.update(now);
          rectState(rect);
          if (rect && layer.backdrop) {
            const margin = layer.backdrop.blur * 3;
            this._videoSampleAreas.push({
              left: rect.left - margin,
              top: rect.top - margin,
              width: rect.width + margin * 2,
              height: rect.height + margin * 2,
            });
          }
          state.push(layer.version, layer.opacity, layer.backdrop);
          animated ||= !!layer.animations;
        }
      }
      for (const video of this._videoNodes) {
        if (this._isIgnored(video)) continue;
        const rect = video.getBoundingClientRect();
        if (!this._videoRelevant(rect)) continue;
        rectState(rect);
        state.push(
          videoFrame(video),
          video.readyState,
          video.seeking,
          getComputedStyle(video).opacity,
        );
      }
      animated ||= this._dynamicNodes.some(({ el }) => {
        if (
          this._contentLayers.some((layer) => layer.el.contains(el)) ||
          this._isIgnored(el) ||
          effectiveZ(el) >= this._getMaxLensZ() ||
          !this._videoRelevant(el.getBoundingClientRect())
        )
          return false;
        const meta = this._dynMeta.get(el);
        return (
          meta && (meta._animating || meta.needsRecapture || meta._capturing)
        );
      });
      const signature = JSON.stringify(state);
      const changed = signature !== this._renderSignature;
      this._renderSignature = signature;
      return animated || changed;
    }

    _videoRelevant(rect) {
      return (
        !this._videoSampleAreas ||
        this._videoSampleAreas.some(
          (area) =>
            rect.left < area.left + area.width &&
            rect.left + rect.width > area.left &&
            rect.top < area.top + area.height &&
            rect.top + rect.height > area.top,
        )
      );
    }

    _renderFrame(lower) {
      const backend = this.backend;

      const dprNow = Math.min(2, window.devicePixelRatio || 1);
      const bufW = Math.trunc(
        (document.documentElement.clientWidth || innerWidth) * dprNow,
      );
      const bufH = Math.trunc(
        Math.max(document.documentElement.clientHeight, innerHeight) * dprNow,
      );
      if (this.canvas.width !== bufW || this.canvas.height !== bufH) {
        this._resizeCanvas();
      }

      const canvasRect = this.canvas.getBoundingClientRect();
      if (canvasRect.left || canvasRect.top) {
        this.canvas.style.left = `${parseFloat(this.canvas.style.left) - canvasRect.left}px`;
        this.canvas.style.top = `${parseFloat(this.canvas.style.top) - canvasRect.top}px`;
      }
      this._frameCanvasRect = this.canvas.getBoundingClientRect();

      if (this._isScrolling) {
        this._scrollUpdateCounter++;
      }

      const time = (Date.now() - this.startTime) / 1000;

      this._prepareContentLayers();
      backend.beginFrame(
        this.canvas.width,
        this.canvas.height,
        time,
        this._contentLayers.length > 0 || lower.length > 0,
      );

      if (this._updateDynamicVideos() !== false) this._updateDynamicNodes();

      this._frameSnapRect = this.snapshotTarget.getBoundingClientRect();
      this._frameLensRects = [];

      for (const renderer of lower) {
        for (const layer of renderer._contentLayers) {
          if (layer.before) this._drawContentLayer(layer);
        }
        if (backend.drawContent(renderer._output, renderer._frameCanvasRect)) {
          const dx =
            (renderer._frameCanvasRect.left - this._frameCanvasRect.left) *
            dprNow;
          const dy =
            this.canvas.height -
            renderer.canvas.height +
            (this._frameCanvasRect.top - renderer._frameCanvasRect.top) *
              dprNow;
          this._frameLensRects.push(
            ...renderer._ownFrameLensRects.map((rect) => ({
              ...rect,
              x: rect.x + dx,
              y: rect.y + dy,
            })),
          );
        }
        for (const layer of renderer._contentLayers) {
          if (!layer.before) this._drawContentLayer(layer);
        }
      }

      const ownStart = this._frameLensRects.length;
      this._orderedLenses.forEach((lens) => {
        this._renderContent(lens, true);
        this._renderLens(lens);
        this._renderContent(lens, false);
        if (lens._mirrorActive && lens._mirrorClipUpdater) {
          lens._mirrorClipUpdater();
        }
      });

      backend.endFrame();
      this._ownFrameLensRects = this._frameLensRects.slice(ownStart);
      this._output ||= { canvas: this.canvas, version: 0 };
      this._output.device = backend.device;
      this._output.texture = backend._outputTexture;
      this._output.version++;
      if (this.lenses.some((lens) => lens._mirrorActive)) {
        this._outputCanvas ||= document.createElement("canvas");
        this._outputCanvas.width = this.canvas.width;
        this._outputCanvas.height = this.canvas.height;
        this._outputCanvas.getContext("2d").drawImage(this.canvas, 0, 0);
        this._output.canvas = this._outputCanvas;
      } else {
        this._output.canvas = this.canvas;
      }
      this._contentLayers.forEach((layer) => layer.updateMask());

      this.lenses.forEach((ln) => {
        if (ln._mirrorActive && ln._mirrorCtx) {
          const mirror = ln._mirror;
          if (
            mirror.width !== this.canvas.width ||
            mirror.height !== this.canvas.height
          ) {
            mirror.width = this.canvas.width;
            mirror.height = this.canvas.height;
          }
          ln._mirrorCtx.clearRect(0, 0, mirror.width, mirror.height);
          ln._mirrorCtx.drawImage(this.canvas, 0, 0);
        }
      });

      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const clearRects = [];
      this.lenses.forEach((ln) => {
        if (ln._mirrorActive && ln.rectPx) {
          const { left, top, width, height } = ln._fluidDrawRect || ln.rectPx;
          const expand = 2;
          const x = Math.max(
            0,
            Math.round((left - this._frameCanvasRect.left) * dpr) - expand,
          );
          const y = Math.max(
            0,
            Math.round(
              this.canvas.height -
                (top + height - this._frameCanvasRect.top) * dpr,
            ) - expand,
          );
          const w = Math.min(
            this.canvas.width - x,
            Math.round(width * dpr) + expand * 2,
          );
          const h = Math.min(
            this.canvas.height - y,
            Math.round(height * dpr) + expand * 2,
          );
          if (w > 0 && h > 0) {
            clearRects.push({ x, y, w, h });
          }
        }
      });
      backend.clearRegions(clearRects);
    }

    /* ----------------------------- */
    _updateInteraction(lens, now) {
      const options = lens.options;
      if (options.interaction !== "fluid") {
        lens._fluid = null;
        return INTERACTION_OFF;
      }
      if (!this._fluidPointer) {
        const pointer = (this._fluidPointer = {
          active: false,
          x: 0,
          y: 0,
          vx: 0,
          vy: 0,
          time: 0,
          id: null,
        });
        const stop = () => {
          pointer.active = false;
          pointer.vx = pointer.vy = 0;
        };
        const move = (event) => {
          if (!event.isPrimary) return;
          const time = performance.now();
          const dt = time - pointer.time;
          const continuous =
            pointer.active &&
            pointer.id === event.pointerId &&
            dt > 0 &&
            dt < 100;
          pointer.vx = continuous ? (event.clientX - pointer.x) / dt : 0;
          pointer.vy = continuous ? (event.clientY - pointer.y) / dt : 0;
          pointer.x = event.clientX;
          pointer.y = event.clientY;
          pointer.time = time;
          pointer.id = event.pointerId;
          pointer.active =
            event.pointerType === "mouse" ||
            event.buttons > 0 ||
            event.type === "pointerdown";
        };
        const listenerOptions = { passive: true, capture: true };
        listen(this, window, "pointermove", move, listenerOptions);
        listen(this, window, "pointerdown", move, listenerOptions);
        listen(
          this,
          window,
          "pointerup",
          (event) => {
            if (event.isPrimary && event.pointerType !== "mouse") stop();
          },
          listenerOptions,
        );
        listen(
          this,
          window,
          "pointercancel",
          (event) => {
            if (event.isPrimary) stop();
          },
          listenerOptions,
        );
        listen(
          this,
          window,
          "pointerout",
          (event) => {
            if (!event.relatedTarget && event.isPrimary) stop();
          },
          listenerOptions,
        );
        listen(this, window, "blur", stop);
        listen(this, document, "visibilitychange", () => {
          if (document.hidden) stop();
        });
      }
      const rect = lens.rectPx;
      const pointer = this._fluidPointer;
      const size = Math.min(rect.width, rect.height);
      const x = (pointer.x - rect.left) / rect.width;
      const y = (pointer.y - rect.top) / rect.height;
      const radius = lens.radiusCss || 0;
      const qx = Math.max(
        Math.abs(pointer.x - rect.left - rect.width / 2) -
          rect.width / 2 +
          radius,
        0,
      );
      const qy = Math.max(
        Math.abs(pointer.y - rect.top - rect.height / 2) -
          rect.height / 2 +
          radius,
        0,
      );
      const inside =
        pointer.active &&
        x >= 0 &&
        x <= 1 &&
        y >= 0 &&
        y <= 1 &&
        Math.hypot(qx, qy) <= radius;
      const state =
        lens._fluid ||
        (lens._fluid = { time: now, inside: false, value: [x, y, 0, 0] });
      const value = state.value;
      const dt = Math.max(0, now - state.time);
      state.time = now;
      const viscosity = unitOption(options.interactionViscosity, 0.65);
      const follow = 1 - Math.exp(-dt / (25 + viscosity * 180));
      const settle = 1 - Math.exp(-dt / (80 + viscosity * 620));
      let dx = 0,
        dy = 0;
      if (inside) {
        if (!state.inside && Math.hypot(value[2], value[3]) < 0.0001) {
          value[0] = x;
          value[1] = y;
        }
        value[0] += (x - value[0]) * follow;
        value[1] += (y - value[1]) * follow;
        const speed = Math.hypot(pointer.vx, pointer.vy);
        const amount =
          Math.min(size * 0.12, speed * 45) *
          unitOption(options.interactionStrength, 0.5, 5) *
          Math.exp(-Math.max(0, now - pointer.time) / 70);
        if (speed > 0) {
          dx = ((pointer.vx / speed) * amount) / rect.width;
          dy = ((pointer.vy / speed) * amount) / rect.height;
        }
      }
      const easing =
        Math.hypot(dx, dy) > Math.hypot(value[2], value[3]) ? follow : settle;
      value[2] += (dx - value[2]) * easing;
      value[3] += (dy - value[3]) * easing;
      if (
        unitOption(options.interactionStrength, 0.5, 5) === 0 ||
        Math.hypot(value[2], value[3]) < 0.00001
      )
        value[2] = value[3] = 0;
      state.inside = inside;
      return value;
    }

    _renderLens(lens) {
      const rect = lens.rectPx;
      if (!rect) return;

      const dpr = Math.min(2, window.devicePixelRatio || 1);

      const origin =
        this._frameCanvasRect || this.canvas.getBoundingClientRect();
      const overscrollX = -origin.left;
      const overscrollY = -origin.top;

      if (rect.width <= 0 || rect.height <= 0) return;
      const interaction = this._updateInteraction(lens, performance.now());
      const interactionRadius =
        interaction[2] || interaction[3]
          ? unitOption(lens.options.interactionRadius, 0.35, 5)
          : 0;
      if (lens._syncShadowMode) lens._syncShadowMode();
      const shadow =
        lens.options.interaction === "fluid" && lens.options.shadow;
      const expandX =
        Math.ceil(shadow ? 60 * dpr : 0) +
        (interactionRadius > 0
          ? Math.ceil(Math.abs(interaction[2]) * rect.width * dpr) + 1
          : 0);
      const expandY =
        Math.ceil(shadow ? 70 * dpr : 0) +
        (interactionRadius > 0
          ? Math.ceil(Math.abs(interaction[3]) * rect.height * dpr) + 1
          : 0);
      const leftPx = (rect.left + overscrollX) * dpr;
      const topPx = (rect.top + overscrollY) * dpr;
      const x = Math.round(leftPx) - expandX;
      const yTop = Math.round(topPx) - expandY;
      const w = Math.round(leftPx + rect.width * dpr) + expandX - x;
      const h = Math.round(topPx + rect.height * dpr) + expandY - yTop;
      const y = this.canvas.height - (yTop + h);
      lens._fluidDrawRect =
        expandX || expandY
          ? {
              left: x / dpr + origin.left,
              top: yTop / dpr + origin.top,
              width: w / dpr,
              height: h / dpr,
              right: (x + w) / dpr + origin.left,
              bottom: (yTop + h) / dpr + origin.top,
            }
          : null;

      const snapRect =
        this._frameSnapRect || this.snapshotTarget.getBoundingClientRect();
      const docX = rect.left - snapRect.left;
      const docY = rect.top - snapRect.top;
      const leftUV = (docX * this.scaleFactor) / this.textureWidth;
      const topUV = (docY * this.scaleFactor) / this.textureHeight;
      const wUV = (rect.width * this.scaleFactor) / this.textureWidth;
      const hUV = (rect.height * this.scaleFactor) / this.textureHeight;

      const mag = Math.max(
        0.001,
        Math.min(
          3.0,
          lens.options.magnify !== undefined ? lens.options.magnify : 1.0,
        ),
      );

      if (
        w <= 0 ||
        h <= 0 ||
        x >= this.canvas.width ||
        y >= this.canvas.height ||
        x + w <= 0 ||
        y + h <= 0
      )
        return;
      const stackMapping = [
        ((snapRect.left + overscrollX) * dpr) / this.canvas.width,
        ((snapRect.top + overscrollY) * dpr) / this.canvas.height,
        ((this.textureWidth / this.scaleFactor) * dpr) / this.canvas.width,
        ((this.textureHeight / this.scaleFactor) * dpr) / this.canvas.height,
      ];
      const prior = this._frameLensRects;
      let stackRegion = null;
      if (prior.length) {
        let minX = Infinity,
          minY = Infinity,
          maxX = -Infinity,
          maxY = -Infinity;
        for (const r of prior) {
          minX = Math.min(minX, r.x);
          minY = Math.min(minY, r.y);
          maxX = Math.max(maxX, r.x + r.w);
          maxY = Math.max(maxY, r.y + r.h);
        }
        const displacement =
          (Math.abs(lens.options.refraction) +
            Math.abs(lens.options.bevelDepth)) *
          (1 + Math.abs(lens.options.aberration || 0));
        const frost =
          (Math.max(1, (lens.options.frost || 0) * 4) * dpr) / this.scaleFactor;
        const padX =
          (((displacement +
            Math.abs(Math.tan(((lens.tiltY || 0) * Math.PI) / 180)) * 0.05) *
            this.textureWidth) /
            this.scaleFactor) *
            dpr +
          frost +
          Math.abs(interaction[2]) * rect.width * dpr +
          1;
        const padY =
          (((displacement +
            Math.abs(Math.tan(((lens.tiltX || 0) * Math.PI) / 180)) * 0.05) *
            this.textureHeight) /
            this.scaleFactor) *
            dpr +
          frost +
          Math.abs(interaction[3]) * rect.height * dpr +
          1;
        const sx = Math.max(
          0,
          Math.floor(x + w * 0.5 - (w / mag) * 0.5 - padX),
          minX,
        );
        const sy = Math.max(
          0,
          Math.floor(y + h * 0.5 - (h / mag) * 0.5 - padY),
          minY,
        );
        const ex = Math.min(
          this.canvas.width,
          Math.ceil(x + w * 0.5 + (w / mag) * 0.5 + padX),
          maxX,
        );
        const ey = Math.min(
          this.canvas.height,
          Math.ceil(y + h * 0.5 + (h / mag) * 0.5 + padY),
          maxY,
        );
        if (ex > sx && ey > sy)
          stackRegion = { x: sx, y: sy, w: ex - sx, h: ey - sy };
      }
      prior.push({ x, y, w, h });
      this.backend.drawLens(lens, {
        shadow,
        dpr,
        interaction,
        interactionRadius,
        stackMapping,
        stackRegion,
        x,
        y,
        w,
        h,
        subX: leftPx - x,
        subY: this.canvas.height - topPx - rect.height * dpr - y,
        boxW: rect.width * dpr,
        boxH: rect.height * dpr,
        bounds: [leftUV, topUV, wUV, hUV],
        texW:
          this.textureWidth *
          (lens._contentLayer?.before ? dpr / this.scaleFactor : 1),
        texH:
          this.textureHeight *
          (lens._contentLayer?.before ? dpr / this.scaleFactor : 1),
        refraction: lens.options.refraction,
        aberration: lens.options.aberration || 0,
        bevelDepth: lens.options.bevelDepth,
        bevelWidth: lens.options.bevelWidth,
        frost: lens.options.frost,
        radius: lens.radiusGl,
        specular: lens.options.specular ? 1 : 0,
        revealProgress: lens._revealProgress || 1.0,
        revealType: lens.revealTypeIndex || 0,
        magnify: mag,
        tiltX: lens.tiltX || 0,
        tiltY: lens.tiltY || 0,
        tint: lens.options.tint || TINT_OFF,
      });
    }

    /* ----------------------------- */
    _createRoundedRectPath(ctx, w, h, radii) {
      ctx.beginPath();
      ctx.moveTo(radii.tl, 0);
      ctx.lineTo(w - radii.tr, 0);
      ctx.arcTo(w, 0, w, radii.tr, radii.tr);
      ctx.lineTo(w, h - radii.br);
      ctx.arcTo(w, h, w - radii.br, h, radii.br);
      ctx.lineTo(radii.bl, h);
      ctx.arcTo(0, h, 0, h - radii.bl, radii.bl);
      ctx.lineTo(0, radii.tl);
      ctx.arcTo(0, 0, radii.tl, 0, radii.tl);
      ctx.closePath();
    }

    _videoIsOpaque(vid) {
      if (!vid.videoWidth || !vid.videoHeight) return false;

      const key = vid.videoWidth + "x" + vid.videoHeight;
      const cached = this._videoAlphaState.get(vid);
      if (cached && cached.key === key) return cached.opaque;

      let opaque = false;
      try {
        const probe =
          this._alphaProbeCanvas ||
          (this._alphaProbeCanvas = document.createElement("canvas"));
        const size = 32;
        probe.width = size;
        probe.height = size;
        const ctx = probe.getContext("2d", { willReadFrequently: true });
        ctx.clearRect(0, 0, size, size);
        ctx.drawImage(vid, 0, 0, size, size);
        const data = ctx.getImageData(0, 0, size, size).data;
        opaque = true;
        for (let i = 3; i < data.length; i += 4) {
          if (data[i] !== 255) {
            opaque = false;
            break;
          }
        }
      } catch (e) {
        opaque = false;
      }

      this._videoAlphaState.set(vid, { key, opaque });
      return opaque;
    }

    /* ----------------------------- */
    _compositeVideoFrame(vid, vidIndex, image, opacity, rect, region) {
      if (
        this._videoFadeFailed ||
        this.backend._videoFadeFailed ||
        (!this.backend._videoResolvePipe &&
          (!this._snapshotOpaque ||
            region.texX < 0 ||
            region.texY < 0 ||
            region.texX + region.texW > this.textureWidth ||
            region.texY + region.texH > this.textureHeight))
      )
        return false;
      this._videoComposites ||= new WeakMap();
      let entry = this._videoComposites.get(vid);
      if (!entry) {
        entry = {
          background: { canvas: document.createElement("canvas"), version: 0 },
          sources: new WeakMap(),
        };
        this._videoComposites.set(vid, entry);
      }
      const {
        texX,
        texY,
        texW,
        texH,
        drawW,
        drawH,
        srcX,
        srcY,
        dstX,
        dstY,
        updW,
        updH,
      } = region;
      const prepare = (content, source, key, draw) => {
        if (content.source === source && content.key === key) return;
        const canvas = content.canvas;
        if (canvas.width !== updW) canvas.width = updW;
        if (canvas.height !== updH) canvas.height = updH;
        const ctx = canvas.getContext("2d");
        ctx.clearRect(0, 0, updW, updH);
        draw(ctx);
        content.source = source;
        content.key = key;
        content.version++;
      };
      try {
        const key = [
          texX,
          texY,
          texW,
          texH,
          drawW,
          drawH,
          srcX,
          srcY,
          updW,
          updH,
        ].join(",");
        prepare(entry.background, this.staticSnapshotCanvas, key, (ctx) => {
          ctx.drawImage(
            this.staticSnapshotCanvas,
            texX,
            texY,
            texW,
            texH,
            -srcX,
            -srcY,
            drawW,
            drawH,
          );
        });
        const layers = [{ content: entry.background, opacity: 1 }];
        const add = (video, frame, alpha, x, y, w, h) => {
          if (!frame || alpha === 0) return;
          let content = entry.sources.get(video);
          if (!content) {
            content = { canvas: document.createElement("canvas"), version: 0 };
            entry.sources.set(video, content);
          }
          content.external =
            w > 0 && h > 0 && this._videoIsOpaque(video)
              ? this.backend.getExternalVideo?.(frame.source)
              : null;
          if (content.external) {
            content.uv = [(srcX - x) / w, (srcY - y) / h, updW / w, updH / h];
            content.clip = [x - srcX, y - srcY, w, h];
            layers.push({ content, opacity: alpha });
            return;
          }
          const key = [x, y, w, h, srcX, srcY, updW, updH].join(",");
          prepare(content, frame.version, key, (ctx) => {
            ctx.drawImage(frame.source, x - srcX, y - srcY, w, h);
          });
          layers.push({ content, opacity: alpha });
        };
        for (let j = 0; j < vidIndex; j++) {
          const below = this._videoNodes[j];
          if (this._isIgnored(below)) continue;
          const alpha = Math.max(
            0,
            Math.min(1, parseFloat(getComputedStyle(below).opacity) || 0),
          );
          if (alpha === 0) continue;
          const bounds = below.getBoundingClientRect();
          if (
            bounds.left >= rect.right ||
            bounds.right <= rect.left ||
            bounds.top >= rect.bottom ||
            bounds.bottom <= rect.top
          )
            continue;
          add(
            below,
            videoImage(below),
            alpha,
            (bounds.left - rect.left) * this.scaleFactor,
            (bounds.top - rect.top) * this.scaleFactor,
            bounds.width * this.scaleFactor,
            bounds.height * this.scaleFactor,
          );
        }
        add(vid, image, opacity, 0, 0, drawW, drawH);
        return this.backend.compositeVideo(layers, dstX, dstY, updW, updH);
      } catch (e) {
        this._videoFadeFailed = true;
        return false;
      }
    }

    _updateDynamicVideos() {
      if (
        !this.hasTexture ||
        !this.staticSnapshotCanvas ||
        !this._videoNodes.length
      )
        return;

      const snapRect = this.snapshotTarget.getBoundingClientRect();

      const maxLensZ = this._getMaxLensZ();
      if (
        this._videoNodes.some(
          (vid) =>
            this._videoFrameState.has(vid) &&
            !videoImages.get(vid)?.retained &&
            (vid.seeking || vid.readyState < 2) &&
            !this._isIgnored(vid) &&
            this._videoRelevant(vid.getBoundingClientRect()) &&
            effectiveZ(vid) < maxLensZ &&
            parseFloat(window.getComputedStyle(vid).opacity) > 0,
        )
      )
        return false;

      const updatedRects = [];
      this._videoNodes.forEach((vid, vidIndex) => {
        if (effectiveZ(vid) >= maxLensZ) {
          return;
        }

        if (this._isIgnored(vid)) return;

        const vidOpacity = Math.max(
          0,
          Math.min(1, parseFloat(window.getComputedStyle(vid).opacity) || 0),
        );

        const rect = vid.getBoundingClientRect();
        if (!this._videoRelevant(rect)) return;
        const image = videoImage(vid);
        if (!image) return;
        const texX = (rect.left - snapRect.left) * this.scaleFactor;
        const texY = (rect.top - snapRect.top) * this.scaleFactor;
        const texW = rect.width * this.scaleFactor;
        const texH = rect.height * this.scaleFactor;

        const drawW = Math.round(texW);
        const drawH = Math.round(texH);

        if (drawW <= 0 || drawH <= 0) return;

        const drawX = Math.round(texX);
        const drawY = Math.round(texY);
        const minX = Math.max(0, drawX);
        const minY = Math.max(0, drawY);
        const maxX = Math.min(this.textureWidth, drawX + drawW);
        const maxY = Math.min(this.textureHeight, drawY + drawH);
        if (maxX <= minX || maxY <= minY) return;

        let dstX = minX,
          dstY = minY,
          endX = maxX,
          endY = maxY;
        if (this._videoSampleAreas) {
          dstX = maxX;
          dstY = maxY;
          endX = minX;
          endY = minY;
          for (const area of this._videoSampleAreas) {
            const left = Math.max(
              minX,
              Math.floor((area.left - snapRect.left) * this.scaleFactor) - 2,
            );
            const top = Math.max(
              minY,
              Math.floor((area.top - snapRect.top) * this.scaleFactor) - 2,
            );
            const right = Math.min(
              maxX,
              Math.ceil(
                (area.left + area.width - snapRect.left) * this.scaleFactor,
              ) + 2,
            );
            const bottom = Math.min(
              maxY,
              Math.ceil(
                (area.top + area.height - snapRect.top) * this.scaleFactor,
              ) + 2,
            );
            if (right <= left || bottom <= top) continue;
            dstX = Math.min(dstX, left);
            dstY = Math.min(dstY, top);
            endX = Math.max(endX, right);
            endY = Math.max(endY, bottom);
          }
        }
        const srcX = dstX - drawX;
        const srcY = dstY - drawY;
        const updW = endX - dstX;
        const updH = endY - dstY;
        if (updW <= 0 || updH <= 0) return;

        const geomKey = [
          drawX,
          drawY,
          drawW,
          drawH,
          dstX,
          dstY,
          updW,
          updH,
        ].join(",");
        const prevState = this._videoFrameState.get(vid);
        if (
          vidOpacity === 0 &&
          prevState?.opacity === 0 &&
          prevState.geom === geomKey
        )
          return;
        const frame = image.version;
        const unchangedRegion =
          prevState &&
          prevState.geom === geomKey &&
          prevState.opacity === vidOpacity &&
          !updatedRects.some(
            (updated) =>
              updated.left < rect.right &&
              updated.right > rect.left &&
              updated.top < rect.bottom &&
              updated.bottom > rect.top,
          );

        if (unchangedRegion && (vidOpacity === 0 || prevState.time === frame))
          return;
        const frameState = {
          time: frame,
          geom: geomKey,
          opacity: vidOpacity,
        };

        const style = window.getComputedStyle(vid);
        const scaledRadii = {
          tl: parseFloat(style.borderTopLeftRadius) * this.scaleFactor,
          tr: parseFloat(style.borderTopRightRadius) * this.scaleFactor,
          br: parseFloat(style.borderBottomRightRadius) * this.scaleFactor,
          bl: parseFloat(style.borderBottomLeftRadius) * this.scaleFactor,
        };
        const isRounded = Object.values(scaledRadii).some((r) => r > 0);

        if (
          !isRounded &&
          vidOpacity < 1 &&
          this._compositeVideoFrame(vid, vidIndex, image, vidOpacity, rect, {
            texX,
            texY,
            texW,
            texH,
            drawW,
            drawH,
            srcX,
            srcY,
            dstX,
            dstY,
            updW,
            updH,
          })
        ) {
          this._videoFrameState.set(vid, frameState);
          updatedRects.push(rect);
          return;
        }

        if (
          !isRounded &&
          vidOpacity >= 1 &&
          this._videoIsOpaque(vid) &&
          this.backend.blitVideo(
            vid,
            dstX,
            dstY,
            updW,
            updH,
            {
              u: srcX / drawW,
              v: srcY / drawH,
              uw: updW / drawW,
              vh: updH / drawH,
              fullWidth: maxX - minX,
              fullHeight: maxY - minY,
            },
            image.source,
          )
        ) {
          this._videoFrameState.set(vid, frameState);
          updatedRects.push(rect);
          return;
        }

        if (this._tmpCanvas.width !== updW || this._tmpCanvas.height !== updH) {
          this._tmpCanvas.width = updW;
          this._tmpCanvas.height = updH;
        }

        try {
          this._tmpCtx.save();
          this._tmpCtx.clearRect(0, 0, updW, updH);
          this._tmpCtx.translate(-srcX, -srcY);

          if (isRounded) {
            this._createRoundedRectPath(
              this._tmpCtx,
              drawW,
              drawH,
              scaledRadii,
            );
            this._tmpCtx.clip();
          }

          this._tmpCtx.drawImage(
            this.staticSnapshotCanvas,
            texX,
            texY,
            texW,
            texH,
            0,
            0,
            drawW,
            drawH,
          );

          for (let j = 0; j < vidIndex; j++) {
            const below = this._videoNodes[j];
            if (this._isIgnored(below)) continue;
            const belowOpacity = Math.max(
              0,
              Math.min(
                1,
                parseFloat(window.getComputedStyle(below).opacity) || 0,
              ),
            );
            if (belowOpacity === 0) continue;
            const bRect = below.getBoundingClientRect();
            if (
              bRect.left < rect.right &&
              bRect.right > rect.left &&
              bRect.top < rect.bottom &&
              bRect.bottom > rect.top
            ) {
              const belowImage = videoImage(below);
              if (!belowImage) continue;
              this._tmpCtx.globalAlpha = belowOpacity;
              this._tmpCtx.drawImage(
                belowImage.source,
                (bRect.left - rect.left) * this.scaleFactor,
                (bRect.top - rect.top) * this.scaleFactor,
                bRect.width * this.scaleFactor,
                bRect.height * this.scaleFactor,
              );
            }
          }

          this._tmpCtx.globalAlpha = vidOpacity;
          this._tmpCtx.drawImage(image.source, 0, 0, drawW, drawH);
          this._tmpCtx.restore();
        } catch (e) {
          console.warn("liquidGL: Error drawing video frame", e);
          return;
        }

        this.backend.uploadRegion(dstX, dstY, this._tmpCanvas);
        this._videoFrameState.set(vid, frameState);
        updatedRects.push(rect);
      });
    }

    /* ----------------------------- */
    _invalidateDynamicRegion(x, y, w, h) {
      for (const { el } of this._dynamicNodes) {
        const meta = this._dynMeta.get(el);
        const rect = meta?.prevDrawRect;
        if (
          rect &&
          x < rect.x + rect.w &&
          x + w > rect.x &&
          y < rect.y + rect.h &&
          y + h > rect.y
        )
          meta._compositeDirty = true;
      }
    }

    _updateDynamicNodes() {
      if (!this.hasTexture || !this._dynMeta || !this._dynamicNodes.length)
        return;
      const snapRect = this.snapshotTarget.getBoundingClientRect();
      const maxLensZ = this._getMaxLensZ();

      const lensRects = this.lenses.map((ln) => ln.rectPx).filter(Boolean);

      const rectsIntersect = (a, b) =>
        a.left < b.left + b.width &&
        a.left + a.width > b.left &&
        a.top < b.top + b.height &&
        a.top + a.height > b.top;

      if (!this._compositeCtx) {
        this._compositeCtx = document.createElement("canvas").getContext("2d");
      }

      let videoRects;
      const getVideoLayers = (rect) => {
        videoRects ||= this._videoNodes
          .filter((vid) => effectiveZ(vid) < maxLensZ)
          .map((vid) => ({ el: vid, rect: vid.getBoundingClientRect() }));
        const layers = [];
        for (const video of videoRects) {
          if (!rectsIntersect(rect, video.rect)) continue;
          video.opacity ??= Math.max(
            0,
            Math.min(
              1,
              parseFloat(window.getComputedStyle(video.el).opacity) || 0,
            ),
          );
          if (video.opacity === 0) continue;
          video.image ||= videoImage(video.el);
          if (video.image) layers.push(video);
        }
        return layers;
      };

      this._dynamicNodes.forEach((node) => {
        const el = node.el;
        const meta = this._dynMeta.get(el);
        if (!meta) return;
        if (this._contentLayers.some((layer) => layer.el.contains(el))) {
          meta.prevDrawRect = null;
          return;
        }

        if (meta.needsRecapture && !meta._capturing && !this._isScrolling) {
          meta._capturing = true;

          const ignoreDynamic = (n) =>
            n.tagName === "CANVAS" || n.hasAttribute("data-liquid-ignore");

          if (this._naughtyQueued) {
            meta._capturing = false;
          } else {
            this._naughtyQueued = true;
            const capture = () => {
              if (this._destroyed || this._dynMeta.get(el) !== meta) return;
              this._naughtyQueued = false;
              try {
                const cv = NaughtyDOM.rasterise(el, {
                  scale: this.scaleFactor,
                  ignoreElements: ignoreDynamic,
                  canvas: meta.naughtyCanvas,
                });
                meta.naughtyCanvas = cv;
                if (cv.width > 0 && cv.height > 0) {
                  meta.lastCapture = cv;
                  meta._compositeDirty = true;
                  meta.needsRecapture = false;
                  this._renderSignature = null;
                }
              } catch (e) {
                console.error("liquidGL: Dynamic element capture failed.", e);
              }
              meta._capturing = false;
            };
            if (typeof requestIdleCallback === "function") {
              this._captureIdle = requestIdleCallback(capture, {
                timeout: 100,
              });
            } else {
              this._captureTimeout = setTimeout(capture, 0);
            }
          }
        }

        if (meta.lastCapture) {
          const rect = el.getBoundingClientRect();
          const style = window.getComputedStyle(el);
          const visible =
            effectiveZ(el) < maxLensZ &&
            document.contains(el) &&
            rect.width > 0 &&
            rect.height > 0 &&
            lensRects.some((lr) => rectsIntersect(rect, lr));
          const media = visible ? getVideoLayers(rect) : [];
          const state = [
            meta.lastCapture,
            this.staticSnapshotCanvas,
            rect.left,
            rect.top,
            rect.width,
            rect.height,
            snapRect.left,
            snapRect.top,
            this.scaleFactor,
            this.textureWidth,
            this.textureHeight,
            style.transform,
            style.opacity,
            visible,
          ];
          for (const video of media) {
            state.push(
              video.el,
              video.image.version,
              video.opacity,
              video.rect.left,
              video.rect.top,
              video.rect.width,
              video.rect.height,
            );
          }
          if (
            meta.prevDrawRect &&
            !meta._compositeDirty &&
            !meta.needsRecapture &&
            !meta._capturing &&
            !(this._workerEnabled && meta._heavyAnim) &&
            meta._compositeState?.length === state.length &&
            state.every((value, i) => value === meta._compositeState[i])
          )
            return;
          meta._compositeState = null;
          if (meta.prevDrawRect && !(this._workerEnabled && meta._heavyAnim)) {
            const { x, y, w, h } = meta.prevDrawRect;
            if (w > 0 && h > 0) {
              const eraseCanvas = this._compositeCtx.canvas;
              if (eraseCanvas.width !== w || eraseCanvas.height !== h) {
                eraseCanvas.width = w;
                eraseCanvas.height = h;
              }
              this._compositeCtx.drawImage(
                this.staticSnapshotCanvas,
                x,
                y,
                w,
                h,
                0,
                0,
                w,
                h,
              );
              this.backend.uploadRegion(x, y, eraseCanvas);
            }
          }

          if (!visible) {
            meta.prevDrawRect = null;
            return;
          }

          const texX = (rect.left - snapRect.left) * this.scaleFactor;
          const texY = (rect.top - snapRect.top) * this.scaleFactor;
          const drawW = Math.round(rect.width * this.scaleFactor);
          const drawH = Math.round(rect.height * this.scaleFactor);
          const drawX = Math.round(texX);
          const drawY = Math.round(texY);

          if (drawW <= 0 || drawH <= 0) return;

          const maxW = this.textureWidth;
          const maxH = this.textureHeight;
          let dstX = drawX;
          let dstY = drawY;
          let srcX = 0,
            srcY = 0,
            updW = drawW,
            updH = drawH;

          if (dstX < 0) {
            srcX = -dstX;
            updW += dstX;
            dstX = 0;
          }
          if (dstY < 0) {
            srcY = -dstY;
            updH += dstY;
            dstY = 0;
          }

          if (dstX + updW > maxW) {
            updW = maxW - dstX;
          }
          if (dstY + updH > maxH) {
            updH = maxH - dstY;
          }

          if (updW <= 0 || updH <= 0) return;

          const compositeCanvas = this._compositeCtx.canvas;
          if (
            compositeCanvas.width !== drawW ||
            compositeCanvas.height !== drawH
          ) {
            compositeCanvas.width = drawW;
            compositeCanvas.height = drawH;
          }
          this._compositeCtx.clearRect(0, 0, drawW, drawH);

          this._compositeCtx.drawImage(
            this.staticSnapshotCanvas,
            texX,
            texY,
            rect.width * this.scaleFactor,
            rect.height * this.scaleFactor,
            0,
            0,
            drawW,
            drawH,
          );
          for (const video of media) {
            this._compositeCtx.globalAlpha = video.opacity;
            this._compositeCtx.drawImage(
              video.image.source,
              (video.rect.left - rect.left) * this.scaleFactor,
              (video.rect.top - rect.top) * this.scaleFactor,
              video.rect.width * this.scaleFactor,
              video.rect.height * this.scaleFactor,
            );
          }
          this._compositeCtx.globalAlpha = 1;

          this._compositeCtx.save();
          this._compositeCtx.translate(drawW / 2, drawH / 2);
          if (style.transform !== "none") {
            this._compositeCtx.transform(
              ...this._parseTransform(style.transform),
            );
          }
          this._compositeCtx.translate(-drawW / 2, -drawH / 2);
          this._compositeCtx.globalAlpha = parseFloat(style.opacity) || 1.0;
          this._compositeCtx.drawImage(meta.lastCapture, 0, 0, drawW, drawH);
          this._compositeCtx.restore();

          this.backend.uploadRegion(dstX, dstY, compositeCanvas, srcX, srcY);

          if (this._workerEnabled && meta._heavyAnim) {
            const jobId = `${Date.now()}_${Math.random()}`;
            this._dynJobs.set(jobId, {
              x: dstX,
              y: dstY,
              w: updW,
              h: updH,
            });

            Promise.all([
              createImageBitmap(
                this.staticSnapshotCanvas,
                dstX,
                dstY,
                updW,
                updH,
              ),
              createImageBitmap(meta.lastCapture),
            ]).then(([snapBmp, dynBmp]) => {
              this._dynWorker.postMessage(
                {
                  id: jobId,
                  width: updW,
                  height: updH,
                  snap: snapBmp,
                  dyn: dynBmp,
                },
                [snapBmp, dynBmp],
              );
            });
            meta.prevDrawRect = { x: dstX, y: dstY, w: updW, h: updH };
            return;
          }

          meta.prevDrawRect = { x: dstX, y: dstY, w: updW, h: updH };
          meta._compositeState = state;
          meta._compositeDirty = false;
        }
      });
    }

    _parseTransform(transform) {
      if (transform === "none") return [1, 0, 0, 1, 0, 0];
      const matrixMatch = transform.match(/matrix\((.+)\)/);
      if (matrixMatch) {
        const values = matrixMatch[1].split(",").map(parseFloat);
        return values;
      }
      const matrix3dMatch = transform.match(/matrix3d\((.+)\)/);
      if (matrix3dMatch) {
        const v = matrix3dMatch[1].split(",").map(parseFloat);
        return [v[0], v[1], v[4], v[5], v[12], v[13]];
      }
      return [1, 0, 0, 1, 0, 0];
    }

    /* ----------------------------- */
    _localLensZ(lens) {
      return (
        (lens.options.zIndex ?? effectiveZ(lens.el)) -
        (this._anchor === document.body ? 0 : effectiveZ(this._anchor))
      );
    }

    _getMaxLensZ() {
      let maxZ = this.lenses.length ? -Infinity : 0;
      this.lenses.forEach((ln) => {
        const z = ln.options.zIndex ?? effectiveZ(ln.el);
        if (z > maxZ) maxZ = z;
      });
      return maxZ;
    }

    /* ----------------------------- */
    addDynamicElement(el) {
      if (!el) return;
      if (typeof el === "string") {
        this.snapshotTarget
          .querySelectorAll(el)
          .forEach((n) => this.addDynamicElement(n));
        return;
      }
      if (NodeList.prototype.isPrototypeOf(el) || Array.isArray(el)) {
        Array.from(el).forEach((n) => this.addDynamicElement(n));
        return;
      }
      if (!el.getBoundingClientRect || !this.snapshotTarget.contains(el))
        return;
      if (el.closest && el.closest("[data-liquid-ignore]")) return;
      if (this._dynamicNodes.some((n) => n.el === el)) return;

      this._dynamicNodes = this._dynamicNodes.filter((n) => !el.contains(n.el));

      const meta = {
        _capturing: false,
        prevDrawRect: null,
        lastCapture: null,
        naughtyCanvas: null,
        needsRecapture: true,
        hoverClassName: null,
        _animating: false,
        _rafId: null,
        _lastCaptureTs: 0,
        _heavyAnim: false,
      };
      this._dynMeta.set(el, meta);

      const setDirty = () => {
        const m = this._dynMeta.get(el);
        if (m && !m.needsRecapture) {
          m.needsRecapture = true;
          cancelAnimationFrame(this._dirtyRaf);
          this._dirtyRaf = requestAnimationFrame(() => this.render());
        }
      };

      const findAppliedHoverStyles = (element) => {
        let cssText = "";
        for (const sheet of document.styleSheets) {
          try {
            for (const rule of sheet.cssRules) {
              if (!rule.selectorText || !rule.selectorText.includes(":hover")) {
                continue;
              }
              const baseSelector = rule.selectorText.split(":hover")[0];
              if (element.matches(baseSelector)) {
                cssText += rule.style.cssText;
              }
            }
          } catch (e) {}
        }
        return cssText;
      };

      const handleLeave = () => {
        const m = this._dynMeta.get(el);
        if (!m || !m.hoverClassName) return;

        el.classList.remove(m.hoverClassName);
        for (let i = this._dynamicStyleSheet.cssRules.length - 1; i >= 0; i--) {
          const rule = this._dynamicStyleSheet.cssRules[i];
          if (rule.selectorText === `.${m.hoverClassName}`) {
            this._dynamicStyleSheet.deleteRule(i);
            break;
          }
        }
        m.hoverClassName = null;
        setDirty();
      };

      listen(
        this,
        el,
        "mouseenter",
        () => {
          const m = this._dynMeta.get(el);
          if (!m) return;
          const hoverCss = findAppliedHoverStyles(el);
          if (hoverCss) {
            const className = `lqgl-h-${Math.random()
              .toString(36)
              .substr(2, 9)}`;
            const rule = `.${className} { ${hoverCss} }`;
            try {
              this._dynamicStyleSheet.insertRule(
                rule,
                this._dynamicStyleSheet.cssRules.length,
              );
              m.hoverClassName = className;
              el.classList.add(className);
            } catch (e) {
              console.error("liquidGL: Failed to insert hover style rule.", e);
            }
          }
          setDirty();
        },
        { passive: true },
      );

      listen(this, el, "mouseleave", handleLeave, { passive: true });
      listen(this, el, "transitionend", setDirty, { passive: true });

      const startRealtime = () => {
        const m = this._dynMeta.get(el);
        if (!m || m._animating) return;
        m._animating = true;

        m._heavyAnim = false;

        const step = (ts) => {
          const meta = this._dynMeta.get(el);
          if (!meta || !meta._animating) return;

          if (
            meta._heavyAnim &&
            !meta._capturing &&
            ts - meta._lastCaptureTs > RECAPTURE_INTERVAL_MS
          ) {
            meta._lastCaptureTs = ts;
            meta.needsRecapture = true;
          }
          if (meta._heavyAnim) {
            meta._rafId = requestAnimationFrame(step);
          } else {
            meta._rafId = null;
          }
        };
        m._rafId = requestAnimationFrame(step);
      };

      const trackProperty = (prop) => {
        const m = this._dynMeta.get(el);
        if (!m) return;
        const low = (prop || "").toLowerCase();
        if (!(low.includes("transform") || low.includes("opacity"))) {
          const wasHeavy = m._heavyAnim;
          m._heavyAnim = true;
          if (m._animating && !wasHeavy && !m._rafId) {
            m._animating = false;
            startRealtime();
          }
        }
      };

      const transitionRunHandler = (e) => {
        trackProperty(e.propertyName);
        startRealtime();
      };

      listen(this, el, "transitionrun", transitionRunHandler, {
        passive: true,
      });
      listen(this, el, "transitionstart", transitionRunHandler, {
        passive: true,
      });
      listen(
        this,
        el,
        "animationstart",
        () => {
          const m = this._dynMeta.get(el);
          if (m) m._heavyAnim = true;
          startRealtime();
        },
        { passive: true },
      );

      listen(
        this,
        el,
        "animationiteration",
        () => {
          const m = this._dynMeta.get(el);
          if (m) {
            m._heavyAnim = true;
            if (!m._animating) startRealtime();
          }
        },
        { passive: true },
      );

      const stopRealtime = () => {
        const m = this._dynMeta.get(el);
        if (!m || !m._animating) return;
        m._animating = false;
        if (m._rafId) {
          cancelAnimationFrame(m._rafId);
          m._rafId = null;
        }
        m._heavyAnim = false;
        setDirty();
      };

      listen(this, el, "transitionend", stopRealtime, { passive: true });
      listen(this, el, "transitioncancel", stopRealtime, { passive: true });
      listen(this, el, "animationend", stopRealtime, { passive: true });
      listen(this, el, "animationcancel", stopRealtime, { passive: true });

      /* --------------------------------------------------
       *  Removal clean-up
       * --------------------------------------------------*/
      if (typeof MutationObserver !== "undefined") {
        const removalObserver = new MutationObserver(() => {
          if (!document.contains(el)) {
            handleLeave();
            cancelAnimationFrame(this._dynMeta.get(el)?._rafId);
            removalObserver.disconnect();
            this._dynamicNodes = this._dynamicNodes.filter((n) => n.el !== el);
            this._dynMeta.delete(el);
          }
        });
        removalObserver.observe(document.body, {
          childList: true,
          subtree: true,
        });
        this._cleanups.push(() => removalObserver.disconnect());
      }

      this._dynamicNodes.push({ el });
    }

    /* ----------------------------- */
    _isIgnored(el) {
      return !!(
        el &&
        typeof el.closest === "function" &&
        el.closest("[data-liquid-ignore]")
      );
    }
  }

  /* --------------------------------------------------
   *  Per-element lens wrapper
   * ------------------------------------------------*/
  class liquidGLLens {
    constructor(renderer, element, options) {
      this.renderer = renderer;
      this.el = element;
      this.options = options;
      this._initCalled = false;
      this.rectPx = null;
      this.radiusGl = 0;
      this.radiusCss = 0;
      this.revealTypeIndex = this.options.reveal === "fade" ? 1 : 0;
      this._revealProgress = this.revealTypeIndex === 0 ? 1 : 0;
      this.tiltX = 0;
      this.tiltY = 0;
      this._activated = false;
      this._destroyed = false;
    }

    destroy() {
      if (this._destroyed) return;
      this._destroyed = true;
      const renderer = this.renderer;
      if (renderer) renderer._tearingDown = true;
      this._sizeObs?.disconnect();
      if (
        this._dragPointerId != null &&
        this.el.hasPointerCapture?.(this._dragPointerId)
      )
        this.el.releasePointerCapture(this._dragPointerId);
      this._unbindDragHandlers();
      this._unbindTiltHandlers();
      this._destroyMirrorCanvas();
      this._cleanups?.splice(0).forEach((cleanup) => cleanup());
      this._shadowEl?.remove();
      this._shadowEl = null;
      if (renderer && this._tiltMeasureStyle)
        removeRule(renderer._dynamicStyleSheet, this._tiltMeasureStyle);
      this._restoreStyles?.();
      if (this._interactionStylesChanged) this._restoreInteractionStyles?.();
      this._restoreStyles = this._restoreInteractionStyles = null;
      this._fluid = null;
      this._activated = false;
      if (renderer) {
        renderer._tearingDown = false;
        renderer.removeLens(this);
      }
      this.renderer = null;
    }

    /* ----------------------------- */
    _activate() {
      if (this._destroyed || this._activated) return;
      this._activated = true;
      this._restoreStyles = restoreStyles(this.el, [
        "background",
        "backdrop-filter",
        "-webkit-backdrop-filter",
        "box-shadow",
        "opacity",
        "transition",
        "position",
        "pointer-events",
      ]);
      this._restoreInteractionStyles = restoreStyles(this.el, [
        "transform",
        "transform-style",
        "transform-origin",
        "cursor",
        "touch-action",
      ]);

      this.originalShadow = this.el.style.boxShadow;
      this.originalOpacity = this.el.style.opacity;
      this.originalTransition = this.el.style.transition;
      this.el.style.transition = "none";
      this.el.style.opacity = 0;

      this.el.style.position =
        this.el.style.position === "static"
          ? "relative"
          : this.el.style.position;

      this._isSticky = /sticky/.test(window.getComputedStyle(this.el).position);

      const bgCol = window.getComputedStyle(this.el).backgroundColor;
      const rgbaMatch = bgCol.match(/rgba?\(([^)]+)\)/);
      this._bgColorComponents = null;
      if (rgbaMatch) {
        const comps = rgbaMatch[1].split(/[ ,]+/).map(parseFloat);
        const [r, g, b, a = 1] = comps;
        this._bgColorComponents = { r, g, b, a };
        this.el.style.backgroundColor = `rgba(${r}, ${g}, ${b}, 0)`;
      }

      this.el.style.backdropFilter = "none";
      this.el.style.webkitBackdropFilter = "none";
      this.el.style.backgroundImage = "none";
      this.el.style.background = "transparent";

      if (this.options.draggable) {
        this._bindDragHandlers();
      } else {
        this.el.style.pointerEvents = "none";
      }

      this.updateMetrics();
      this.setShadow(this.options.shadow);
      if (this.options.tilt && !this.options.draggable)
        this._bindTiltHandlers();

      if (typeof ResizeObserver !== "undefined" && !this._sizeObs) {
        this._sizeObs = new ResizeObserver(() => {
          if (this._destroyed) return;
          this.updateMetrics();
          this.renderer.render();
        });
        this._sizeObs.observe(this.el);
      }
    }

    /* ----------------------------- */
    updateMetrics() {
      if (this._destroyed) return;
      const rect =
        this._mirrorActive && this._baseRect
          ? this._baseRect
          : this.el.getBoundingClientRect();

      const origin = this.renderer.canvas.getBoundingClientRect();
      const vpX = -origin.left;
      const vpY = -origin.top;

      const prev = this.rectPx;
      if (
        prev &&
        rect.left === prev.left &&
        rect.top === prev.top &&
        rect.width === prev.width &&
        rect.height === prev.height &&
        vpX === this._vpOffsetX &&
        vpY === this._vpOffsetY
      ) {
        return;
      }
      this._vpOffsetX = vpX;
      this._vpOffsetY = vpY;

      this.rectPx = {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height,
      };

      const style = window.getComputedStyle(this.el);
      const brRaw = style.borderTopLeftRadius.split(" ")[0];
      const isPct = brRaw.trim().endsWith("%");
      let brPx;
      if (isPct) {
        const pct = parseFloat(brRaw);
        brPx = (Math.min(rect.width, rect.height) * pct) / 100;
      } else {
        brPx = parseFloat(brRaw);
      }
      const maxAllowedCss = Math.min(rect.width, rect.height) * 0.5;
      this.radiusCss = Math.min(brPx, maxAllowedCss);

      const dpr = Math.min(2, window.devicePixelRatio || 1);
      this.radiusGl = this.radiusCss * dpr;

      if (this._shadowSyncFn) {
        this._shadowSyncFn();
      }
    }

    /* ----------------------------- */
    _handleOverscrollCompensation() {
      let overscrollY = 0;
      let overscrollX = 0;

      const vv = window.visualViewport;
      if (vv) {
        if (Math.abs(vv.scale - 1) < 0.01) {
          overscrollX = -vv.offsetLeft;
          overscrollY = -vv.offsetTop;
        }
      } else {
        const bodyStyle = window.getComputedStyle(document.body);
        const htmlStyle = window.getComputedStyle(document.documentElement);

        if (bodyStyle.transform && bodyStyle.transform !== "none") {
          const matrix = new DOMMatrix(bodyStyle.transform);
          overscrollX = matrix.m41;
          overscrollY = matrix.m42;
        }

        if (
          overscrollY === 0 &&
          overscrollX === 0 &&
          htmlStyle.transform &&
          htmlStyle.transform !== "none"
        ) {
          const matrix = new DOMMatrix(htmlStyle.transform);
          overscrollX = matrix.m41;
          overscrollY = matrix.m42;
        }
      }

      this._currentOverscrollX = overscrollX;
      this._currentOverscrollY = overscrollY;

      if (overscrollY !== 0 || overscrollX !== 0) {
        const compensationTransform = `translate(${-overscrollX}px, ${-overscrollY}px)`;

        let currentTransform = this.el.style.transform;
        currentTransform = currentTransform
          .replace(/translate\([^)]*\)\s*/g, "")
          .trim();

        this.el.style.transform =
          compensationTransform +
          (currentTransform ? " " + currentTransform : "");

        if (this._shadowEl) {
          let shadowTransform = this._shadowEl.style.transform || "";
          shadowTransform = shadowTransform
            .replace(/translate\([^)]*\)\s*/g, "")
            .trim();
          this._shadowEl.style.transform =
            compensationTransform +
            (shadowTransform ? " " + shadowTransform : "");
        }
      } else if (!this._tiltInteracting) {
        this.el.style.transform = this._savedTransform || "";
        if (this._shadowEl) {
          this._shadowEl.style.transform = "";
        }
      }
    }

    /* ----------------------------- */
    setTilt(enabled) {
      if (this._destroyed) return;
      this.options.tilt = !!enabled;
      if (this.options.tilt) {
        this._bindTiltHandlers();
      } else {
        this._unbindTiltHandlers();
      }
    }

    /* ----------------------------- */
    setTint(value) {
      if (this._destroyed) return;
      this.options.tint = parseTintColor(value);
      return this.options.tint;
    }

    /* ----------------------------- */
    setDraggable(enabled) {
      if (this._destroyed) return;
      this.options.draggable = !!enabled;
      if (this.options.draggable) {
        this._bindDragHandlers();
      } else {
        this._unbindDragHandlers();
        this.el.style.pointerEvents = "none";
      }
    }

    /* ----------------------------- */
    _bindDragHandlers() {
      if (this._destroyed || !this.renderer) return;
      this._interactionStylesChanged = true;
      this.el.style.pointerEvents = "auto";
      if (this._dragHandlersBound) return;
      this._dragHandlersBound = true;

      this._savedCursor = this.el.style.cursor;
      this._savedTouchAction = this.el.style.touchAction;
      this._savedDragTransform = this.el.style.transform;
      this.el.style.cursor = "grab";
      this.el.style.touchAction = "none";

      this._dragX = 0;
      this._dragY = 0;
      this._dragBaseX = 0;
      this._dragBaseY = 0;
      this._dragPointerId = null;

      this._onDragDown = (e) => {
        if (e.button) return;
        if (this._dragPointerId !== null) return;
        this._dragPointerId = e.pointerId;
        this._dragStartX = e.clientX;
        this._dragStartY = e.clientY;
        this.el.style.cursor = "grabbing";
        e.preventDefault();
        if (this.options.on && this.options.on.dragstart) {
          this.options.on.dragstart(this, {
            clientX: e.clientX,
            clientY: e.clientY,
          });
        }
      };

      this._onDragMove = (e) => {
        if (this._dragPointerId === null || e.pointerId !== this._dragPointerId)
          return;
        this._dragX = this._dragBaseX + (e.clientX - this._dragStartX);
        this._dragY = this._dragBaseY + (e.clientY - this._dragStartY);
        this.el.style.transform = `translate3d(${this._dragX}px, ${this._dragY}px, 0)`;
        this.updateMetrics();
        this.renderer.render();
        if (this.options.on && this.options.on.drag) {
          this.options.on.drag(this, {
            clientX: e.clientX,
            clientY: e.clientY,
            x: this._dragX,
            y: this._dragY,
          });
        }
      };

      this._onDragEnd = (e) => {
        if (
          this._dragPointerId === null ||
          (e && e.pointerId !== this._dragPointerId)
        )
          return;
        const info = {
          clientX: e ? e.clientX : undefined,
          clientY: e ? e.clientY : undefined,
          x: this._dragX,
          y: this._dragY,
        };
        this._dragPointerId = null;
        this._dragBaseX = this._dragX;
        this._dragBaseY = this._dragY;
        this.el.style.cursor = "grab";
        if (this.options.on && this.options.on.dragend) {
          this.options.on.dragend(this, info);
        }
      };

      listen(this, this.el, "pointerdown", this._onDragDown);
      listen(this, window, "pointermove", this._onDragMove);
      listen(this, window, "pointerup", this._onDragEnd);
      listen(this, window, "pointercancel", this._onDragEnd);
    }

    _unbindDragHandlers() {
      if (!this._dragHandlersBound) return;
      this.el.removeEventListener("pointerdown", this._onDragDown);
      window.removeEventListener("pointermove", this._onDragMove);
      window.removeEventListener("pointerup", this._onDragEnd);
      window.removeEventListener("pointercancel", this._onDragEnd);
      this._dragPointerId = null;
      this.el.style.cursor = this._savedCursor || "";
      this.el.style.touchAction = this._savedTouchAction || "";
      this.clearDrag();
      this._dragHandlersBound = false;
    }

    clearDrag() {
      if (this._destroyed) return;
      this._dragX = 0;
      this._dragY = 0;
      this._dragBaseX = 0;
      this._dragBaseY = 0;
      this._dragStartX = 0;
      this._dragStartY = 0;
      this.el.style.transform = this._savedDragTransform || "";
      this.updateMetrics();
      this.renderer.render();
    }

    /* ----------------------------- */
    _syncShadowMode(force = false) {
      const fluid = this.options.interaction === "fluid" && this.options.shadow;
      if (!force && this._fluidShadowActive === fluid) return;
      this._fluidShadowActive = fluid;
      if (this._shadowEl) {
        this._shadowEl.style.display = "";
        this._shadowEl.style.boxShadow = fluid ? "none" : this._cssShadow;
      }
      this.el.style.boxShadow = fluid
        ? "none"
        : this.options.shadow
          ? this._cssShadow
          : this.originalShadow;
    }

    setShadow(enabled) {
      if (this._destroyed) return;
      this.options.shadow = !!enabled;

      const SHADOW_VAL =
        "0 10px 30px rgba(0,0,0,0.1), 0 0 0 0.5px rgba(0,0,0,0.05)";
      this._cssShadow = SHADOW_VAL;

      const syncShadow = () => {
        if (!this._shadowEl) return;
        const r =
          this._mirrorActive && this._baseRect
            ? this._baseRect
            : this.el.getBoundingClientRect();
        const origin = this.renderer.canvas.getBoundingClientRect();
        this._shadowEl.style.left = `${r.left - origin.left + parseFloat(this.renderer.canvas.style.left)}px`;
        this._shadowEl.style.top = `${r.top - origin.top + parseFloat(this.renderer.canvas.style.top)}px`;
        this._shadowEl.style.width = `${r.width}px`;
        this._shadowEl.style.height = `${r.height}px`;
        this._shadowEl.style.borderRadius = `${this.radiusCss}px`;
      };

      if (enabled) {
        this.el.style.boxShadow = SHADOW_VAL;

        if (!this._shadowEl) {
          this._shadowEl = document.createElement("div");
          Object.assign(this._shadowEl.style, {
            position: "absolute",
            pointerEvents: "none",
            zIndex: this.renderer._localLensZ(this) - 2,
            boxShadow: SHADOW_VAL,
            willChange: "transform, width, height",
            opacity: this._revealProgress ?? 1,
          });
          this.renderer._anchor.appendChild(this._shadowEl);

          this._shadowSyncFn = syncShadow;
          listen(this, window, "resize", this._shadowSyncFn, {
            passive: true,
          });
        }
        syncShadow();
      } else {
        if (this._shadowEl) {
          window.removeEventListener("resize", this._shadowSyncFn);
          this._shadowEl.remove();
          this._shadowEl = null;
        }
        this.el.style.boxShadow = this.originalShadow;
      }
      this._syncShadowMode(true);
    }

    /* ----------------------------- */
    _reveal() {
      if (this._destroyed || !this._activated) return;
      if (this.revealTypeIndex === 0) {
        this.el.style.opacity = this.originalOpacity || 1;
        this.renderer.canvas.style.opacity = "1";
        this._revealProgress = 1;
        this._TriggerInit();
        return;
      }

      if (this.renderer._revealAnimating) return;

      this.renderer._revealAnimating = true;

      const dur = 1000;
      const start = performance.now();

      const renderer = this.renderer;
      const animate = () => {
        if (renderer._destroyed) return;
        const progress = Math.min(1, (performance.now() - start) / dur);

        renderer.lenses.forEach((ln) => {
          ln._revealProgress = progress;
          ln.el.style.opacity = (ln.originalOpacity || 1) * progress;
          if (ln._shadowEl) {
            ln._shadowEl.style.opacity = progress;
          }
        });

        renderer.canvas.style.opacity = String(progress);

        renderer.render();

        if (progress < 1) {
          renderer._revealRaf = requestAnimationFrame(animate);
        } else {
          renderer._revealAnimating = false;
          renderer.lenses.slice().forEach((ln) => {
            if (ln._destroyed) return;
            ln.el.style.transition = ln.originalTransition || "";
            ln._TriggerInit();
          });
        }
      };

      renderer._revealRaf = requestAnimationFrame(animate);
    }

    /* ----------------------------- */
    _bindTiltHandlers() {
      if (this._destroyed || !this.renderer) return;
      this._interactionStylesChanged = true;
      if (this._tiltHandlersBound) return;

      if (this._savedTransform === undefined) {
        const currentTransform = this.el.style.transform;
        if (currentTransform && currentTransform.includes("translate")) {
          this._savedTransform = currentTransform
            .replace(/translate\([^)]*\)\s*/g, "")
            .trim();
          if (this._savedTransform === "") this._savedTransform = "none";
        } else {
          this._savedTransform = currentTransform;
        }
      }
      if (this._savedTransformStyle === undefined) {
        this._savedTransformStyle = this.el.style.transformStyle;
      }
      this.el.style.transformStyle = "preserve-3d";

      const getMaxTilt = () =>
        Number.isFinite(this.options.tiltFactor) ? this.options.tiltFactor : 5;

      const getTiltEase = () =>
        Number.isFinite(this.options.tiltEase)
          ? Math.max(0, this.options.tiltEase)
          : 400;

      this._savedTiltTransition = this.el.style.transition;
      this._savedTiltOrigin = this.el.style.transformOrigin;

      this._applyTilt = (clientX, clientY) => {
        if (!this._tiltInteracting) {
          this._cancelTiltEase();
          this._tiltInteracting = true;
          this._tiltEnterUntil = performance.now() + getTiltEase();
          if (!this._mirror) this._createMirrorCanvas();
        }
        this._tiltActive = true;
        const r = this._baseRect;
        if (!r || !r.width || !r.height) return;
        const maxTilt = getMaxTilt();
        const rotY =
          ((clientX - r.left - r.width / 2) / (r.width / 2)) * maxTilt;
        const rotX =
          -((clientY - r.top - r.height / 2) / (r.height / 2)) * maxTilt;
        this._easeTiltTo(
          rotX,
          rotY,
          Math.max(0, this._tiltEnterUntil - performance.now()),
        );
      };

      this._drawTilt = () => {
        const r = this._baseRect;
        if (!r) return;
        const origin = this.renderer.canvas.getBoundingClientRect();
        this._pivotOrigin = `${r.left - origin.left + r.width / 2}px ${r.top - origin.top + r.height / 2}px`;
        const rotX = this.tiltX;
        const rotY = this.tiltY;
        this.el.style.transition = "none";
        if (this._mirror) this._mirror.style.transition = "none";
        if (this._shadowEl) this._shadowEl.style.transition = "none";
        const baseTransform =
          this._savedTransform && this._savedTransform !== "none"
            ? this._savedTransform + " "
            : "";

        let overscrollCompensation = "";
        const bodyStyle = window.getComputedStyle(document.body);
        if (bodyStyle.transform && bodyStyle.transform !== "none") {
          const matrix = new DOMMatrix(bodyStyle.transform);
          const overscrollX = matrix.m41;
          const overscrollY = matrix.m42;
          if (overscrollX !== 0 || overscrollY !== 0) {
            overscrollCompensation = `translate(${-overscrollX}px, ${-overscrollY}px) `;
          }
        }

        const transformStr = `${overscrollCompensation}${baseTransform}perspective(800px) rotateX(${rotX}deg) rotateY(${rotY}deg)`;

        this.el.style.transformOrigin = `50% 50%`;
        this.el.style.transform = transformStr;

        if (this._mirror) {
          this._mirror.style.transformOrigin = this._pivotOrigin;
          this._mirror.style.transform = transformStr;
        }

        if (this._shadowEl) {
          this._shadowEl.style.transformOrigin = `50% 50%`;
          this._shadowEl.style.transform = transformStr;
        }

        this.renderer.render();
      };

      this._cancelTiltEase = () => {
        if (this._tiltEaseRaf) {
          cancelAnimationFrame(this._tiltEaseRaf);
          this._tiltEaseRaf = null;
        }
      };

      this._finishTilt = () => {
        this._destroyMirrorCanvas();
        this.el.style.transition = "none";
        this.el.style.transform = this._savedTransform || "";
        this.el.style.transformOrigin = this._savedTiltOrigin;
        if (this._shadowEl) this._shadowEl.style.transform = "";
        this.renderer.render();
      };

      this._easeTiltTo = (toX, toY, duration) => {
        this._tiltTargetX = toX;
        this._tiltTargetY = toY;
        if (this._tiltInteracting && duration > 0 && this._tiltEaseRaf) return;
        this._cancelTiltEase();
        const fromX = this.tiltX;
        const fromY = this.tiltY;
        const start = performance.now();
        const step = (now) => {
          this._tiltEaseRaf = null;
          const t = duration > 0 ? Math.min(1, (now - start) / duration) : 1;
          const eased = 1 - Math.pow(1 - t, 3);
          this.tiltX = fromX + (this._tiltTargetX - fromX) * eased;
          this.tiltY = fromY + (this._tiltTargetY - fromY) * eased;
          this._drawTilt();
          if (t < 1) {
            this._tiltEaseRaf = requestAnimationFrame(step);
          } else if (!this._tiltInteracting) {
            this._finishTilt();
          }
        };
        if (duration <= 0 || (fromX === toX && fromY === toY)) {
          duration = 0;
          step(start);
        } else {
          this._tiltEaseRaf = requestAnimationFrame(step);
        }
      };

      this._smoothReset = () => {
        if (!this._tiltInteracting) return;
        this._tiltInteracting = false;
        this._tiltActive = false;
        this._easeTiltTo(0, 0, getTiltEase());
      };

      this._onMouseEnter = (e) => this._applyTilt(e.clientX, e.clientY);
      this._onMouseMove = (e) => this._applyTilt(e.clientX, e.clientY);

      this._onTouchStart = (e) => {
        if (e.touches && e.touches.length === 1) {
          const t = e.touches[0];
          this._applyTilt(t.clientX, t.clientY);
        }
      };
      this._onTouchMove = (e) => {
        if (this._tiltTouchCancelled) return;
        if (e.touches && e.touches.length === 1) {
          const t = e.touches[0];
          this._applyTilt(t.clientX, t.clientY);
        }
      };
      this._onTouchEnd = () => {
        this._smoothReset();
      };

      listen(this, this.el, "mouseenter", this._onMouseEnter, {
        passive: true,
      });
      listen(this, this.el, "mousemove", this._onMouseMove, {
        passive: true,
      });
      listen(this, this.el, "touchstart", this._onTouchStart, {
        passive: true,
      });
      listen(this, this.el, "touchmove", this._onTouchMove, {
        passive: true,
      });
      listen(this, this.el, "touchend", this._onTouchEnd, {
        passive: true,
      });

      /* ----------------------------- */
      this._tiltActive = false;

      this._docPointerMove = (e) => {
        if (e.pointerType !== "mouse" && this._tiltTouchCancelled) return;
        const x = e.clientX ?? (e.touches && e.touches[0].clientX);
        const y = e.clientY ?? (e.touches && e.touches[0].clientY);
        if (x === undefined || y === undefined) return;

        const r = this.el.getBoundingClientRect();
        const inside =
          x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;

        if (inside) {
          if (!this._tiltActive) {
            this._tiltActive = true;
            this._onMouseEnter({ clientX: x, clientY: y });
          } else {
            this._applyTilt(x, y);
          }
        } else if (this._tiltActive) {
          this._tiltActive = false;
          this._smoothReset();
        }
      };

      listen(this, document, "pointermove", this._docPointerMove, {
        passive: true,
      });

      this._onTiltPointerStart = () => {
        this._tiltTouchCancelled = false;
      };
      listen(this, document, "pointerdown", this._onTiltPointerStart, {
        passive: true,
        capture: true,
      });
      this._onTiltScroll = () => {
        this._tiltTouchCancelled = true;
        if (!this._mirrorActive) return;
        this._cancelTiltEase();
        this._tiltInteracting = this._tiltActive = false;
        this.tiltX = this.tiltY = 0;
        this._finishTilt();
      };
      this._onTiltPointerEnd = (event) => {
        if (event.pointerType !== "mouse") this._smoothReset();
      };
      listen(this, document, "scroll", this._onTiltScroll, {
        passive: true,
        capture: true,
      });
      listen(this, document, "pointercancel", this._onTiltScroll);
      listen(this, document, "pointerup", this._onTiltPointerEnd);
      this._tiltHandlersBound = true;
    }

    _unbindTiltHandlers() {
      if (!this._tiltHandlersBound) return;
      if (this._cancelTiltEase) this._cancelTiltEase();
      this._tiltInteracting = false;
      this._tiltActive = false;
      this.tiltX = 0;
      this.tiltY = 0;
      this._finishTilt();
      this.el.removeEventListener("mouseenter", this._onMouseEnter);
      this.el.removeEventListener("mousemove", this._onMouseMove);
      this.el.removeEventListener("touchstart", this._onTouchStart);
      this.el.removeEventListener("touchmove", this._onTouchMove);
      this.el.removeEventListener("touchend", this._onTouchEnd);
      document.removeEventListener(
        "pointerdown",
        this._onTiltPointerStart,
        true,
      );
      document.removeEventListener("scroll", this._onTiltScroll, true);
      document.removeEventListener("pointercancel", this._onTiltScroll);
      document.removeEventListener("pointerup", this._onTiltPointerEnd);

      if (this._docPointerMove) {
        document.removeEventListener("pointermove", this._docPointerMove);
        this._docPointerMove = null;
      }
      this._tiltHandlersBound = false;

      this.el.style.transform = this._savedTransform || "";
      this.el.style.transformStyle = this._savedTransformStyle || "";
      this.el.style.transition = this._savedTiltTransition;

      this.renderer.render();
    }

    _measureBaseRect() {
      const tilted =
        this.tiltX !== 0 || this.tiltY !== 0 || !!this._tiltEaseRaf;
      if (!tilted) return this.el.getBoundingClientRect();

      const prevTransition = this.el.style.transition;
      const prevTransform = this.el.style.transform;

      this.el.style.transition = "none";
      this.el.style.transform = this._savedTransform || "";
      const rect = this.el.getBoundingClientRect();

      this.el.style.transform = prevTransform;
      this.el.style.transition = prevTransition;

      return rect;
    }

    _createMirrorCanvas() {
      this._baseRect = this._measureBaseRect();
      if (this._mirror) return;
      this._tiltBaseTransform = window.getComputedStyle(this.el).transform;
      if (!this._tiltMeasureStyle) {
        const sheet = this.renderer._dynamicStyleSheet;
        const index = sheet.insertRule(
          `[data-liquidgl-tilt="${this._order}"] {}`,
          sheet.cssRules.length,
        );
        this._tiltMeasureStyle = sheet.cssRules[index].style;
      }
      this.el.setAttribute("data-liquidgl-tilt", String(this._order));
      this._mirror = document.createElement("canvas");
      Object.assign(this._mirror.style, {
        position: "absolute",
        top: 0,
        left: 0,
        width: "100%",
        height: "100%",
        pointerEvents: "none",
        zIndex: this.renderer.canvas.style.zIndex,
        willChange: "transform",
      });
      this._mirror.setAttribute("data-liquid-ignore", "");
      this._mirrorCtx = this._mirror.getContext("2d");
      this.renderer._anchor.insertBefore(this._mirror, this.renderer.canvas);

      const updateClip = () => {
        if (this._mirrorActive) {
          this._baseRect = this._baseRect || this._measureBaseRect();
        }
        const r =
          this._fluidDrawRect || this._baseRect || this._measureBaseRect();
        const radius = `${this._fluidDrawRect ? 0 : this.radiusCss}px`;
        const origin = this.renderer.canvas.getBoundingClientRect();
        this._mirror.style.left = this.renderer.canvas.style.left;
        this._mirror.style.top = this.renderer.canvas.style.top;
        this._mirror.style.width = this.renderer.canvas.style.width;
        this._mirror.style.height = this.renderer.canvas.style.height;
        this._mirror.style.clipPath = `inset(${r.top - origin.top}px ${
          origin.right - r.right
        }px ${origin.bottom - r.bottom}px ${r.left - origin.left}px round ${radius})`;
        this._mirror.style.webkitClipPath = this._mirror.style.clipPath;
      };
      updateClip();
      this._mirrorClipUpdater = updateClip;
      listen(this, window, "resize", updateClip, { passive: true });

      this._mirrorActive = true;
    }

    _destroyMirrorCanvas() {
      if (!this._mirror) return;
      window.removeEventListener("resize", this._mirrorClipUpdater);
      this.el.removeAttribute("data-liquidgl-tilt");
      this._tiltMeasureStyle.removeProperty("transform");
      this._mirror.remove();
      this._mirror = this._mirrorCtx = null;
      this._baseRect = null;
      this._mirrorActive = false;
    }

    _TriggerInit() {
      if (this._destroyed || this._initCalled) return;
      this._initCalled = true;
      if (this.options.on && this.options.on.init) {
        this.options.on.init(this);
      }
    }
  }

  /* --------------------------------------------------
   *  Public API
   * ------------------------------------------------*/
  window.liquidGL = function (userOptions = {}) {
    const defaults = {
      target: ".liquidGL",
      snapshot: "body",
      resolution: 2.0,
      engine: "auto",
      zIndex: undefined,
      content: undefined,
      refraction: 0.01,
      aberration: 0,
      bevelDepth: 0.08,
      bevelWidth: 0.15,
      frost: 0,
      shadow: true,
      specular: true,
      reveal: "fade",
      tilt: false,
      tiltFactor: 5,
      tiltEase: 400,
      draggable: false,
      interaction: "none",
      interactionStrength: 0.5,
      interactionRadius: 0.35,
      interactionViscosity: 0.65,
      magnify: 1,
      tint: null,
      helper: false,
      on: {},
    };
    const options = { ...defaults, ...userOptions };
    options.tint = parseTintColor(options.tint);
    if (
      options.zIndex != null &&
      (!Number.isInteger(options.zIndex) ||
        Math.abs(options.zIndex) > 2147483646)
    ) {
      throw new TypeError(
        "liquidGL: zIndex must be an integer between -2147483646 and 2147483646.",
      );
    }

    const engineRaw =
      options.engine !== "auto"
        ? options.engine
        : new URLSearchParams(window.location.search).get("liquidGL-engine") ||
          "auto";
    const engineKey = String(engineRaw).toLowerCase();
    if (ENGINE_CHAINS[engineKey]) {
      options.engine = engineKey;
    } else {
      console.warn(`liquidGL: Unknown engine "${engineRaw}" – using "auto".`);
      options.engine = "auto";
    }

    const chain = ENGINE_CHAINS[options.engine];
    const hasWebGPU =
      chain[0] === "webgpu" &&
      typeof navigator !== "undefined" &&
      "gpu" in navigator;
    let hasWebGL = false;
    const glChain = chain.filter((c) => c !== "webgpu");
    if (glChain.length) {
      const testCanvas = document.createElement("canvas");
      for (const name of glChain) {
        if (testCanvas.getContext(name)) {
          hasWebGL = true;
          break;
        }
      }
    }
    const noGPU = !hasWebGPU && !hasWebGL;

    if (window.__liquidGLNoWebGL__ === true || noGPU) {
      console.warn(
        "liquidGL: WebGPU/WebGL not available – falling back to CSS backdrop-filter.",
      );
      const fbTint = options.tint || [1, 1, 1, 0.07];
      const fbBackground = `rgba(${Math.round(fbTint[0] * 255)}, ${Math.round(fbTint[1] * 255)}, ${Math.round(fbTint[2] * 255)}, ${options.tint ? Math.max(fbTint[3], 0.04) : fbTint[3]})`;
      const instances = Array.from(
        document.querySelectorAll(options.target),
        (node) => {
          const lens = new liquidGLLens(null, node, { ...options });
          lens._restoreStyles = restoreStyles(node, [
            "background",
            "backdrop-filter",
            "-webkit-backdrop-filter",
          ]);
          Object.assign(node.style, {
            background: fbBackground,
            backdropFilter: "blur(12px)",
            webkitBackdropFilter: "blur(12px)",
          });
          return lens;
        },
      );
      return instances.length === 1 ? instances[0] : instances;
    }

    const snapshotTarget =
      document.querySelector(options.snapshot) || document.body;
    const nodeList = document.querySelectorAll(options.target);
    if (!nodeList || nodeList.length === 0) {
      console.warn(
        `liquidGL: Target element(s) '${options.target}' not found.`,
      );
      return;
    }

    const instances = Array.from(nodeList).map((el) => {
      const zIndex = options.zIndex ?? effectiveZ(el);
      let anchor = el;
      while (anchor !== document.body && anchor.parentElement) {
        if (/^(fixed|sticky)$/.test(getComputedStyle(anchor).position)) break;
        anchor = anchor.parentElement;
      }
      let renderer = Array.from(renderers).find(
        (candidate) =>
          candidate.snapshotTarget === snapshotTarget &&
          candidate._anchor === anchor &&
          candidate._zIndex === zIndex,
      );
      if (!renderer) {
        renderer = new liquidGLRenderer(
          options.snapshot,
          options.resolution,
          options.engine,
          anchor,
        );
        renderer._zIndex = zIndex;
        renderer.useExternalTicker =
          window.__liquidGLRenderer__?.useExternalTicker || false;
        renderers.add(renderer);
        window.__liquidGLRenderer__ ||= renderer;
      }
      const lens = renderer.addLens(el, options);
      for (const instance of renderers) {
        for (const layer of instance._contentLayers) layer.invalidate();
      }
      return lens;
    });
    startRendering();

    if (options.helper) {
      if (typeof window.__liquidGLHelper__ === "function") {
        window.__liquidGLHelper__(instances, options);
      } else {
        console.error(
          "liquidGL Helper Not Found - ensure liquidGL-helper.js is available in your project",
        );
      }
    }

    return instances.length === 1 ? instances[0] : instances;
  };

  /* --------------------------------------------------
   *  Public helper: register elements that need live updates
   * ------------------------------------------------*/
  window.liquidGL.registerDynamic = function (elements) {
    for (const renderer of renderers.values()) {
      renderer.addDynamicElement(elements);
      renderer.captureSnapshot();
    }
  };

  /* --------------------------------------------------
   *  Public helper: Universal smooth scroll / animation sync
   * ------------------------------------------------*/
  window.liquidGL.syncWith = function (config = {}) {
    const renderer = window.__liquidGLRenderer__;
    if (!renderer) {
      console.warn(
        "liquidGL: Please initialize liquidGL *before* calling syncWith().",
      );
      return;
    }

    stopSync?.();
    cancelAnimationFrame(renderRaf);
    renderRaf = null;
    const cleanups = [];
    let active = true;
    let syncRaf = null;
    stopSync = () => {
      if (!active) return;
      active = false;
      cancelAnimationFrame(syncRaf);
      cleanups.splice(0).forEach((cleanup) => cleanup());
    };

    const G = config.gsap === false ? null : config.gsap || window.gsap;
    const L = window.Lenis;
    const LS = window.LocomotiveScroll;
    const ST = G ? config.ScrollTrigger || G.ScrollTrigger : null;

    let lenis = config.lenis;
    let loco = config.locomotiveScroll;
    const useGSAP = config.gsap !== false && G && ST;

    if (config.lenis !== false && L && !lenis) {
      lenis = new L();
      cleanups.push(() => lenis.destroy?.());
    }

    if (
      config.locomotiveScroll !== false &&
      LS &&
      !loco &&
      document.querySelector("[data-scroll-container]")
    ) {
      loco = new LS({
        el: document.querySelector("[data-scroll-container]"),
        smooth: true,
      });
      cleanups.push(() => loco.destroy?.());
    }

    if (useGSAP && ST) {
      if (loco) {
        const update = () => ST.update();
        loco.on("scroll", update);
        cleanups.unshift(() => loco.off?.("scroll", update));
        ST.scrollerProxy(loco.el, {
          scrollTop(value) {
            return arguments.length
              ? loco.scrollTo(value, { duration: 0, disableLerp: true })
              : loco.scroll.instance.scroll.y;
          },
          getBoundingClientRect() {
            return {
              top: 0,
              left: 0,
              width: window.innerWidth,
              height: window.innerHeight,
            };
          },
          pinType: loco.el.style.transform ? "transform" : "fixed",
        });
        const refresh = () => loco.update();
        ST.addEventListener("refresh", refresh);
        cleanups.unshift(() => {
          ST.removeEventListener("refresh", refresh);
          ST.scrollerProxy(loco.el);
        });
        ST.refresh();
      } else if (lenis) {
        const update = () => ST.update();
        lenis.on("scroll", update);
        cleanups.unshift(() => lenis.off?.("scroll", update));
      }
    }

    for (const instance of renderers.values())
      instance.useExternalTicker = true;

    if (useGSAP) {
      const tick = (time) => {
        if (!active) return;
        if (lenis) lenis.raf(time * 1000);
        renderAll();
      };
      G.ticker.add(tick);
      cleanups.unshift(() => G.ticker.remove(tick));
      G.ticker.lagSmoothing(0);
    } else {
      const loop = (time) => {
        if (!active) return;
        if (lenis) lenis.raf(time);
        if (loco) loco.update();
        renderAll();
        if (active) syncRaf = requestAnimationFrame(loop);
      };
      syncRaf = requestAnimationFrame(loop);
    }

    return { lenis, locomotiveScroll: loco };
  };
  return window.liquidGL;
})();

window.liquidGL = liquidGL;

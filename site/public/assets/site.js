/* MPS website: progressive enhancement for navigation, hero video, forms, QR & slide export. */
(function () {
  'use strict';
  var doc = document;

  // Enforce canonical https when opened over plain http on the main host
  var home = /^https:\/\/([a-z0-9.-]+)$/.exec(doc.documentElement.getAttribute('data-home') || '');
  if (home && location.protocol === 'http:' && (location.hostname === home[1] || location.hostname === 'www.' + home[1])) {
    location.replace('https://' + home[1] + location.pathname + location.search + location.hash);
    return;
  }

  doc.documentElement.classList.add('js');

  // Mobile navigation menu
  var button = doc.querySelector('.menu-button');
  var nav = doc.getElementById('nav');
  if (button && nav) {
    var setOpen = function (open) {
      nav.classList.toggle('open', open);
      button.setAttribute('aria-expanded', open ? 'true' : 'false');
    };
    button.addEventListener('click', function () { setOpen(!nav.classList.contains('open')); });
    nav.addEventListener('click', function (event) { if (event.target.closest('a')) { setOpen(false); } });
    doc.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && nav.classList.contains('open')) { setOpen(false); button.focus(); }
    });
  }

  // Hero inline video logo & studio screen toggle
  var heroStage = doc.querySelector('[data-hero-stage]');
  if (heroStage) {
    var video = heroStage.querySelector('video.hero-video');
    var muteBtn = heroStage.querySelector('[data-video-mute]');
    var replayBtn = heroStage.querySelector('[data-video-replay]');
    var tabs = doc.querySelectorAll('[data-hero-tab]');
    var panels = heroStage.querySelectorAll('[data-hero-panel]');
    var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (video && reduced) {
      video.removeAttribute('autoplay');
      video.pause();
    }
    if (video && muteBtn) {
      var syncMute = function () {
        var unmuted = !video.muted;
        muteBtn.setAttribute('aria-pressed', unmuted ? 'true' : 'false');
        muteBtn.textContent = unmuted ? (muteBtn.dataset.labelOn || 'Sound') : (muteBtn.dataset.labelOff || 'Muted');
      };
      muteBtn.addEventListener('click', function () {
        video.muted = !video.muted;
        if (!video.muted) {
          if (video.paused || video.currentTime > 8.5) {
            video.currentTime = 0;
          }
          video.play().catch(function () {});
        }
        syncMute();
      });
      syncMute();
    }
    if (video && replayBtn) {
      replayBtn.addEventListener('click', function () {
        video.currentTime = 0;
        video.play().catch(function () {});
      });
    }
    if (tabs.length && panels.length) {
      tabs.forEach(function (tab) {
        tab.addEventListener('click', function () {
          var target = tab.dataset.heroTab;
          tabs.forEach(function (t) {
            var active = t === tab;
            t.classList.toggle('active', active);
            t.setAttribute('aria-pressed', active ? 'true' : 'false');
          });
          panels.forEach(function (panel) {
            var show = panel.dataset.heroPanel === target;
            panel.hidden = !show;
          });
          if (video) {
            if (target === 'logo' && !reduced) {
              video.play().catch(function () {});
            } else if (target !== 'logo') {
              video.pause();
            }
          }
        });
      });
    }
  }

  // Screenshot gallery arrows
  var gallery = doc.querySelector('.gallery');
  var arrows = doc.querySelectorAll('.arrow[data-scroll]');
  if (gallery && arrows.length) {
    var update = function () {
      var max = gallery.scrollWidth - gallery.clientWidth - 2;
      arrows.forEach(function (arrow) {
        arrow.disabled = arrow.dataset.scroll === '-1' ? gallery.scrollLeft <= 2 : gallery.scrollLeft >= max;
      });
    };
    arrows.forEach(function (arrow) {
      arrow.addEventListener('click', function () {
        var figure = gallery.querySelector('figure');
        var step = figure ? figure.getBoundingClientRect().width + 20 : gallery.clientWidth;
        gallery.scrollBy({ left: step * Number(arrow.dataset.scroll), behavior: 'smooth' });
      });
    });
    gallery.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    update();
  }

  // Survey & feedback form validation
  var form = doc.querySelector('form.form[data-check]');
  if (form) {
    var dependent = form.querySelectorAll('[data-when]');
    var refresh = function () {
      dependent.forEach(function (box) {
        var chosen = form.querySelector('input[name="' + box.dataset.when + '"]:checked');
        var show = !!chosen && chosen.value === box.dataset.is;
        box.hidden = !show;
        box.querySelectorAll('input, textarea').forEach(function (field) { field.disabled = !show; });
      });
    };
    var limit = function (group) {
      var max = Number(group.dataset.max);
      var boxes = group.querySelectorAll('input[type="checkbox"]');
      var count = group.querySelectorAll('input[type="checkbox"]:checked').length;
      boxes.forEach(function (box) { box.disabled = !box.checked && count >= max; });
    };
    form.addEventListener('change', function (event) {
      refresh();
      var group = event.target.closest('.options[data-max]');
      if (group) { limit(group); }
      var block = event.target.closest('.bad');
      if (block) { block.classList.remove('bad'); }
    });
    form.addEventListener('input', function (event) {
      var block = event.target.closest('.bad');
      if (block) { block.classList.remove('bad'); }
    });
    form.querySelectorAll('.options[data-max]').forEach(limit);
    refresh();

    form.addEventListener('submit', function (event) {
      var problems = [];
      var message = '';
      form.querySelectorAll('[data-required]').forEach(function (block) {
        if (block.hidden) { return; }
        var text = block.querySelector('textarea');
        var own = block.querySelector('.other input');
        var answered = text ? text.value.trim().length >= 3 : !!block.querySelector('input:checked') || (!!own && own.value.trim() !== '');
        block.classList.toggle('bad', !answered);
        if (!answered) { problems.push(block); }
      });
      var alertBox = doc.getElementById('form-error');
      if (problems.length) {
        message = alertBox ? alertBox.dataset.required : '';
      } else {
        var consent = form.querySelector('[data-consent]');
        if (consent) {
          var contact = consent.querySelector('input[type="text"]');
          var agreed = consent.querySelector('input[type="checkbox"]');
          if (contact && agreed && contact.value.trim() !== '' && !agreed.checked) {
            consent.classList.add('bad');
            problems.push(consent);
            message = consent.dataset.consent;
          }
        }
      }
      if (!problems.length) { return; }
      event.preventDefault();
      if (alertBox) { alertBox.textContent = message; alertBox.hidden = false; }
      problems[0].scrollIntoView({ block: 'center', behavior: 'smooth' });
      var first = problems[0].querySelector('input, textarea');
      if (first) { first.focus({ preventScroll: true }); }
    });
  }

  // Delete confirmation prompts
  doc.querySelectorAll('[data-confirm]').forEach(function (control) {
    control.addEventListener('click', function (event) {
      if (!window.confirm(control.dataset.confirm)) { event.preventDefault(); }
    });
  });

  // Presentation deck controls, SVG/PNG slide export, and QR card download
  var deck = doc.querySelector('[data-slide-deck]');
  if (deck) {
    var themePalettes = {
      dark: {
        bg: '#131114',
        border: '#2f2833',
        text: '#fdf4f8',
        dim: '#b5a6ad',
        line: '#2a252c',
        track: '#241e27',
        surface2: '#1c181e',
        muted: '#7b6b84',
        brandBg: '#251a26'
      },
      light: {
        bg: '#ffffff',
        border: '#dfd7e3',
        text: '#18131a',
        dim: '#5d5064',
        line: '#e4dce8',
        track: '#efe9f2',
        surface2: '#f4eff6',
        muted: '#8a7c94',
        brandBg: '#fce8f1'
      }
    };

    var currentTheme = function () {
      return deck.dataset.theme === 'light' ? 'light' : 'dark';
    };

    var buildStandaloneSvg = function (svgEl, themeName) {
      var p = themePalettes[themeName] || themePalettes.dark;
      var clone = svgEl.cloneNode(true);
      clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
      var vb = (clone.getAttribute('viewBox') || '0 0 680 300').trim().split(/\s+/).map(Number);
      var vw = vb[2] || 680;
      var vh = vb[3] || 300;
      clone.setAttribute('width', String(vw));
      clone.setAttribute('height', String(vh));

      var cssText = [
        'text { font-family: system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }',
        '.grid { stroke: ' + p.line + '; stroke-width: 1; }',
        '.tick { fill: ' + p.dim + '; font-size: 12px; }',
        '.value { fill: ' + p.text + '; font-size: 12px; font-weight: 700; }',
        '.hbar-lbl { fill: ' + p.text + '; font-size: 12.5px; font-weight: 500; }',
        '.hbar-val { fill: ' + p.text + '; font-size: 12.5px; font-weight: 700; }',
        '.track { fill: ' + p.track + '; }',
        '.donut-center-num { fill: ' + p.text + '; font-size: 26px; font-weight: 800; }',
        '.seg-lbl { fill: #ffffff; font-size: 11px; font-weight: 700; }',
        '.ring { fill: none; stroke: ' + p.surface2 + '; }',
        '.seg { fill: none; }',
        '.line { fill: none; stroke-width: 2.5; stroke-linejoin: round; stroke-linecap: round; }',
        '.c1 { fill: #ff4f96; } .c2 { fill: #45d0c3; } .c3 { fill: #a78bfa; } .c4 { fill: #f5b942; } .c5 { fill: #6ea8ff; } .c6 { fill: #8b7f88; }',
        '.c-muted { fill: ' + p.muted + '; }',
        '.nps-det { fill: #f05d6c; } .nps-pas { fill: #f0ae35; } .nps-pro { fill: #ff4f96; }',
        '.l1 { stroke: #ff4f96; } .l2 { stroke: #45d0c3; } .l3 { stroke: #a78bfa; }',
        '.s1 { fill: none; stroke: #ff4f96; } .s2 { fill: none; stroke: #45d0c3; } .s3 { fill: none; stroke: #a78bfa; } .s4 { fill: none; stroke: #f5b942; } .s5 { fill: none; stroke: #6ea8ff; } .s6 { fill: none; stroke: #8b7f88; }'
      ].join('\n');

      var styleEl = doc.createElementNS('http://www.w3.org/2000/svg', 'style');
      styleEl.textContent = cssText;
      clone.insertBefore(styleEl, clone.firstChild);

      return {
        svgText: '<?xml version="1.0" encoding="UTF-8"?>\n' + new XMLSerializer().serializeToString(clone),
        width: vw,
        height: vh
      };
    };

    var svgToImage = function (svgText) {
      return new Promise(function (resolve, reject) {
        var img = new Image();
        img.onload = function () { resolve(img); };
        img.onerror = function (err) { reject(err); };
        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svgText);
      });
    };

    var wrapCanvasLines = function (ctx, text, maxWidth) {
      var words = String(text || '').split(/\s+/);
      var lines = [];
      var cur = '';
      words.forEach(function (w) {
        var next = cur ? cur + ' ' + w : w;
        if (ctx.measureText(next).width <= maxWidth) {
          cur = next;
        } else {
          if (cur) { lines.push(cur); }
          cur = w;
        }
      });
      if (cur) { lines.push(cur); }
      return lines;
    };

    var roundRectPath = function (ctx, x, y, w, h, r) {
      if (ctx.roundRect) {
        ctx.beginPath();
        ctx.roundRect(x, y, w, h, r);
        return;
      }
      ctx.beginPath();
      ctx.moveTo(x + r, y);
      ctx.arcTo(x + w, y, x + w, y + h, r);
      ctx.arcTo(x + w, y + h, x, y + h, r);
      ctx.arcTo(x, y + h, x, y, r);
      ctx.arcTo(x, y, x + w, y, r);
      ctx.closePath();
    };

    var triggerDownload = function (href, filename) {
      var a = doc.createElement('a');
      a.href = href;
      a.download = filename;
      doc.body.appendChild(a);
      a.click();
      doc.body.removeChild(a);
    };

    var renderSlideCanvas = function (card) {
      var themeName = currentTheme();
      var p = themePalettes[themeName];
      var svgEl = card.querySelector('svg');
      if (!svgEl) { return Promise.reject(new Error('No SVG in card')); }

      var pack = buildStandaloneSvg(svgEl, themeName);
      var title = card.dataset.chartTitle || '';
      var note = card.dataset.chartNote || '';
      var brand = deck.dataset.brand || 'MPS';

      var legendNodes = card.querySelectorAll('.legend.inline li');
      var inlineLegend = [];
      legendNodes.forEach(function (li) {
        var keyEl = li.querySelector('.key');
        var spanEl = li.querySelector('span');
        var bEl = li.querySelector('b');
        if (spanEl) {
          inlineLegend.push({
            color: keyEl ? getComputedStyle(keyEl).backgroundColor : '#ff4f96',
            label: spanEl.textContent + (bEl ? ' ' + bEl.textContent : '')
          });
        }
      });

      var qrCard = deck.querySelector('[data-qr-card]');
      var qrSvgEl = (deck.dataset.slideQr === '1' && qrCard) ? qrCard.querySelector('.qr-frame svg') : null;
      var qrCaption = qrCard ? (qrCard.dataset.qrCaption || '') : '';

      var W = 1600;
      var padX = 72;
      var innerW = W - padX * 2;
      var tmpCanvas = doc.createElement('canvas');
      var tctx = tmpCanvas.getContext('2d');
      tctx.font = '700 38px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
      var titleLines = wrapCanvasLines(tctx, title, innerW - 260);
      tctx.font = '400 22px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
      var noteLines = note ? wrapCanvasLines(tctx, note, innerW - (qrSvgEl ? 180 : 0)) : [];

      var headerH = 60 + titleLines.length * 46 + (noteLines.length ? noteLines.length * 30 + 10 : 0);
      var chartDrawW = innerW;
      var chartDrawH = Math.round(chartDrawW * (pack.height / pack.width));
      var legendH = inlineLegend.length ? 48 : 0;
      var footerH = qrSvgEl ? 156 : 54;
      var H = headerH + 28 + chartDrawH + legendH + footerH + 40;

      var promises = [svgToImage(pack.svgText)];
      if (qrSvgEl) {
        var qrClone = qrSvgEl.cloneNode(true);
        qrClone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        promises.push(svgToImage('<?xml version="1.0" encoding="UTF-8"?>\n' + new XMLSerializer().serializeToString(qrClone)));
      }

      return Promise.all(promises).then(function (imgs) {
        var canvas = doc.createElement('canvas');
        canvas.width = W;
        canvas.height = H;
        var ctx = canvas.getContext('2d');

        // Card slide background + accent bar
        ctx.fillStyle = p.bg;
        ctx.fillRect(0, 0, W, H);
        ctx.fillStyle = '#ff4f96';
        ctx.fillRect(0, 0, W, 8);

        // Brand badge top-right
        ctx.font = '700 19px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
        var bw = ctx.measureText(brand).width + 32;
        ctx.fillStyle = p.brandBg;
        roundRectPath(ctx, W - padX - bw, 46, bw, 38, 19);
        ctx.fill();
        ctx.fillStyle = '#ff4f96';
        ctx.textAlign = 'center';
        ctx.fillText(brand, W - padX - bw / 2, 72);

        // Title & Subtitle
        ctx.textAlign = 'left';
        ctx.fillStyle = p.text;
        ctx.font = '800 38px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
        var ty = 80;
        titleLines.forEach(function (line) {
          ctx.fillText(line, padX, ty);
          ty += 46;
        });
        if (noteLines.length) {
          ctx.fillStyle = p.dim;
          ctx.font = '400 22px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
          ty += 4;
          noteLines.forEach(function (line) {
            ctx.fillText(line, padX, ty);
            ty += 30;
          });
        }

        // Vector chart rasterized at full slide width
        ctx.drawImage(imgs[0], padX, headerH + 20, chartDrawW, chartDrawH);

        // Optional inline legend (for time-series line charts)
        if (inlineLegend.length) {
          var lx = padX;
          var ly = headerH + 20 + chartDrawH + 34;
          ctx.font = '600 21px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
          inlineLegend.forEach(function (item) {
            ctx.fillStyle = item.color;
            roundRectPath(ctx, lx, ly - 15, 16, 16, 4);
            ctx.fill();
            ctx.fillStyle = p.text;
            ctx.fillText(item.label, lx + 24, ly);
            lx += ctx.measureText(item.label).width + 64;
          });
        }

        // Bottom footer + optional QR stamp
        if (imgs[1]) {
          var qSize = 112;
          var qBox = qSize + 18;
          var qx = W - padX - qBox;
          var qy = H - qBox - 34;
          ctx.fillStyle = '#ffffff';
          roundRectPath(ctx, qx, qy, qBox, qBox, 14);
          ctx.fill();
          ctx.drawImage(imgs[1], qx + 9, qy + 9, qSize, qSize);
          if (qrCaption) {
            ctx.fillStyle = p.dim;
            ctx.font = '600 18px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText(qrCaption, qx - 18, qy + qBox - 12);
          }
        }
        return canvas;
      });
    };

    deck.querySelectorAll('[data-slide-theme]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var mode = btn.dataset.slideTheme === 'light' ? 'light' : 'dark';
        deck.dataset.theme = mode;
        deck.querySelectorAll('[data-slide-theme]').forEach(function (other) {
          var active = other === btn;
          other.classList.toggle('active', active);
          other.setAttribute('aria-pressed', active ? 'true' : 'false');
        });
      });
    });

    var qrStampToggle = deck.querySelector('[data-qr-stamp]');
    if (qrStampToggle) {
      qrStampToggle.addEventListener('change', function () {
        deck.dataset.slideQr = qrStampToggle.checked ? '1' : '0';
      });
    }

    var printBtn = deck.querySelector('[data-print-deck]');
    if (printBtn) {
      printBtn.addEventListener('click', function () { window.print(); });
    }

    deck.addEventListener('click', function (event) {
      var pngBtn = event.target.closest('[data-export-png]');
      var svgBtn = event.target.closest('[data-export-svg]');
      var copyBtn = event.target.closest('[data-export-copy]');
      var card = event.target.closest('.chart-card');

      if (svgBtn && card) {
        var svgEl = card.querySelector('svg');
        if (!svgEl) { return; }
        var pack = buildStandaloneSvg(svgEl, currentTheme());
        var blob = new Blob([pack.svgText], { type: 'image/svg+xml;charset=utf-8' });
        var url = URL.createObjectURL(blob);
        triggerDownload(url, (card.dataset.chartSlug || 'mps-chart') + '-' + currentTheme() + '.svg');
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
      } else if (pngBtn && card) {
        renderSlideCanvas(card).then(function (canvas) {
          triggerDownload(canvas.toDataURL('image/png'), (card.dataset.chartSlug || 'mps-chart') + '-' + currentTheme() + '.png');
        }).catch(function () {});
      } else if (copyBtn && card) {
        var orig = copyBtn.textContent;
        renderSlideCanvas(card).then(function (canvas) {
          return new Promise(function (resolve, reject) {
            canvas.toBlob(function (blob) {
              if (!blob || !navigator.clipboard || !window.ClipboardItem) {
                reject(new Error('Clipboard unavailable'));
                return;
              }
              navigator.clipboard.write([new window.ClipboardItem({ 'image/png': blob })]).then(resolve, reject);
            }, 'image/png');
          });
        }).then(function () {
          copyBtn.textContent = copyBtn.dataset.copied || 'Copied';
          setTimeout(function () { copyBtn.textContent = orig; }, 1800);
        }).catch(function () {
          // Fallback to PNG download if clipboard API is unavailable (e.g. non-HTTPS localhost)
          renderSlideCanvas(card).then(function (canvas) {
            triggerDownload(canvas.toDataURL('image/png'), (card.dataset.chartSlug || 'mps-chart') + '-' + currentTheme() + '.png');
          }).catch(function () {});
        });
      }
    });

    // Standalone QR Code PNG & SVG downloads
    var qrBanner = deck.querySelector('[data-qr-card]');
    if (qrBanner) {
      var qrSvgBtn = qrBanner.querySelector('[data-qr-svg]');
      var qrPngBtn = qrBanner.querySelector('[data-qr-png]');
      var copyLinkBtn = qrBanner.querySelector('[data-copy-text]');
      var qrSvgNode = qrBanner.querySelector('.qr-frame svg');

      if (qrSvgBtn && qrSvgNode) {
        qrSvgBtn.addEventListener('click', function () {
          var clone = qrSvgNode.cloneNode(true);
          clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
          clone.setAttribute('width', '600');
          clone.setAttribute('height', '600');
          var xml = '<?xml version="1.0" encoding="UTF-8"?>\n' + new XMLSerializer().serializeToString(clone);
          var blob = new Blob([xml], { type: 'image/svg+xml;charset=utf-8' });
          var url = URL.createObjectURL(blob);
          triggerDownload(url, 'mps-stats-qr.svg');
          setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
        });
      }

      if (qrPngBtn && qrSvgNode) {
        qrPngBtn.addEventListener('click', function () {
          var clone = qrSvgNode.cloneNode(true);
          clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
          clone.setAttribute('width', '960');
          clone.setAttribute('height', '960');
          var xml = '<?xml version="1.0" encoding="UTF-8"?>\n' + new XMLSerializer().serializeToString(clone);
          svgToImage(xml).then(function (img) {
            var canvas = doc.createElement('canvas');
            canvas.width = 1200;
            canvas.height = 1420;
            var ctx = canvas.getContext('2d');
            ctx.fillStyle = '#ffffff';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.fillStyle = '#ff4f96';
            ctx.fillRect(0, 0, canvas.width, 14);
            ctx.drawImage(img, 120, 110, 960, 960);
            ctx.fillStyle = '#18131a';
            ctx.textAlign = 'center';
            ctx.font = '800 44px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
            ctx.fillText('MPS', 600, 1175);
            ctx.fillStyle = '#5d5064';
            ctx.font = '600 32px system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif';
            ctx.fillText(qrBanner.dataset.qrCaption || 'mp-studio.online/stats/', 600, 1245);
            triggerDownload(canvas.toDataURL('image/png'), 'mps-stats-qr.png');
          }).catch(function () {});
        });
      }

      if (copyLinkBtn) {
        copyLinkBtn.addEventListener('click', function () {
          var txt = copyLinkBtn.dataset.copyText || '';
          var orig = copyLinkBtn.textContent;
          if (navigator.clipboard && txt) {
            navigator.clipboard.writeText(txt).then(function () {
              copyLinkBtn.textContent = copyLinkBtn.dataset.copied || 'Copied';
              setTimeout(function () { copyLinkBtn.textContent = orig; }, 1800);
            }).catch(function () {});
          }
        });
      }
    }
  }
})();

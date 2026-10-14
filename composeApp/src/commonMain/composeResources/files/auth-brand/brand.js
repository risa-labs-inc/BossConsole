/**
 * Interaction for the sign-in brand panel: digit rain, pointer parallax, custom cursor, orbit
 * direction, capability previews and a one-shot ambient sound.
 *
 * Everything here is decoration on the one screen a user cannot get past, so every piece is
 * independently optional: each runs in its own guarded block, a failure in one leaves the others
 * going, and with this file absent entirely the panel is still a complete picture drawn by CSS - the
 * orbits, glow and entrance are all in the stylesheet.
 *
 * `prefers-reduced-motion` is a hard gate, not a softening - no rain, no parallax, no sound, and the
 * orbits keep the fixed angles the CSS gives them. It is the same setting the stylesheet uses to drop
 * every looping animation.
 */
(function () {
  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------------------------------------------------------------------------------------------
     Idle.

     NOTHING MAY ANIMATE WHILE NOBODY IS LOOKING. On macOS the panel is rendered off-screen, so every
     changed frame is copied out of Chromium through the CPU; a page that loops forever kept the fans
     audibly running for as long as the sign-in screen was up. So motion runs while the pointer is over
     the panel (or for the opening few seconds), then everything settles: CSS animations pause in place
     via `brand-idle`, and each script loop below stops scheduling frames until [onWake] restarts it.
     --------------------------------------------------------------------------------------------- */
  var IDLE_AFTER_MS = 8000;
  var idle = false;
  var idleTimer = null;
  var wakeHandlers = [];

  function onWake(handler) {
    wakeHandlers.push(handler);
  }

  function sleep() {
    if (idleTimer) window.clearTimeout(idleTimer);
    idleTimer = null;
    idle = true;
    document.body.classList.add('brand-idle');
  }

  function wake() {
    if (idleTimer) window.clearTimeout(idleTimer);
    idleTimer = window.setTimeout(sleep, IDLE_AFTER_MS);
    if (!idle) return;
    idle = false;
    document.body.classList.remove('brand-idle');
    for (var i = 0; i < wakeHandlers.length; i++) wakeHandlers[i]();
  }

  ['pointermove', 'pointerdown', 'keydown', 'focusin'].forEach(function (type) {
    window.addEventListener(type, wake, { passive: true });
  });
  // The form on the Compose side taking focus is the commonest way attention leaves the panel.
  window.addEventListener('blur', sleep);
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) sleep();
  });
  wake();

  /* ---------------------------------------------------------------------------------------------
     Matrix-style digit rain, the background field.

     BLUE, NOT GREEN. The glyphs are drawn in the site's own `#88a9ff` / white, because Matrix green
     against a Blueprint-blue panel would read as a different product's screensaver.

     EACH GLYPH HAS AN EXPLICIT LIFETIME. It is drawn from a list with its own age, and the canvas is
     fully cleared every frame - deliberately NOT the classic "wash the canvas with a translucent
     backdrop and let the trails fade themselves" trick, which this used to do and which left digits on
     screen for good. Compositing is 8-bit: at the very low wash alpha that dimming the field required
     (0.013), the reduction of an already-faint pixel rounds to zero, so those pixels never reach
     transparent. The result was a permanent ghost layer of digits that only ever accumulated.

     Owning the ages costs a redraw of every live glyph each frame instead of one rectangle fill, so the
     field is deliberately bounded: [TAIL_MAX] glyphs per column, columns [COLUMN_WIDTH] apart, and
     redrawn at about 30fps rather than every frame. At this dimness the halved rate is invisible, and it
     halves the work behind a screen whose real job is signing in.

     Dimness and lifetime are now INDEPENDENT, which they were not before: alpha is computed per glyph,
     so it can go as faint as it likes without affecting whether or when anything disappears.

     IT DOES NOT START UNTIL THE OPENING HAS FINISHED. The panel arrives zoomed and settles back; digits
     falling through that would fight the one thing the eye should be following. See the bottom of this
     block for how the two are synchronised.
     --------------------------------------------------------------------------------------------- */
  (function digitRain() {
    if (reduceMotion) return;
    var canvas = document.getElementById('brand-particles');
    if (!canvas || !canvas.getContext) return;
    var ctx = canvas.getContext('2d');
    var DPR = Math.min(window.devicePixelRatio || 1, 2);
    var COLUMN_WIDTH = 13;
    var GLYPH_SIZE = 12;
    var STEP = 14;
    var TAIL_MAX = 8;
    // In draw-frames, so ~2.5s at the 30fps redraw rate below.
    var LIFETIME = 75;
    var columns = [];
    var pointer = { x: -9999, y: -9999 };
    var width = 0;
    var height = 0;
    var oddFrame = false;

    function newColumn(startAnywhere) {
      return {
        y: startAnywhere ? Math.random() * height : -Math.random() * height * 0.4,
        framesPerStep: 4 + Math.floor(Math.random() * 5),
        tick: 0,
        active: Math.random() < 0.95,
        glyphs: []
      };
    }

    function seed() {
      columns = [];
      var count = Math.ceil(width / COLUMN_WIDTH);
      for (var i = 0; i < count; i++) columns.push(newColumn(true));
    }

    function resize() {
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = width * DPR;
      canvas.height = height * DPR;
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
      seed();
    }

    function digit() {
      return String(Math.floor(Math.random() * 10));
    }

    // Idle drains rather than freezing: no new glyphs, the live ones fade out over their own
    // lifetime, and the loop stops once the canvas is empty.
    var running = false;

    function frame() {
      // Half rate. Cheap, and indistinguishable at this brightness.
      oddFrame = !oddFrame;
      if (oddFrame) {
        requestAnimationFrame(frame);
        return;
      }

      // A real clear, so nothing can persist beyond its own lifetime.
      ctx.clearRect(0, 0, width, height);
      ctx.font = GLYPH_SIZE + 'px ui-monospace, SFMono-Regular, Menlo, monospace';
      ctx.textBaseline = 'top';
      var live = 0;

      for (var i = 0; i < columns.length; i++) {
        var col = columns[i];
        if (!col.active) continue;
        var x = i * COLUMN_WIDTH + 1;

        // A column lifts slightly as the pointer passes, so the field acknowledges the mouse without
        // anything as literal as a spotlight.
        var near = Math.abs(pointer.x - x);
        var boost = near < 130 ? (1 - near / 130) * 0.05 : 0;

        col.tick++;
        if (!idle && col.tick >= col.framesPerStep) {
          col.tick = 0;
          col.y += STEP;
          col.glyphs.push({ ch: digit(), y: col.y, age: 0 });
          if (col.glyphs.length > TAIL_MAX) col.glyphs.shift();
          if (col.y > height + STEP) {
            columns[i] = newColumn(false);
            continue;
          }
        }

        for (var g = col.glyphs.length - 1; g >= 0; g--) {
          var glyph = col.glyphs[g];
          glyph.age++;
          if (glyph.age > LIFETIME) {
            col.glyphs.splice(g, 1);
            continue;
          }
          // Linear fade to nothing. The newest glyph is the brightest simply by being the youngest, so
          // no separate "head" case is needed.
          var life = 1 - glyph.age / LIFETIME;
          ctx.fillStyle = 'rgba(198, 220, 255, ' + (0.055 * life + boost * life) + ')';
          ctx.fillText(glyph.ch, x, glyph.y);
        }
        live += col.glyphs.length;

        // Flicker: an existing digit changes rather than a new one being drawn. Mutating the list is
        // what makes this free - the glyph is redrawn from its own record either way.
        if (col.glyphs.length && Math.random() < 0.05) {
          col.glyphs[Math.floor(Math.random() * col.glyphs.length)].ch = digit();
        }
      }

      if (idle && live === 0) {
        running = false;
        return;
      }
      requestAnimationFrame(frame);
    }

    function run() {
      if (running) return;
      running = true;
      requestAnimationFrame(frame);
    }

    window.addEventListener('resize', resize);
    window.addEventListener('pointermove', function (e) {
      pointer.x = e.clientX;
      pointer.y = e.clientY;
    });
    window.addEventListener('pointerleave', function () {
      pointer.x = -9999;
      pointer.y = -9999;
    });

    /* The rain waits for the opening to finish.
       Driven by the reveal's own `animationend` rather than a matching timeout, so the two cannot drift
       apart when the animation is retimed - the duration lives in one place, the stylesheet. The name is
       checked because several animations end on this element and its descendants.

       The timeout is a safety net, not the mechanism: if the animation never runs or its end event is
       missed (an interrupted first paint, a browser that throttles background tabs), the field still
       starts rather than the panel sitting empty for good. */
    var startedRain = false;

    function startRain() {
      if (startedRain) return;
      startedRain = true;
      resize();
      run();
      onWake(run);
    }

    var hero = document.querySelector('.hero');
    if (hero) {
      hero.addEventListener('animationend', function (e) {
        if (e.animationName === 'brand-hero-reveal') startRain();
      });
      window.setTimeout(startRain, 2600);
    } else {
      startRain();
    }
  })();

  /* ---------------------------------------------------------------------------------------------
     The custom cursor.

     THE PANEL ITSELF DOES NOT MOVE WITH THE POINTER. Pointer parallax was built here and deliberately
     taken out: a scene that leans about behind the form is motion nobody asked for, every time they
     reach for the email field. The cursor follows the pointer; nothing else does.
     --------------------------------------------------------------------------------------------- */
  (function pointerLayers() {
    var cursor = document.querySelector('.brand-cursor');
    if (!cursor) return;
    var cx = 0;
    var cy = 0;
    var tx = 0;
    var ty = 0;

    // Scheduled only while the cursor is still catching up, so a resting pointer costs no frames.
    var following = false;

    function onMove(e) {
      tx = e.clientX;
      ty = e.clientY;
      if (cursor) {
        cursor.classList.add('is-visible');
        document.body.classList.add('brand-cursor-active');
      }
      if (!following) {
        following = true;
        requestAnimationFrame(follow);
      }
    }

    function follow() {
      // Eased rather than pinned to the pointer: the trail is what makes it read as a deliberate
      // cursor rather than a laggy one.
      cx += (tx - cx) * 0.18;
      cy += (ty - cy) * 0.18;
      if (Math.abs(tx - cx) < 0.25 && Math.abs(ty - cy) < 0.25) {
        cx = tx;
        cy = ty;
        following = false;
      }
      if (cursor) cursor.style.transform = 'translate3d(' + cx + 'px,' + cy + 'px,0)';
      if (following) requestAnimationFrame(follow);
    }

    function hide() {
      if (cursor) cursor.classList.remove('is-visible');
      document.body.classList.remove('brand-cursor-active');
    }

    /* Hiding needs BELT AND BRACES, because the panel is a native browser surface embedded in a Compose
       window rather than a page in a browser tab. When the pointer crosses onto the Compose side, this
       document frequently gets no leave event at all - the events simply stop - so the drawn cursor was
       left frozen against whichever edge it was last seen at.

       Four signals, because no single one fires reliably here:
         - `pointerleave` / `mouseleave` on the document, when they do arrive;
         - `mouseout` with a null `relatedTarget`, which is the DOM's way of saying "left the document";
         - window `blur`, which is what actually happens when the form on the other side takes focus;
         - and an edge watchdog for the case where nothing at all arrives: if the last known position was
           within a few pixels of a viewport edge and no movement follows, the pointer has left. It is
           deliberately conditioned on the EDGE so that a mouse resting still in the middle of the panel
           - which is not leaving - keeps its cursor. */
    var EDGE_SLACK = 6;
    var idleTimer = null;

    function armEdgeWatchdog() {
      if (idleTimer) window.clearTimeout(idleTimer);
      idleTimer = window.setTimeout(function () {
        var nearEdge =
          tx <= EDGE_SLACK ||
          ty <= EDGE_SLACK ||
          tx >= window.innerWidth - EDGE_SLACK ||
          ty >= window.innerHeight - EDGE_SLACK;
        if (nearEdge) hide();
      }, 220);
    }

    window.addEventListener('pointermove', function (e) {
      onMove(e);
      armEdgeWatchdog();
    });
    document.addEventListener('pointerleave', hide);
    document.addEventListener('mouseleave', hide);
    document.addEventListener('mouseout', function (e) {
      if (!e.relatedTarget) hide();
    });
    window.addEventListener('blur', hide);

    // The halo swells over anything clickable, so the cursor itself signals the affordance.
    document.addEventListener('pointerover', function (e) {
      if (!cursor) return;
      var interactive = e.target && e.target.closest && e.target.closest('[data-preview],[data-brand-close]');
      cursor.classList.toggle('is-hot', !!interactive);
    });
  })();

  /* ---------------------------------------------------------------------------------------------
     Orbit direction, randomised per launch.

     Direction is flipped with `animation-direction: reverse` rather than by writing new keyframes, and
     THE WRAPPER AND ITS LABEL MUST GET THE SAME VALUE. The label's rotation is the exact negative of
     its orbit's; reverse one without the other and the counter-rotation stops cancelling, so the chip
     would tumble round its own centre as it travelled.
     --------------------------------------------------------------------------------------------- */
  (function orbitDirections() {
    if (reduceMotion) return;
    var orbits = document.querySelectorAll('.brand-orbit');
    for (var i = 0; i < orbits.length; i++) {
      var direction = Math.random() < 0.5 ? 'reverse' : 'normal';
      orbits[i].style.animationDirection = direction;
      var label = orbits[i].querySelector('.brand-orbit-label');
      if (label) label.style.animationDirection = direction;
    }
  })();

  /* ---------------------------------------------------------------------------------------------
     Capability previews.

     Copy is the site's own throughout - the capability titles and descriptions from its product
     section, the tool events and the permission states from its console mock - so the panel and the
     site cannot end up describing the product differently.
     --------------------------------------------------------------------------------------------- */
  (function previews() {
    var dialog = document.getElementById('brand-dialog');
    if (!dialog) return;
    var topbar = document.getElementById('brand-dialog-topbar');
    var kicker = document.getElementById('brand-dialog-kicker');
    var title = document.getElementById('brand-dialog-title');
    var copy = document.getElementById('brand-dialog-copy');
    var events = document.getElementById('brand-dialog-events');
    var lastFocus = null;

    var CONTENT = {
      browser: {
        topbar: 'BOSS · BROWSER / SESSION',
        kicker: 'BROWSER',
        title: 'Use the real web',
        copy: 'Browse, inspect, automate, and verify work in a full browser environment.',
        rows: [
          ['done', 'Browser', 'Inspected first-run experience'],
          ['done', 'Codebase', 'Mapped onboarding states']
        ]
      },
      terminal: {
        topbar: 'BOSS · WORKSPACE / TERMINAL',
        kicker: 'TERMINAL',
        title: 'Operate the machine',
        copy: 'Run commands, edit projects, inspect output, and work across local or remote compute.',
        rows: [
          ['done', 'Shell', 'Ran the project test suite'],
          ['approval', 'Approval required', 'Allow the agent to create a local prototype?']
        ]
      },
      policy: {
        topbar: 'BOSS · WORKSPACE / CAPABILITIES',
        kicker: 'POLICY',
        title: 'Control is a feature',
        copy:
          'BOSS makes agent capabilities explicit. Operators can approve consequential actions and keep ' +
          'sensitive work close to the systems it depends on.',
        rows: [
          ['on', 'Browser', 'Allowed'],
          ['on', 'Files', 'Project only'],
          ['ask', 'Shell', 'Ask first'],
          ['off', 'Secrets', 'Not exposed']
        ]
      }
    };

    function row(kind, name, detail) {
      var wrap = document.createElement('div');
      if (kind === 'approval') wrap.className = 'approval';
      var mark = document.createElement('span');
      if (kind === 'done') {
        mark.className = 'done';
        mark.textContent = '\u2713';
      } else if (kind === 'approval') {
        mark.textContent = '!';
      } else {
        mark.className = 'brand-status brand-status-' + kind;
      }
      var strong = document.createElement('strong');
      strong.textContent = name;
      var small = document.createElement('small');
      small.textContent = detail;
      wrap.appendChild(mark);
      wrap.appendChild(strong);
      wrap.appendChild(small);
      return wrap;
    }

    function open(key) {
      var data = CONTENT[key];
      if (!data) return;
      lastFocus = document.activeElement;
      topbar.textContent = data.topbar;
      kicker.textContent = data.kicker;
      title.textContent = data.title;
      copy.textContent = data.copy;
      // textContent and createElement throughout rather than innerHTML: the strings are ours, but a
      // panel on the sign-in screen is the last place to build markup by concatenation.
      events.textContent = '';
      data.rows.forEach(function (r) {
        events.appendChild(row(r[0], r[1], r[2]));
      });
      dialog.hidden = false;
      var close = dialog.querySelector('.brand-dialog-close');
      if (close) close.focus();
    }

    function close() {
      dialog.hidden = true;
      release();
      // Focus goes back to the chip that opened it, so keyboard users are not dropped at the top.
      if (lastFocus && lastFocus.focus) lastFocus.focus();
    }

    /* Holding the orbits still.
       Aiming at a target that is still drifting is the one genuinely irritating thing about putting
       controls in orbit, so anything that counts as "about to click" freezes all of them: the pointer
       over a chip or a planet, keyboard focus on a chip, or an open preview. It is `animation-play-state`
       in CSS, so they stop exactly where they are and resume from there - no snapping back. */
    function hold() {
      document.body.classList.add('brand-hold');
    }

    function release() {
      // A preview still on screen outranks the pointer having moved away.
      if (dialog.hidden) document.body.classList.remove('brand-hold');
    }

    document.addEventListener('pointerover', function (e) {
      if (e.target.closest && e.target.closest('[data-preview]')) hold();
    });
    document.addEventListener('pointerout', function (e) {
      if (e.target.closest && e.target.closest('[data-preview]')) release();
    });
    document.addEventListener('focusin', function (e) {
      if (e.target.closest && e.target.closest('[data-preview]')) hold();
    });
    document.addEventListener('focusout', function (e) {
      if (e.target.closest && e.target.closest('[data-preview]')) release();
    });

    document.addEventListener('click', function (e) {
      // Matches the planets as well as the chips: both carry `data-preview`, so a click on either opens
      // the same panel with no extra wiring.
      var target = e.target.closest && e.target.closest('[data-preview]');
      if (target) {
        hold();
        open(target.getAttribute('data-preview'));
        return;
      }
      if (e.target.closest && e.target.closest('[data-brand-close]')) close();
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !dialog.hidden) close();
    });
  })();
})();

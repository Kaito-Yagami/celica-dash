/* ==========================================================================
   Mockup-only viewer: the site's wireframe car, held at a fixed three-quarter
   angle, with the wheels turning.

   model.js carries the car as PARTS (see tools/model.py): body, wing and four
   wheels, each a run of faces and edges in the shared arrays, and each wheel
   with its axle as a pivot. So a wheel is simply its part, parented to a
   group sitting on that pivot.

   window.MOCK.setSpeed(mph) turns the wheels at the real rate for a 205/50R16
   — at 30 mph that is about seven turns a second, so it strobes, like a real
   wheel on camera does.
   ========================================================================== */
(function () {
  'use strict';
  var C = (window.CELICA = window.CELICA || {});
  var MOCK = (window.MOCK = window.MOCK || {});

  var TYRE_M = 0.3065;                 /* 205/50R16 rolling radius, metres */

  function b64bytes(s) {
    var bin = atob(s), n = bin.length, out = new Uint8Array(n);
    for (var i = 0; i < n; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function init(host) {
    var wrap = host.parentElement;
    if (!C.model || typeof THREE === 'undefined') { wrap.classList.add('fallback'); return; }
    var M = C.model;

    /* ------------------------------------------------------- decode */
    var qpos = new Uint16Array(b64bytes(M.pos).buffer);
    var faces = new Uint16Array(b64bytes(M.idx).buffer);
    var edges = new Uint16Array(b64bytes(M.edg).buffer);
    var nv = qpos.length / 3;
    var pos = new Float32Array(qpos.length);
    for (var i = 0; i < nv; i++) {
      for (var a = 0; a < 3; a++) pos[i * 3 + a] = M.min[a] + (qpos[i * 3 + a] / 65535) * M.span[a];
    }
    var g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.rotateX(-Math.PI / 2);
    g.computeBoundingBox();
    var c0 = new THREE.Vector3(); g.boundingBox.getCenter(c0);
    var y0 = g.boundingBox.min.y;             /* sit it on the ground plane */
    g.translate(-c0.x, -y0, -c0.z);
    g.computeBoundingBox();
    var bb = g.boundingBox;

    /* --------------------------------------------------------- parts */
    /* Every part shares the one position buffer; a part is just an index
       range. A wheel gets its own group on its pivot, run through the same
       rotate/translate the geometry had so it lands on the axle. */
    var posAttr = g.getAttribute('position');
    var toThree = function (p) {
      return new THREE.Vector3(p[0], p[1], p[2])
        .applyAxisAngle(new THREE.Vector3(1, 0, 0), -Math.PI / 2)
        .sub(new THREE.Vector3(c0.x, y0, c0.z));
    };
    function geo(index, start, count) {
      var gg = new THREE.BufferGeometry();
      gg.setAttribute('position', posAttr);
      gg.setIndex(new THREE.BufferAttribute(index.subarray(start, start + count), 1));
      return gg;
    }
    var occMat = new THREE.MeshBasicMaterial({ color: 0x131216, polygonOffset: true, polygonOffsetFactor: 1.4, polygonOffsetUnits: 1.4 });
    var lineMat = new THREE.LineBasicMaterial({ color: 0xf1edea, transparent: true, opacity: 0.92 });

    var scene = new THREE.Scene();
    var wheels = [];
    (M.parts || [{ faceStart: 0, faceCount: faces.length / 3, edgeStart: 0, edgeCount: edges.length / 2 }]).forEach(function (p) {
      var mesh = new THREE.Mesh(geo(faces, p.faceStart * 3, p.faceCount * 3), occMat);
      var line = new THREE.LineSegments(geo(edges, p.edgeStart * 2, p.edgeCount * 2), lineMat);
      if (!p.pivot) { scene.add(mesh); scene.add(line); return; }
      var pv = toThree(p.pivot), grp = new THREE.Group();
      grp.position.copy(pv);
      mesh.position.copy(pv).negate(); line.position.copy(pv).negate();
      grp.add(mesh); grp.add(line);
      scene.add(grp);
      wheels.push(grp);
    });
    MOCK.debug = { scene: scene, wheels: wheels };

    var camera = new THREE.PerspectiveCamera(30, 16 / 9, 0.1, 1000);
    MOCK.debug.camera = camera;
    var renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setClearColor(0x000000, 0);
    host.appendChild(renderer.domElement);
    var dom = renderer.domElement;

    /* ------------------------------------------------- camera, fixed */
    var HOME_AZ = Math.PI - 0.80, HOME_EL = 0.24;   /* nose left */
    var az = HOME_AZ, el = HOME_EL, targetAz = az, targetEl = el;
    var target = new THREE.Vector3(0, bb.max.y * 0.45, 0);
    var dir = new THREE.Vector3(), right = new THREE.Vector3(), up = new THREE.Vector3(), rel = new THREE.Vector3();
    var WORLD_UP = new THREE.Vector3(0, 1, 0);
    var K = 0.9, corners = [];
    for (var cx = 0; cx < 2; cx++) for (var cy = 0; cy < 2; cy++) for (var cz = 0; cz < 2; cz++)
      corners.push(new THREE.Vector3((cx ? bb.max.x : bb.min.x) * K, cy ? bb.max.y * K : 0, (cz ? bb.max.z : bb.min.z) * K));

    /* fit the home angle only — the car is parked, not turning */
    var dist = 0;
    function fit() {
      var ty = Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2), tx = ty * camera.aspect, worst = 0;
      dir.set(Math.sin(HOME_AZ) * Math.cos(HOME_EL), Math.sin(HOME_EL), Math.cos(HOME_AZ) * Math.cos(HOME_EL)).normalize();
      right.crossVectors(WORLD_UP, dir).normalize();
      up.crossVectors(dir, right).normalize();
      for (var i = 0; i < corners.length; i++) {
        rel.copy(corners[i]).sub(target);
        var depth = rel.dot(dir);
        worst = Math.max(worst, Math.abs(rel.dot(right)) / tx + depth, Math.abs(rel.dot(up)) / ty + depth);
      }
      dist = worst * 1.04;
    }
    function size() {
      var w = host.clientWidth, h = host.clientHeight;
      if (!w || !h) return;
      renderer.setSize(w, h, false);
      camera.aspect = w / h; camera.updateProjectionMatrix();
      fit(); dirty = true;
    }

    /* drag to peek round it; it settles back to the home angle */
    var dragging = false, lastX = 0, lastY = 0, settleAt = 0;
    dom.style.touchAction = 'none';
    dom.addEventListener('pointerdown', function (e) { dragging = true; lastX = e.clientX; lastY = e.clientY; dom.setPointerCapture(e.pointerId); });
    dom.addEventListener('pointermove', function (e) {
      if (!dragging) return;
      targetAz -= (e.clientX - lastX) * 0.008;
      targetEl = Math.max(-0.1, Math.min(1.2, targetEl + (e.clientY - lastY) * 0.005));
      lastX = e.clientX; lastY = e.clientY;
    });
    ['pointerup', 'pointercancel'].forEach(function (ev) {
      dom.addEventListener(ev, function () { dragging = false; settleAt = performance.now() + 2500; });
    });

    /* ------------------------------------------------- wheel speed */
    var mph = 0, omega = 0;
    MOCK.setSpeed = function (v) { mph = v; omega = v * 0.44704 / TYRE_M; dirty = true; };
    MOCK.setView = function (a, e) { targetAz = az = a; targetEl = el = e; settleAt = 0; dirty = true; };

    var dirty = true, last = performance.now();
    function frame(now) {
      requestAnimationFrame(frame);
      var dt = Math.min(0.05, (now - last) / 1000); last = now;

      if (!dragging && settleAt && now > settleAt) {
        targetAz = HOME_AZ; targetEl = HOME_EL;
        if (Math.abs(az - HOME_AZ) < 1e-3) settleAt = 0;
      }
      var moving = dragging || omega > 0 || Math.abs(targetAz - az) > 1e-4 || Math.abs(targetEl - el) > 1e-4;
      if (!moving && !dirty) return;
      dirty = false;

      az += (targetAz - az) * 0.1;
      el += (targetEl - el) * 0.1;
      if (omega > 0) for (var w = 0; w < wheels.length; w++) wheels[w].rotation.z -= omega * dt;

      dir.set(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el)).normalize();
      camera.position.copy(dir).multiplyScalar(dist).add(target);
      camera.lookAt(target);
      renderer.render(scene, camera);
    }

    size();
    window.addEventListener('resize', size);
    wrap.classList.add('live');
    requestAnimationFrame(frame);
  }

  var host = document.getElementById('model3d');
  if (host) init(host);
})();

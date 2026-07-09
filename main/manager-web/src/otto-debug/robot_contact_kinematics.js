import * as THREE from 'three';

const UP = new THREE.Vector3(0, 0, 1);

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function averagePoint(points) {
  const out = new THREE.Vector3();
  for (const point of points) out.add(point);
  return points.length ? out.multiplyScalar(1 / points.length) : out;
}

function horizontalDistance(a, b) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return Math.sqrt(dx * dx + dy * dy);
}

function planeNormal(points) {
  if (points.length < 3) return UP.clone();
  const a = points[1].clone().sub(points[0]);
  const b = points[2].clone().sub(points[0]);
  const normal = new THREE.Vector3().crossVectors(a, b);
  if (normal.lengthSq() < 1e-10) return UP.clone();
  normal.normalize();
  if (normal.dot(UP) < 0) normal.negate();
  return normal;
}

function objectBox(object) {
  object.updateWorldMatrix(true, true);
  return new THREE.Box3().setFromObject(object);
}

function findObject(scene, model, name) {
  return scene.getObjectByName(name) || model.getObjectByName(name);
}

function vectorFromArray(value) {
  if (!Array.isArray(value) || value.length < 3) return null;
  const vector = new THREE.Vector3(Number(value[0]), Number(value[1]), Number(value[2]));
  return Number.isFinite(vector.x + vector.y + vector.z) ? vector : null;
}

function contactSetsFromSidecar(sidecar) {
  return Array.isArray(sidecar?.foot_contact_points) ? sidecar.foot_contact_points : [];
}

function objectLocalMeshBox(object) {
  const box = new THREE.Box3();
  object.updateWorldMatrix(true, true);
  object.traverse(child => {
    if (!child.isMesh || !child.geometry?.attributes?.position) return;
    child.updateWorldMatrix(true, false);
    const position = child.geometry.attributes.position;
    const vertex = new THREE.Vector3();
    for (let i = 0; i < position.count; i++) {
      vertex.fromBufferAttribute(position, i);
      const world = vertex.clone().applyMatrix4(child.matrixWorld);
      box.expandByPoint(object.worldToLocal(world));
    }
  });
  return box;
}

function sortLocalContactPoints(points) {
  const center = averagePoint(points);
  return [...points].sort((a, b) => (
    Math.atan2(a.y - center.y, a.x - center.x) -
    Math.atan2(b.y - center.y, b.x - center.x)
  ));
}

function sidecarContactLocalPoints(object, model, contactSet) {
  const localPoints = Array.isArray(contactSet?.points_local_m) ? contactSet.points_local_m : [];
  if (localPoints.length === 4) {
    const localBox = objectLocalMeshBox(object);
    if (!localBox.isEmpty()) {
      const contacts = [];
      for (const point of localPoints) {
        const local = vectorFromArray(point?.xyz);
        if (!local) return null;
        contacts.push(new THREE.Vector3(-local.x, local.y, localBox.min.z));
      }
      return sortLocalContactPoints(contacts);
    }
  }

  const points = Array.isArray(contactSet?.points_assembly_m) ? contactSet.points_assembly_m : [];
  if (points.length !== 4) return null;
  const contacts = [];
  for (const point of points) {
    const assembly = vectorFromArray(point?.xyz);
    if (!assembly) return null;
    const world = assembly.applyMatrix4(model.matrixWorld);
    contacts.push(object.worldToLocal(world));
  }
  return sortLocalContactPoints(contacts);
}

function fallbackBoxContactLocalPoints(object, fallbackBox) {
  if (!fallbackBox || fallbackBox.isEmpty()) return null;
  const { min, max } = fallbackBox;
  const contactZ = min.z;
  const contactsWorld = [
    new THREE.Vector3(min.x, min.y, contactZ),
    new THREE.Vector3(max.x, min.y, contactZ),
    new THREE.Vector3(max.x, max.y, contactZ),
    new THREE.Vector3(min.x, max.y, contactZ)
  ];
  return sortLocalContactPoints(contactsWorld.map(point => object.worldToLocal(point.clone())));
}

function makeMarker(color, radius) {
  const geometry = new THREE.SphereGeometry(radius, 12, 8);
  const material = new THREE.MeshBasicMaterial({
    color,
    transparent: true,
    opacity: 0.9,
    depthWrite: false
  });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.renderOrder = 20;
  return mesh;
}

export function createContactKinematics(options = {}) {
  return new ContactKinematics(options);
}

class ContactKinematics {
  constructor(options = {}) {
    this.enabled = options.enabled !== false;
    this.debug = options.debug !== false;
    this.footNames = options.footNames || ['foot-1', 'foot-2'];
    this.groundZ = Number.isFinite(options.groundZ) ? options.groundZ : 0;
    this.mu = Number.isFinite(options.friction) ? options.friction : 0.85;
    this.contactThreshold = Number.isFinite(options.contactThreshold) ? options.contactThreshold : null;
    this.supportSwitchHysteresis = Number.isFinite(options.supportSwitchHysteresis) ? options.supportSwitchHysteresis : null;
    this.tiltEnabled = options.tiltEnabled === true;
    this.maxTiltDeg = Number.isFinite(options.maxTiltDeg) ? options.maxTiltDeg : 10;
    this.tiltBlend = Number.isFinite(options.tiltBlend) ? options.tiltBlend : 0.35;
    this.maxStaticCorrection = Number.isFinite(options.maxStaticCorrection) ? options.maxStaticCorrection : null;
    this.groundClamp = options.groundClamp !== false;
    this.groundClearance = Number.isFinite(options.groundClearance) ? options.groundClearance : 0.001;
    this.dynamics = options.dynamics !== false;
    this.contactStiffness = Number.isFinite(options.contactStiffness) ? options.contactStiffness : 85;
    this.contactDamping = Number.isFinite(options.contactDamping) ? options.contactDamping : 18;
    this.verticalStiffness = Number.isFinite(options.verticalStiffness) ? options.verticalStiffness : 150;
    this.verticalDamping = Number.isFinite(options.verticalDamping) ? options.verticalDamping : 24;
    this.gravity = Number.isFinite(options.gravity) ? options.gravity : 9.81;
    this.maxLinearSpeed = Number.isFinite(options.maxLinearSpeed) ? options.maxLinearSpeed : 0.35;

    this.scene = null;
    this.model = null;
    this.motionRoot = null;
    this.sidecar = null;
    this.feet = [];
    this.comLocal = new THREE.Vector3();
    this.anchor = null;
    this.supportName = null;
    this.linearVelocity = new THREE.Vector3();
    this.lastStepAt = 0;
    this.lastContactError = new THREE.Vector3();
    this.lastGroundClamp = 0;
    this.calibrated = false;
    this.lastSlip = false;
    this.debugGroup = null;
    this.debugMarkers = [];
    this.comMarker = null;
    this.footLines = [];
  }

  setEnabled(enabled) {
    this.enabled = !!enabled;
    this.anchor = null;
    this.supportName = null;
    this.linearVelocity.set(0, 0, 0);
    this.lastStepAt = 0;
    if (this.debugGroup) this.debugGroup.visible = this.enabled && this.debug;
  }

  calibrate({ scene, model, motionRoot, sidecar }) {
    this.scene = scene;
    this.model = model;
    this.motionRoot = motionRoot || model;
    this.sidecar = sidecar || null;
    this.feet = [];
    this.anchor = null;
    this.supportName = null;
    this.linearVelocity.set(0, 0, 0);
    this.lastStepAt = 0;
    this.lastContactError.set(0, 0, 0);
    this.lastGroundClamp = 0;

    this.scene.updateMatrixWorld(true);
    const fullBox = objectBox(this.motionRoot);
    const fullSize = fullBox.getSize(new THREE.Vector3());
    const fullRadius = Math.max(fullSize.x, fullSize.y, fullSize.z, 1e-3);
    const contactThreshold = this.contactThreshold ?? Math.max(0.002, fullRadius * 0.012);
    const supportSwitchHysteresis = this.supportSwitchHysteresis ?? contactThreshold * 2.5;

    const comWorld = fullBox.getCenter(new THREE.Vector3());
    comWorld.z = fullBox.min.z + fullSize.z * 0.58;
    this.comLocal.copy(this.motionRoot.worldToLocal(comWorld.clone()));

    let totalFootSpan = 0;
    const contactByFoot = new Map(contactSetsFromSidecar(this.sidecar).map(item => [item.foot_node, item]));
    for (const name of this.footNames) {
      const object = findObject(this.scene, this.model, name);
      if (!object) continue;
      const box = objectBox(object);
      if (box.isEmpty()) continue;
      const size = box.getSize(new THREE.Vector3());
      totalFootSpan += Math.max(size.x, size.y, size.z, 0.01);
      const contactSet = contactByFoot.get(name);
      const contactsLocal = sidecarContactLocalPoints(object, this.model, contactSet)
        || fallbackBoxContactLocalPoints(object, box);
      if (!contactsLocal) continue;
      this.feet.push({
        name,
        object,
        contactsLocal,
        contactSource: contactSet ? 'solidworks-sidecar' : 'fallback-box',
        contactSet
      });
    }

    const avgFootSpan = this.feet.length ? totalFootSpan / this.feet.length : fullRadius * 0.15;
    this.derived = {
      contactThreshold,
      supportSwitchHysteresis,
      friction: this.mu,
      maxStaticCorrection: this.maxStaticCorrection ?? Math.max(0.006, avgFootSpan * (0.35 + this.mu))
    };

    this.calibrated = this.feet.length > 0;
    this.setupDebug(avgFootSpan);
    return this.summary();
  }

  reset() {
    this.anchor = null;
    this.supportName = null;
    this.lastSlip = false;
    this.linearVelocity.set(0, 0, 0);
    this.lastStepAt = 0;
    this.lastContactError.set(0, 0, 0);
    this.lastGroundClamp = 0;
    if (this.motionRoot) {
      this.motionRoot.position.set(0, 0, 0);
      this.motionRoot.quaternion.identity();
      this.motionRoot.updateMatrixWorld(true);
    }
  }

  footState(foot) {
    const contacts = foot.contactsLocal.map(point => foot.object.localToWorld(point.clone()));
    const centroid = averagePoint(contacts);
    const minZ = contacts.reduce((value, point) => Math.min(value, point.z), Infinity);
    return {
      ...foot,
      contacts,
      centroid,
      minZ,
      normal: planeNormal(contacts)
    };
  }

  footStates() {
    this.scene.updateMatrixWorld(true);
    return this.feet.map(foot => this.footState(foot));
  }

  chooseSupport(states) {
    if (!states.length) return null;
    const threshold = this.derived.contactThreshold;
    const minZ = Math.min(...states.map(state => state.minZ));
    const grounded = states.filter(state => state.minZ <= Math.max(this.groundZ + threshold, minZ + threshold));
    const previous = this.anchor ? grounded.find(state => state.name === this.anchor.footName) : null;
    if (previous && previous.minZ <= minZ + this.derived.supportSwitchHysteresis) return previous;

    const comWorld = this.motionRoot.localToWorld(this.comLocal.clone());
    return grounded
      .map(state => ({
        state,
        score: Math.abs(state.minZ - this.groundZ) + horizontalDistance(state.centroid, comWorld) * 0.08
      }))
      .sort((a, b) => a.score - b.score)[0]?.state || states[0];
  }

  ensureAnchor(support) {
    if (this.anchor && this.anchor.footName === support.name) return;
    this.anchor = {
      footName: support.name,
      centroid: new THREE.Vector3(support.centroid.x, support.centroid.y, this.groundZ)
    };
    this.supportName = support.name;
    this.linearVelocity.multiplyScalar(0.35);
  }

  applyTiltCorrection(support, dt = 1 / 60) {
    if (!this.tiltEnabled || !this.motionRoot || this.tiltBlend <= 0) return;
    const align = new THREE.Quaternion().setFromUnitVectors(support.normal, UP);
    const angle = 2 * Math.acos(clamp(align.w, -1, 1));
    if (angle < 1e-5) return;

    const maxTilt = THREE.MathUtils.degToRad(this.maxTiltDeg);
    const blend = Math.min(1, maxTilt / angle) * this.tiltBlend * clamp(dt * 60, 0.1, 2.0);
    const partial = new THREE.Quaternion().identity().slerp(align, blend);
    this.motionRoot.quaternion.premultiply(partial);
    this.motionRoot.updateMatrixWorld(true);
  }

  contactErrorFor(support) {
    const refreshed = this.footState(support);
    return new THREE.Vector3(
      this.anchor.centroid.x - refreshed.centroid.x,
      this.anchor.centroid.y - refreshed.centroid.y,
      this.groundZ - refreshed.minZ
    );
  }

  applyKinematicCorrection(support) {
    const error = this.contactErrorFor(support);
    const horizontal = new THREE.Vector3(error.x, error.y, 0);
    const maxStatic = this.derived.maxStaticCorrection;
    this.lastSlip = horizontal.length() > maxStatic;
    if (this.lastSlip && horizontal.lengthSq() > 1e-12) horizontal.setLength(maxStatic);
    this.motionRoot.position.add(horizontal);
    this.motionRoot.position.z += error.z;
    this.lastContactError.copy(error);
    this.motionRoot.updateMatrixWorld(true);
  }

  applyDynamicCorrection(support, dt) {
    const error = this.contactErrorFor(support);
    const accel = new THREE.Vector3(
      error.x * this.contactStiffness - this.linearVelocity.x * this.contactDamping,
      error.y * this.contactStiffness - this.linearVelocity.y * this.contactDamping,
      error.z * this.verticalStiffness - this.linearVelocity.z * this.verticalDamping
    );

    const horizontalAccel = new THREE.Vector3(accel.x, accel.y, 0);
    const frictionLimit = Math.max(0.2, this.mu * this.gravity);
    this.lastSlip = horizontalAccel.length() > frictionLimit;
    if (this.lastSlip && horizontalAccel.lengthSq() > 1e-12) {
      horizontalAccel.setLength(frictionLimit);
      accel.x = horizontalAccel.x;
      accel.y = horizontalAccel.y;
      const slipRelax = clamp(dt * 4, 0, 0.25);
      this.anchor.centroid.x += (support.centroid.x - this.anchor.centroid.x) * slipRelax;
      this.anchor.centroid.y += (support.centroid.y - this.anchor.centroid.y) * slipRelax;
    }

    this.linearVelocity.addScaledVector(accel, dt);
    if (this.linearVelocity.length() > this.maxLinearSpeed) {
      this.linearVelocity.setLength(this.maxLinearSpeed);
    }
    this.motionRoot.position.addScaledVector(this.linearVelocity, dt);
    this.lastContactError.copy(error);
    this.motionRoot.updateMatrixWorld(true);
  }

  applyGroundPenetrationClamp(states = null) {
    if (!this.groundClamp || !this.motionRoot) return states;
    const currentStates = states || this.footStates();
    if (!currentStates.length) return currentStates;
    const minZ = Math.min(...currentStates.map(state => state.minZ));
    const targetZ = this.groundZ + this.groundClearance;
    const correction = targetZ - minZ;
    if (correction > 0) {
      this.motionRoot.position.z += correction;
      if (this.linearVelocity.z < 0) this.linearVelocity.z = 0;
      this.lastGroundClamp = correction;
      this.motionRoot.updateMatrixWorld(true);
      return this.footStates();
    }
    this.lastGroundClamp = 0;
    return currentStates;
  }

  updateContactState() {
    if (!this.enabled || !this.calibrated || !this.motionRoot) return;
    const states = this.footStates();
    const support = this.chooseSupport(states);
    if (!support) return;
    this.ensureAnchor(support);
    this.updateDebug(states, support.name);
    return { states, support };
  }

  solve() {
    const state = this.updateContactState();
    if (!state) return;
    if (!this.dynamics) {
      this.applyTiltCorrection(state.support);
      this.applyKinematicCorrection(state.support);
    }
    this.updateDebug(this.applyGroundPenetrationClamp(), state.support.name);
  }

  snap() {
    const state = this.updateContactState();
    if (!state) return;
    this.applyTiltCorrection(state.support);
    this.applyKinematicCorrection(state.support);
    this.applyGroundPenetrationClamp();
    this.linearVelocity.set(0, 0, 0);
    this.lastStepAt = 0;
    this.updateDebug(this.footStates(), state.support.name);
  }

  step(nowMs = performance.now()) {
    if (!this.enabled || !this.calibrated || !this.motionRoot || !this.dynamics) return;
    if (!this.lastStepAt) {
      this.lastStepAt = nowMs;
      this.updateContactState();
      return;
    }
    const dt = clamp((nowMs - this.lastStepAt) / 1000, 1 / 240, 1 / 30);
    this.lastStepAt = nowMs;
    const state = this.updateContactState();
    if (!state) return;
    this.applyTiltCorrection(state.support, dt);
    this.applyDynamicCorrection(state.support, dt);
    this.updateDebug(this.applyGroundPenetrationClamp(), state.support.name);
  }

  setupDebug(avgFootSpan) {
    if (!this.debug || !this.scene) return;
    if (this.debugGroup) this.scene.remove(this.debugGroup);
    this.debugGroup = new THREE.Group();
    this.debugGroup.name = 'runtime-contact-kinematics-debug';
    this.debugGroup.visible = this.enabled;
    this.scene.add(this.debugGroup);

    const markerRadius = Math.max(0.002, avgFootSpan * 0.035);
    this.debugMarkers = [];
    for (let i = 0; i < this.feet.length * 4; i++) {
      const marker = makeMarker(0x1f7a4d, markerRadius);
      this.debugGroup.add(marker);
      this.debugMarkers.push(marker);
    }
    this.comMarker = makeMarker(0xff8a00, markerRadius * 1.5);
    this.debugGroup.add(this.comMarker);

    this.footLines = [];
    for (let i = 0; i < this.feet.length; i++) {
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute('position', new THREE.Float32BufferAttribute(new Array(15).fill(0), 3));
      const material = new THREE.LineBasicMaterial({
        color: 0x7994a8,
        transparent: true,
        opacity: 0.7,
        depthWrite: false
      });
      const line = new THREE.Line(geometry, material);
      line.renderOrder = 19;
      this.debugGroup.add(line);
      this.footLines.push(line);
    }
  }

  updateDebug(states, supportName) {
    if (!this.debugGroup || !this.debugGroup.visible) return;
    let markerIndex = 0;
    for (let stateIndex = 0; stateIndex < states.length; stateIndex++) {
      const state = states[stateIndex];
      const color = state.name === supportName ? 0x00a86b : 0x7994a8;
      for (const point of state.contacts) {
        const marker = this.debugMarkers[markerIndex++];
        if (!marker) continue;
        marker.visible = true;
        marker.position.copy(point);
        marker.material.color.setHex(color);
      }
      const line = this.footLines[stateIndex];
      if (line) {
        const points = [...state.contacts, state.contacts[0]];
        const attr = line.geometry.getAttribute('position');
        for (let i = 0; i < 5; i++) {
          const point = points[i] || points[0] || new THREE.Vector3();
          attr.setXYZ(i, point.x, point.y, point.z);
        }
        attr.needsUpdate = true;
        line.material.color.setHex(color);
        line.visible = true;
      }
    }
    for (; markerIndex < this.debugMarkers.length; markerIndex++) {
      this.debugMarkers[markerIndex].visible = false;
    }
    for (let i = states.length; i < this.footLines.length; i++) {
      this.footLines[i].visible = false;
    }

    if (this.comMarker) {
      this.comMarker.position.copy(this.motionRoot.localToWorld(this.comLocal.clone()));
      this.comMarker.material.color.setHex(this.lastSlip ? 0xd9480f : 0xff8a00);
    }
  }

  summary() {
    return {
      enabled: this.enabled,
      calibrated: this.calibrated,
      feet: this.feet.map(foot => foot.name),
      contact_sources: Object.fromEntries(this.feet.map(foot => [foot.name, foot.contactSource])),
      ground_z: this.groundZ,
      virtual_com_local: this.comLocal.toArray(),
      friction: this.mu,
      contact_threshold: this.derived?.contactThreshold ?? this.contactThreshold,
      support_switch_hysteresis: this.derived?.supportSwitchHysteresis ?? this.supportSwitchHysteresis,
      max_static_correction: this.derived?.maxStaticCorrection ?? this.maxStaticCorrection,
      support_foot: this.anchor?.footName || null,
      dynamics: this.dynamics,
      tilt_enabled: this.tiltEnabled,
      ground_clamp: this.groundClamp,
      ground_clearance: this.groundClearance,
      last_ground_clamp: this.lastGroundClamp,
      linear_velocity: this.linearVelocity.toArray(),
      last_contact_error: this.lastContactError.toArray(),
      contact_stiffness: this.contactStiffness,
      contact_damping: this.contactDamping,
      vertical_stiffness: this.verticalStiffness,
      vertical_damping: this.verticalDamping,
      max_linear_speed: this.maxLinearSpeed,
      slipping: this.lastSlip
    };
  }
}

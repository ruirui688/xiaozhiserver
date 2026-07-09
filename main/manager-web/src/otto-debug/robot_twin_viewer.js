import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { TrackballControls } from 'three/addons/controls/TrackballControls.js';
import { createContactKinematics } from './robot_contact_kinematics.js';
import {
  FIRMWARE_SERVO_PROTOCOL,
  FIRMWARE_SERVO_TO_SIDECAR_JOINT,
  WEB_PREVIEW_SERVO_TO_JOINT_SIGN,
  clamp,
  getFiniteNumber,
  mapServoCommand,
  servoNumberField
} from './robot_servo_mapping.js';

const JOINT_SIDE_CAR_URL = `${process.env.BASE_URL || '/'}otto-debug/joints/otto_robot_joints.json`;

function degToRad(degrees) {
  return degrees * Math.PI / 180;
}

function setStatus(status, text) {
  if (status) status.textContent = text;
}

function makeEyeCanvas() {
  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = 512;
  return canvas;
}

function roundedRectPath(ctx, x, y, width, height, radius) {
  const r = Math.min(radius, width / 2, height / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + width - r, y);
  ctx.quadraticCurveTo(x + width, y, x + width, y + r);
  ctx.lineTo(x + width, y + height - r);
  ctx.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
  ctx.lineTo(x + r, y + height);
  ctx.quadraticCurveTo(x, y + height, x, y + height - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
}

function drawEyes(canvas, pose, options = {}) {
  const ctx = canvas.getContext('2d');
  const t = performance.now() / 1000;
  const blinkPhase = t % 3.8;
  const blink = blinkPhase > 3.55 ? 1 - Math.abs(3.675 - blinkPhase) / 0.125 : 0;
  const eyeHeight = clamp(86 * (1 - blink), 8, 86);
  const handLean = ((pose.left_hand ?? 90) - (pose.right_hand ?? 90)) / 180;
  const legLean = ((pose.left_leg ?? 90) - (pose.right_leg ?? 90)) / 180;
  const pupilX = clamp((handLean + legLean) * 30, -38, 38);

  ctx.clearRect(0, 0, 512, 512);
  const clipRadius = Number(options.clipRadius || 0);
  if (clipRadius > 0) {
    ctx.save();
    roundedRectPath(ctx, 8, 8, 496, 496, clipRadius);
    ctx.clip();
  }

  if (!options.transparent) {
    const gradient = ctx.createLinearGradient(0, 0, 0, 512);
    gradient.addColorStop(0, '#fff9ef');
    gradient.addColorStop(1, '#ffe3bd');
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, 512, 512);
  }

  ctx.fillStyle = '#241810';
  ctx.beginPath();
  ctx.ellipse(182 + pupilX, 246, 46, eyeHeight / 2, 0, 0, Math.PI * 2);
  ctx.ellipse(330 + pupilX, 246, 46, eyeHeight / 2, 0, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = 'rgba(255,255,255,.5)';
  ctx.beginPath();
  ctx.ellipse(166 + pupilX, 226, 12, 8, 0, 0, Math.PI * 2);
  ctx.ellipse(314 + pupilX, 226, 12, 8, 0, 0, Math.PI * 2);
  ctx.fill();
  if (clipRadius > 0) ctx.restore();
}

export function createRobotTwinViewer({
  container,
  status,
  modelUrl,
  contactPreview = true,
  contactDebug = false,
  renderFps = 60,
  backgroundFps = 5,
  smoothServoPreview = true,
  servoPreviewSpeedDps = 360
} = {}) {
  const state = {
    scene: null,
    camera: null,
    renderer: null,
    controls: null,
    model: null,
    motionRoot: null,
    contactKinematics: null,
    joints: new Map(),
    jointEntries: new Map(),
    pose: {},
    visualPose: {},
    visualPoseReady: false,
    lastVisualPoseAt: 0,
    smoothServoPreview: smoothServoPreview !== false,
    servoPreviewSpeedDps: Math.max(1, Number(servoPreviewSpeedDps) || 360),
    jointPose: {},
    catalog: [],
    eyeCanvas: makeEyeCanvas(),
    eyeOverlayCanvas: makeEyeCanvas(),
    eyeTexture: null,
    eyeOverlayTexture: null,
    screenOverlay: null,
    jointSidecar: null,
    animationId: 0,
    frameTimer: 0,
    lastRenderAt: 0,
    renderFps: Math.max(1, Number(renderFps) || 60),
    backgroundFps: Math.max(1, Number(backgroundFps) || 5),
    lastEyeDraw: 0
  };

  if (!container) {
    setStatus(status, '3D 容器不存在');
    return {
      setServoPose() {},
      setJointPose() {},
      setContactPreview() {},
      resetMotion() {},
      resetCamera() {},
      dispose() {}
    };
  }

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0xf7f0e5);

  const camera = new THREE.PerspectiveCamera(35, 1, 0.01, 100);
  camera.up.set(0, 0, 1);
  camera.position.set(0.2, 0.58, 0.18);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);

  const controls = new TrackballControls(camera, renderer.domElement);
  controls.rotateSpeed = 3.4;
  controls.zoomSpeed = 1.15;
  controls.panSpeed = 0.85;
  controls.staticMoving = false;
  controls.dynamicDampingFactor = 0.14;
  controls.target.set(0, 0, 0.02);

  const hemi = new THREE.HemisphereLight(0xffffff, 0x9b7b5f, 2.2);
  scene.add(hemi);

  const key = new THREE.DirectionalLight(0xffffff, 2.4);
  key.position.set(0.2, -0.4, 0.8);
  scene.add(key);

  const fill = new THREE.DirectionalLight(0xffd6a4, 0.9);
  fill.position.set(-0.5, 0.2, 0.35);
  scene.add(fill);

  const ground = makeVirtualGround();
  scene.add(ground);

  const motionRoot = new THREE.Group();
  motionRoot.name = 'runtime-motion-root';
  scene.add(motionRoot);

  state.scene = scene;
  state.camera = camera;
  state.renderer = renderer;
  state.controls = controls;
  state.motionRoot = motionRoot;

  state.eyeTexture = new THREE.CanvasTexture(state.eyeCanvas);
  state.eyeTexture.colorSpace = THREE.SRGBColorSpace;
  state.eyeOverlayTexture = new THREE.CanvasTexture(state.eyeOverlayCanvas);
  state.eyeOverlayTexture.colorSpace = THREE.SRGBColorSpace;
  drawEyes(state.eyeCanvas, state.visualPose);
  drawEyes(state.eyeOverlayCanvas, state.visualPose, { transparent: true });
  state.eyeTexture.needsUpdate = true;
  state.eyeOverlayTexture.needsUpdate = true;

  function resize() {
    const rect = container.getBoundingClientRect();
    const width = Math.max(1, Math.floor(rect.width));
    const height = Math.max(1, Math.floor(rect.height));
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    if (typeof controls.handleResize === 'function') controls.handleResize();
  }

  const resizeObserver = new ResizeObserver(resize);
  resizeObserver.observe(container);
  resize();

  function scheduleFrame(now = performance.now()) {
    const fps = document.visibilityState === 'hidden' ? state.backgroundFps : state.renderFps;
    const intervalMs = 1000 / fps;
    const elapsedMs = state.lastRenderAt ? now - state.lastRenderAt : intervalMs;
    const delayMs = Math.max(0, intervalMs - elapsedMs);
    state.frameTimer = window.setTimeout(() => {
      state.frameTimer = 0;
      frame(performance.now());
    }, delayMs);
  }

  function frame(now) {
    state.lastRenderAt = now;
    stepServoPreview(now);
    if (now - state.lastEyeDraw > 80) {
      drawEyes(state.eyeCanvas, activeServoPose());
      drawEyes(state.eyeOverlayCanvas, activeServoPose(), { transparent: true });
      state.eyeTexture.needsUpdate = true;
      state.eyeOverlayTexture.needsUpdate = true;
      state.lastEyeDraw = now;
    }
    state.contactKinematics?.step(now);
    controls.update();
    renderer.render(scene, camera);
    scheduleFrame(now);
  }
  scheduleFrame();

  function applyScreenTexture() {
    const screen = state.model.getObjectByName('屏幕-1');
    if (!screen?.isMesh || !screen.geometry) return;

    screen.geometry.computeBoundingBox();
    const box = screen.geometry.boundingBox;
    if (!box || box.isEmpty()) return;

    const outwardWorld = displayOutwardWorld();

    const localAxes = [
      { axis: 'x', sign: 1, normal: new THREE.Vector3(1, 0, 0) },
      { axis: 'x', sign: -1, normal: new THREE.Vector3(-1, 0, 0) },
      { axis: 'y', sign: 1, normal: new THREE.Vector3(0, 1, 0) },
      { axis: 'y', sign: -1, normal: new THREE.Vector3(0, -1, 0) },
      { axis: 'z', sign: 1, normal: new THREE.Vector3(0, 0, 1) },
      { axis: 'z', sign: -1, normal: new THREE.Vector3(0, 0, -1) }
    ];
    const normalMatrix = new THREE.Matrix3().getNormalMatrix(screen.matrixWorld);
    let face = localAxes[0];
    let best = -Infinity;
    for (const candidate of localAxes) {
      const worldNormal = candidate.normal.clone().applyMatrix3(normalMatrix).normalize();
      const score = worldNormal.dot(outwardWorld);
      if (score > best) {
        best = score;
        face = candidate;
      }
    }

    const min = box.min;
    const max = box.max;
    const center = box.getCenter(new THREE.Vector3());
    const epsilon = 0.003;
    const coord = face.sign > 0
      ? max[face.axis] + epsilon
      : min[face.axis] - epsilon;

    const vertices = [];
    const push = (x, y, z) => vertices.push(x, y, z);
    if (face.axis === 'z') {
      push(min.x, min.y, coord);
      push(max.x, min.y, coord);
      push(max.x, max.y, coord);
      push(min.x, max.y, coord);
    } else if (face.axis === 'y') {
      push(min.x, coord, min.z);
      push(max.x, coord, min.z);
      push(max.x, coord, max.z);
      push(min.x, coord, max.z);
    } else {
      push(coord, min.y, min.z);
      push(coord, max.y, min.z);
      push(coord, max.y, max.z);
      push(coord, min.y, max.z);
    }

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3));
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute([
      0, 0,
      1, 0,
      1, 1,
      0, 1
    ], 2));
    geometry.setIndex(face.sign > 0 ? [0, 1, 2, 0, 2, 3] : [0, 2, 1, 0, 3, 2]);
    geometry.computeVertexNormals();

    const material = new THREE.MeshBasicMaterial({
      map: state.eyeTexture,
      side: THREE.DoubleSide,
      transparent: true,
      depthTest: true,
      depthWrite: false,
      polygonOffset: true,
      polygonOffsetFactor: -4,
      polygonOffsetUnits: -4,
      toneMapped: false
    });

    const overlay = new THREE.Mesh(geometry, material);
    overlay.name = 'runtime-display-video-surface';
    overlay.renderOrder = 50;
    screen.add(overlay);
    state.screenOverlay = overlay;
  }

  function displayOutwardWorld() {
    const screen = state.model?.getObjectByName('屏幕-1');
    const body = state.model?.getObjectByName('body有手-1');
    if (!screen || !body) return new THREE.Vector3(0, -1, 0);

    state.model.updateWorldMatrix(true, true);
    const screenCenter = new THREE.Vector3();
    const bodyCenter = new THREE.Vector3();
    screen.getWorldPosition(screenCenter);
    body.getWorldPosition(bodyCenter);
    const outward = screenCenter.sub(bodyCenter);
    if (outward.lengthSq() < 1e-8) return new THREE.Vector3(0, -1, 0);
    return outward.normalize();
  }

  function robotUpWorld() {
    if (!state.model) return new THREE.Vector3(0, 0, 1);
    return new THREE.Vector3(0, 0, 1)
      .applyQuaternion(state.model.getWorldQuaternion(new THREE.Quaternion()))
      .normalize();
  }

  function robotFrontWorld() {
    const up = robotUpWorld();
    const outward = displayOutwardWorld();
    const front = outward.sub(up.clone().multiplyScalar(outward.dot(up)));
    if (front.lengthSq() < 1e-8) {
      return new THREE.Vector3(0, -1, 0)
        .applyQuaternion(state.model.getWorldQuaternion(new THREE.Quaternion()))
        .normalize();
    }
    return front.normalize();
  }

  function displayFocusState() {
    if (!state.model) return null;
    state.model.updateWorldMatrix(true, true);

    const modelBox = new THREE.Box3().setFromObject(state.model);
    if (modelBox.isEmpty()) return null;

    const up = robotUpWorld();
    const front = robotFrontWorld();
    const screen = state.model.getObjectByName('屏幕-1');
    const screenBox = screen ? new THREE.Box3().setFromObject(screen) : modelBox;
    const screenCenter = screenBox.getCenter(new THREE.Vector3());
    const modelSphere = modelBox.getBoundingSphere(new THREE.Sphere());
    const screenSize = screenBox.getSize(new THREE.Vector3());
    const screenRadius = Math.max(screenSize.length() * 0.5, 0.05);
    const target = modelSphere.center.clone();
    const fitDistance = Math.max(
      modelSphere.radius / Math.tan(THREE.MathUtils.degToRad(camera.fov) * 0.5) * 1.12,
      modelSphere.radius * 2.45
    );
    const minDistance = Math.max(modelSphere.radius * 0.9, screenRadius * 1.4);

    return {
      target,
      screenCenter,
      up,
      front,
      modelRadius: modelSphere.radius,
      screenRadius,
      fitDistance,
      minDistance,
      maxDistance: fitDistance * 6
    };
  }

  function focusCameraOnDisplay() {
    const focus = displayFocusState();
    if (!focus) return;
    const target = focus.target.clone().add(focus.up.clone().multiplyScalar(focus.modelRadius * 0.03));
    camera.up.copy(focus.up);
    camera.position.copy(target).add(focus.front.clone().multiplyScalar(focus.fitDistance));
    controls.minDistance = focus.minDistance;
    controls.maxDistance = focus.maxDistance;
    controls.target.copy(target);
    camera.lookAt(controls.target);
    controls.update();
  }

  function makeVirtualGround() {
    const size = 0.52;
    const divisions = 13;
    const half = size * 0.5;
    const step = size / divisions;
    const vertices = [];

    for (let i = 0; i <= divisions; i++) {
      const p = -half + i * step;
      vertices.push(-half, p, 0, half, p, 0);
      vertices.push(p, -half, 0, p, half, 0);
    }

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3));
    const material = new THREE.LineBasicMaterial({
      color: 0xe8dac8,
      transparent: true,
      opacity: 0.18,
      depthTest: true,
      depthWrite: true
    });
    const grid = new THREE.LineSegments(geometry, material);
    grid.name = 'runtime-virtual-ground';
    grid.renderOrder = -10;
    return grid;
  }

  function boxCorners(box) {
    const { min, max } = box;
    return [
      new THREE.Vector3(min.x, min.y, min.z),
      new THREE.Vector3(min.x, min.y, max.z),
      new THREE.Vector3(min.x, max.y, min.z),
      new THREE.Vector3(min.x, max.y, max.z),
      new THREE.Vector3(max.x, min.y, min.z),
      new THREE.Vector3(max.x, min.y, max.z),
      new THREE.Vector3(max.x, max.y, min.z),
      new THREE.Vector3(max.x, max.y, max.z)
    ];
  }

  function supportBoxAlong(objects, normal, fallbackBox) {
    const boxes = objects.length
      ? objects.map(object => new THREE.Box3().setFromObject(object)).filter(box => !box.isEmpty())
      : [];
    const targetBoxes = boxes.length ? boxes : [fallbackBox];
    let support = Infinity;
    for (const box of targetBoxes) {
      for (const corner of boxCorners(box)) {
        support = Math.min(support, corner.dot(normal));
      }
    }
    return Number.isFinite(support) ? support : 0;
  }

  function alignModelToRobotGround(model, modelBox) {
    const rotation = model.getWorldQuaternion(new THREE.Quaternion());
    const groundX = new THREE.Vector3(1, 0, 0).applyQuaternion(rotation).normalize();
    const groundY = new THREE.Vector3(0, 1, 0).applyQuaternion(rotation).normalize();
    const groundNormal = new THREE.Vector3(0, 0, 1).applyQuaternion(rotation).normalize();
    const center = modelBox.getCenter(new THREE.Vector3());
    const footObjects = ['foot-1', 'foot-2']
      .map(name => model.getObjectByName(name))
      .filter(Boolean);
    const support = supportBoxAlong(footObjects, groundNormal, modelBox);
    const shift = groundX.clone().multiplyScalar(center.dot(groundX))
      .add(groundY.clone().multiplyScalar(center.dot(groundY)))
      .add(groundNormal.clone().multiplyScalar(support));

    model.position.sub(shift);
    ground.position.set(0, 0, 0);
    ground.quaternion.copy(rotation);
  }

  function frameModel(model) {
    model.position.set(0, 0, 0);
    model.rotation.set(Math.PI / 2, 0, Math.PI);
    model.scale.setScalar(1);
    model.updateMatrixWorld(true);

    let box = new THREE.Box3().setFromObject(model);
    const size = box.getSize(new THREE.Vector3());
    const maxSize = Math.max(size.x, size.y, size.z) || 1;
    model.scale.setScalar(0.46 / maxSize);

    model.updateMatrixWorld(true);
    box = new THREE.Box3().setFromObject(model);
    alignModelToRobotGround(model, box);
    model.updateMatrixWorld(true);

    focusCameraOnDisplay();
  }

  function objectBox(object) {
    object.updateWorldMatrix(true, true);
    return new THREE.Box3().setFromObject(object);
  }

  function combinedBox(objects) {
    const box = new THREE.Box3();
    for (const object of objects) box.union(objectBox(object));
    return box;
  }

  function closestSurfacePoint(referenceBox, movingBox) {
    const movingCenter = movingBox.getCenter(new THREE.Vector3());
    return referenceBox.clampPoint(movingCenter, new THREE.Vector3());
  }

  function contactPivot(referenceObjects, movingObjects) {
    const referenceBox = combinedBox(referenceObjects);
    const movingBox = combinedBox(movingObjects);
    return closestSurfacePoint(referenceBox, movingBox);
  }

  async function loadJointSidecar() {
    try {
      const response = await fetch(JOINT_SIDE_CAR_URL, { cache: 'no-cache' });
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
      state.jointSidecar = await response.json();
      return state.jointSidecar;
    } catch (error) {
      console.warn('Joint sidecar unavailable; using built-in joint map.', error);
      state.jointSidecar = null;
      return null;
    }
  }

  function makePivotGroup(name, objects, pivot) {
    const group = new THREE.Group();
    group.name = `runtime-${name}-pivot`;
    group.position.copy(pivot);
    motionRoot.add(group);
    for (const object of objects) group.attach(object);
    return group;
  }

  function vectorFromArray(value) {
    if (!Array.isArray(value) || value.length < 3) return null;
    const vector = new THREE.Vector3(Number(value[0]), Number(value[1]), Number(value[2]));
    return Number.isFinite(vector.x + vector.y + vector.z) ? vector : null;
  }

  function toWorldPoint(value) {
    const point = vectorFromArray(value);
    if (!point || !state.model) return null;
    return point.applyMatrix4(state.model.matrixWorld);
  }

  function toWorldAxis(value) {
    const axis = vectorFromArray(value);
    if (!axis || !state.model || axis.lengthSq() < 1e-12) return null;
    const normalMatrix = new THREE.Matrix3().getNormalMatrix(state.model.matrixWorld);
    return axis.applyMatrix3(normalMatrix).normalize();
  }

  function signedAngleDeg(from, to, axis) {
    const cross = new THREE.Vector3().crossVectors(from, to);
    return Math.atan2(axis.dot(cross), from.dot(to)) * 180 / Math.PI;
  }

  function inferSolidWorksDeltaSign(joint) {
    const refs = Array.isArray(joint.limit_reference_entities) ? joint.limit_reference_entities : [];
    const axis = vectorFromArray(joint.axis_assembly);
    if (refs.length < 2 || !axis || axis.lengthSq() < 1e-12) {
      return { sign: 1, source: 'default:no-limit-reference' };
    }

    const movingNames = new Set(joint.moving_nodes || []);
    const movingRef = refs.find(ref => movingNames.has(ref?.name)) || refs[0];
    const fixedRef = refs.find(ref => ref !== movingRef) || refs[1];
    const movingDirection = vectorFromArray(movingRef?.direction_assembly);
    const fixedDirection = vectorFromArray(fixedRef?.direction_assembly);
    if (!movingDirection || !fixedDirection || movingDirection.lengthSq() < 1e-12 || fixedDirection.lengthSq() < 1e-12) {
      return { sign: 1, source: 'default:invalid-limit-reference' };
    }

    axis.normalize();
    movingDirection.normalize();
    fixedDirection.normalize();
    const signedHomeDeg = signedAngleDeg(fixedDirection, movingDirection, axis);
    const homeDeg = getFiniteNumber(joint.home_deg, 0);
    if (Math.abs(signedHomeDeg) < 1e-5 || Math.abs(homeDeg) < 1e-5) {
      return {
        sign: 1,
        source: 'default:zero-home',
        signedHomeDeg,
        movingReference: movingRef?.name || '',
        fixedReference: fixedRef?.name || ''
      };
    }

    return {
      sign: signedHomeDeg * homeDeg >= 0 ? 1 : -1,
      source: 'limit-reference',
      signedHomeDeg,
      movingReference: movingRef?.name || '',
      fixedReference: fixedRef?.name || ''
    };
  }

  function logicalJoints() {
    const joints = Array.isArray(state.jointSidecar?.logical_joints)
      ? state.jointSidecar.logical_joints
      : [];
    return joints.filter((joint) => (
      joint &&
      typeof (joint.joint_id || joint.servo) === 'string' &&
      Array.isArray(joint.moving_nodes) &&
      joint.moving_nodes.length &&
      Array.isArray(joint.pivot_assembly_m) &&
      Array.isArray(joint.axis_assembly)
    ));
  }

  function sortChildJointsFirst(joints) {
    const memo = new Map();
    const jointIdOf = (joint) => joint?.joint_id || joint?.servo || '';
    const depthOf = (joint, trail = new Set()) => {
      const jointId = jointIdOf(joint);
      if (memo.has(jointId)) return memo.get(jointId);
      if (trail.has(jointId)) return 0;
      trail.add(jointId);

      let depth = 0;
      for (const possibleParent of joints) {
        if (possibleParent === joint) continue;
        const parentMoves = new Set(possibleParent.moving_nodes || []);
        if (parentMoves.has(joint.parent_node)) {
          depth = Math.max(depth, depthOf(possibleParent, trail) + 1);
        }
      }

      trail.delete(jointId);
      memo.set(jointId, depth);
      return depth;
    };

    return [...joints].sort((a, b) => depthOf(b) - depthOf(a));
  }

  function servoField(servo, names, fallback) {
    return servoNumberField(servo, names, fallback);
  }

  function objectByRuntimeName(name, groups) {
    return groups.get(name) || state.model.getObjectByName(name) || scene.getObjectByName(name);
  }

  function buildRuntimeJoints() {
    state.joints.clear();
    state.jointEntries.clear();
    state.model.updateMatrixWorld(true);
    const joints = sortChildJointsFirst(logicalJoints());
    const groups = new Map();
    const runtimeEntries = [];

    for (const joint of joints) {
      const sidecarJointId = joint.joint_id || joint.servo;
      const moving = [];
      const seen = new Set();
      for (const nodeName of joint.moving_nodes) {
        const object = objectByRuntimeName(nodeName, groups);
        if (!object || seen.has(object.uuid)) continue;
        moving.push(object);
        seen.add(object.uuid);
      }
      if (!moving.length) continue;

      const pivot = toWorldPoint(joint.pivot_assembly_m);
      const axis = toWorldAxis(joint.axis_assembly);
      if (!pivot || !axis) {
        console.warn('Skipping joint with invalid pivot/axis:', sidecarJointId, joint);
        continue;
      }

      const group = makePivotGroup(sidecarJointId.replace(/_/g, '-'), moving, pivot);
      for (const nodeName of joint.moving_nodes) {
        if (!groups.has(nodeName)) groups.set(nodeName, group);
      }
      const runtimeServo = Object.entries(FIRMWARE_SERVO_TO_SIDECAR_JOINT)
        .find(([, mappedJointId]) => mappedJointId === sidecarJointId)?.[0] || sidecarJointId;
      groups.set(sidecarJointId, group);
      groups.set(runtimeServo, group);
      runtimeEntries.push({ servoId: runtimeServo, sidecarJointId, joint, group, worldAxis: axis });
    }

    scene.updateMatrixWorld(true);
    for (const entry of runtimeEntries) {
      const worldQuaternion = entry.group.getWorldQuaternion(new THREE.Quaternion());
      const localAxis = entry.worldAxis.clone().applyQuaternion(worldQuaternion.invert()).normalize();
      const jointHomeDeg = getFiniteNumber(entry.joint.home_deg, 0);
      const minDeg = getFiniteNumber(entry.joint.min_deg, null);
      const maxDeg = getFiniteNumber(entry.joint.max_deg, null);
      const protocol = FIRMWARE_SERVO_PROTOCOL[entry.servoId] || {};
      const protocolSign = getFiniteNumber(protocol.servoToJointSign, 1) || 1;
      const previewSign = getFiniteNumber(WEB_PREVIEW_SERVO_TO_JOINT_SIGN[entry.servoId], protocolSign) || 1;
      const swDirection = inferSolidWorksDeltaSign(entry.joint);
      const runtimeEntry = {
        object: entry.group,
        baseQuaternion: entry.group.quaternion.clone(),
        axis: localAxis,
        servoId: entry.servoId,
        sidecarJointId: entry.sidecarJointId,
        servoToJointSign: previewSign,
        webPreviewSign: previewSign,
        swDeltaSign: swDirection.sign,
        swDeltaSignSource: swDirection.source,
        swSignedHomeDeg: swDirection.signedHomeDeg,
        swMovingReference: swDirection.movingReference,
        swFixedReference: swDirection.fixedReference,
        defaultServoHomeDeg: getFiniteNumber(protocol.servoHomeDeg, 90),
        defaultServoMinDeg: getFiniteNumber(protocol.servoMinDeg, 0),
        defaultServoMaxDeg: getFiniteNumber(protocol.servoMaxDeg, 180),
        jointHomeDeg,
        minDeg,
        maxDeg,
        mate: entry.joint.mate || '',
        limitMate: entry.joint.limit_mate || '',
        protocolSign
      };
      state.joints.set(entry.servoId, [runtimeEntry]);
      state.jointEntries.set(entry.sidecarJointId, runtimeEntry);
    }

    if (!state.joints.size) {
      console.warn('No runtime joints were created from assets/otto_robot/joints/otto_robot_joints.json');
    }
  }

  function applyJointPose() {
    if (!state.model) return;
    for (const [jointId, entry] of state.jointEntries.entries()) {
      const rawTargetDeg = getFiniteNumber(state.jointPose[jointId], entry.jointHomeDeg);
      const jointTargetDeg = entry.minDeg === null || entry.maxDeg === null
        ? rawTargetDeg
        : clamp(rawTargetDeg, entry.minDeg, entry.maxDeg);
      const deltaDeg = jointTargetDeg - entry.jointHomeDeg;
      entry.object.quaternion.copy(entry.baseQuaternion);
      entry.object.rotateOnAxis(entry.axis, degToRad(deltaDeg * entry.swDeltaSign));
    }
    scene.updateMatrixWorld(true);
    state.contactKinematics?.solve();
  }

  function servoToJointDeg(servoAngle, servo, entry) {
    const mapped = mapServoCommand(servoAngle, servo, entry);
    const servoDelta = mapped.servoDeltaDeg;

    if (entry.minDeg === null || entry.maxDeg === null) {
      return entry.jointHomeDeg + servoDelta * entry.servoToJointSign;
    }

    const jointPositiveSpan = Math.max(0, entry.maxDeg - entry.jointHomeDeg);
    const jointNegativeSpan = Math.max(0, entry.jointHomeDeg - entry.minDeg);
    const servoSpan = servoDelta >= 0
      ? Math.max(1e-6, mapped.protocolMaxDeg - mapped.servoHomeDeg)
      : Math.max(1e-6, mapped.servoHomeDeg - mapped.protocolMinDeg);
    const signedServoDelta = servoDelta * entry.servoToJointSign;
    const jointSpan = signedServoDelta >= 0 ? jointPositiveSpan : jointNegativeSpan;
    const jointDelta = signedServoDelta * (jointSpan / servoSpan);
    return clamp(entry.jointHomeDeg + jointDelta, entry.minDeg, entry.maxDeg);
  }

  function activeServoPose() {
    return state.visualPoseReady ? state.visualPose : state.pose;
  }

  function syncVisualPoseToTarget() {
    state.visualPose = {...state.pose};
    state.visualPoseReady = true;
    state.lastVisualPoseAt = performance.now();
  }

  function stepServoPreview(now) {
    if (!state.model || !state.smoothServoPreview) return;
    if (!state.visualPoseReady) {
      syncVisualPoseToTarget();
      applyPose(state.visualPose);
      return;
    }

    const dt = state.lastVisualPoseAt
      ? clamp((now - state.lastVisualPoseAt) / 1000, 1 / 240, 1 / 15)
      : 1 / 60;
    state.lastVisualPoseAt = now;

    let changed = false;
    const maxDelta = state.servoPreviewSpeedDps * dt;
    for (const [id, targetValue] of Object.entries(state.pose)) {
      const target = Number(targetValue);
      if (!Number.isFinite(target)) continue;
      const currentValue = Number(state.visualPose[id]);
      if (!Number.isFinite(currentValue)) {
        state.visualPose[id] = target;
        changed = true;
        continue;
      }
      const delta = target - currentValue;
      if (Math.abs(delta) <= 1e-4) continue;
      state.visualPose[id] = Math.abs(delta) <= maxDelta
        ? target
        : currentValue + Math.sign(delta) * maxDelta;
      changed = true;
    }

    if (changed) applyPose(state.visualPose);
  }

  function applyPose(poseSource = activeServoPose(), options = {}) {
    if (!state.model) return;
    const catalogById = new Map(state.catalog.map(item => [item.id, item]));
    for (const [id, entries] of state.joints.entries()) {
      const servo = catalogById.get(id) || {};
      const protocol = FIRMWARE_SERVO_PROTOCOL[id] || {};
      const servoHomeDeg = servoField(servo, ['home', 'homeAngle', 'home_angle'], getFiniteNumber(protocol.servoHomeDeg, 90));
      const servoAngle = getFiniteNumber(poseSource[id], servoHomeDeg);
      for (const entry of entries) {
        const jointTargetDeg = servoToJointDeg(servoAngle, servo, entry);
        const deltaDeg = jointTargetDeg - entry.jointHomeDeg;
        entry.object.quaternion.copy(entry.baseQuaternion);
        entry.object.rotateOnAxis(entry.axis, degToRad(deltaDeg * entry.swDeltaSign));
      }
    }
    scene.updateMatrixWorld(true);
    if (options.snapContact) state.contactKinematics?.snap();
    else state.contactKinematics?.solve();
  }

  function getServoJointTargets(pose = state.pose, catalog = state.catalog) {
    const catalogById = new Map((Array.isArray(catalog) ? catalog : []).map(item => [item.id, item]));
    const result = {};
    for (const [id, entries] of state.joints.entries()) {
      const servo = catalogById.get(id) || {};
      const protocol = FIRMWARE_SERVO_PROTOCOL[id] || {};
      const servoHomeDeg = servoField(servo, ['home', 'homeAngle', 'home_angle'], getFiniteNumber(protocol.servoHomeDeg, 90));
      const servoAngle = getFiniteNumber(pose[id], servoHomeDeg);
      const entry = entries[0];
      if (!entry) continue;
      const mappedServo = mapServoCommand(servoAngle, servo, entry);
      const jointTargetDeg = servoToJointDeg(servoAngle, servo, entry);
      result[id] = {
        servo_deg: mappedServo.logicalDeg,
        servo_requested_deg: mappedServo.requestedDeg,
        servo_safe_deg: mappedServo.safeLogicalDeg,
        servo_home_deg: mappedServo.servoHomeDeg,
        servo_protocol_min_deg: entry.defaultServoMinDeg,
        servo_protocol_max_deg: entry.defaultServoMaxDeg,
        servo_safe_min_deg: mappedServo.safeMinDeg,
        servo_safe_max_deg: mappedServo.safeMaxDeg,
        servo_reverse: mappedServo.reverse,
        servo_trim_deg: mappedServo.trimDeg,
        servo_physical_trim_deg: mappedServo.physicalTrimDeg,
        servo_physical_deg: mappedServo.physicalDeg,
        servo_pwm_deg: mappedServo.pwmDeg,
        physical_servo: mappedServo.physicalServo,
        joint_deg: jointTargetDeg,
        joint_home_deg: entry.jointHomeDeg,
        joint_min_deg: entry.minDeg,
        joint_max_deg: entry.maxDeg,
        protocol_servo_to_joint_sign: entry.protocolSign,
        web_preview_servo_to_joint_sign: entry.webPreviewSign,
        sw_delta_sign: entry.swDeltaSign,
        sw_delta_sign_source: entry.swDeltaSignSource,
        sw_signed_home_deg: entry.swSignedHomeDeg,
        sw_moving_reference: entry.swMovingReference,
        sw_fixed_reference: entry.swFixedReference,
        sidecar_joint_id: entry.sidecarJointId,
        mate: entry.mate,
        limit_mate: entry.limitMate
      };
    }
    return result;
  }

  function getJointTargets(pose = state.jointPose) {
    const result = {};
    for (const [jointId, entry] of state.jointEntries.entries()) {
      const rawTargetDeg = getFiniteNumber(pose[jointId], entry.jointHomeDeg);
      const jointTargetDeg = entry.minDeg === null || entry.maxDeg === null
        ? rawTargetDeg
        : clamp(rawTargetDeg, entry.minDeg, entry.maxDeg);
      result[jointId] = {
        joint_id: jointId,
        joint_deg: jointTargetDeg,
        joint_home_deg: entry.jointHomeDeg,
        joint_min_deg: entry.minDeg,
        joint_max_deg: entry.maxDeg,
        sw_delta_sign: entry.swDeltaSign,
        sw_delta_sign_source: entry.swDeltaSignSource,
        sw_signed_home_deg: entry.swSignedHomeDeg,
        sw_moving_reference: entry.swMovingReference,
        sw_fixed_reference: entry.swFixedReference,
        mate: entry.mate,
        limit_mate: entry.limitMate
      };
    }
    return result;
  }

  function setServoPose(pose = {}, catalog = [], meta = {}) {
    state.pose = {...state.pose, ...pose};
    state.catalog = Array.isArray(catalog) ? catalog : [];
    if (meta.immediate) {
      syncVisualPoseToTarget();
      applyPose(state.pose, { snapContact: !!meta.snapContact });
    } else if (state.smoothServoPreview) {
      if (!state.visualPoseReady) syncVisualPoseToTarget();
    } else {
      syncVisualPoseToTarget();
      applyPose(state.pose);
    }
    const active = [...state.joints.keys()].filter(id => Object.prototype.hasOwnProperty.call(state.pose, id)).length;
    const modelName = meta.model || 'Otto';
    setStatus(status, state.model ? `${modelName} · ${active} 个关节同步` : '等待模型加载');
  }

  function setJointPose(pose = {}, meta = {}) {
    state.jointPose = {...state.jointPose, ...pose};
    applyJointPose();
    const active = [...state.jointEntries.keys()].filter(id => Object.prototype.hasOwnProperty.call(state.jointPose, id)).length;
    const modelName = meta.model || 'Otto SW';
    setStatus(status, state.model ? `${modelName} · ${active} 个 SW 关节同步` : '等待模型加载');
  }

  function resetCamera() {
    focusCameraOnDisplay();
  }

  function setContactPreview(enabled) {
    state.contactKinematics?.setEnabled(enabled);
    applyPose();
  }

  function resetMotion() {
    state.contactKinematics?.reset();
    applyPose();
  }

  const loader = new GLTFLoader();
  loader.load(
    modelUrl,
    async (gltf) => {
      state.model = gltf.scene;
      motionRoot.add(state.model);
      window.__robotTwinDebug = {
        state,
        scene,
        camera,
        controls,
        getSummary() {
          const summaryRoot = state.motionRoot || state.model;
          const box = summaryRoot ? new THREE.Box3().setFromObject(summaryRoot) : null;
          const size = box ? box.getSize(new THREE.Vector3()) : null;
          const center = box ? box.getCenter(new THREE.Vector3()) : null;
          return {
            sceneChildren: scene.children.length,
            modelChildren: state.model?.children.length || 0,
            joints: [...state.joints.keys()],
            sidecarJoints: [...state.jointEntries.keys()],
            camera: camera.position.toArray(),
            target: controls.target.toArray(),
            modelBox: box ? {
              min: box.min.toArray(),
              max: box.max.toArray(),
              size: size.toArray(),
              center: center.toArray()
            } : null,
            screenOverlay: Boolean(state.screenOverlay),
            renderFps: state.renderFps,
            backgroundFps: state.backgroundFps,
            smoothServoPreview: state.smoothServoPreview,
            servoPreviewSpeedDps: state.servoPreviewSpeedDps,
            targetPose: {...state.pose},
            visualPose: {...state.visualPose},
            contact: state.contactKinematics?.summary() || null
          };
        },
        getServoJointTargets,
        getJointTargets
      };
      frameModel(state.model);
      applyScreenTexture();
      await loadJointSidecar();
      buildRuntimeJoints();
      state.contactKinematics = createContactKinematics({
        enabled: contactPreview,
        debug: contactDebug,
        footNames: ['foot-1', 'foot-2'],
        friction: 0.9,
        dynamics: true,
        tiltEnabled: false
      });
      state.contactKinematics.calibrate({ scene, model: state.model, motionRoot, sidecar: state.jointSidecar });
      if (!state.visualPoseReady) syncVisualPoseToTarget();
      applyPose();
      const names = [];
      state.model.traverse((object) => {
        if (object.name) names.push(object.name);
      });
      const sidecarStatus = state.jointSidecar
        ? ` · ${state.jointSidecar.mates?.length || 0} Mate · ${state.joints.size} 关节`
        : ' · 未加载关节 sidecar';
      setStatus(status, `Otto GLB 已加载 · ${names.length} 节点${sidecarStatus}`);
      window.dispatchEvent(new CustomEvent('robot-twin-ready'));
    },
    undefined,
    (error) => {
      console.error(error);
      setStatus(status, '3D 模型加载失败');
    }
  );

  return {
    setServoPose,
    setJointPose,
    setContactPreview,
    resetMotion,
    resetCamera,
    getServoJointTargets,
    getJointTargets,
    dispose() {
      cancelAnimationFrame(state.animationId);
      if (state.frameTimer) clearTimeout(state.frameTimer);
      resizeObserver.disconnect();
      controls.dispose();
      renderer.dispose();
      container.replaceChildren();
    }
  };
}

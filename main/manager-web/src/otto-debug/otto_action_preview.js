const SERVO_IDS = ['left_leg', 'right_leg', 'left_foot', 'right_foot', 'left_hand', 'right_hand'];
const LEFT_LEG = 0;
const RIGHT_LEG = 1;
const LEFT_FOOT = 2;
const RIGHT_FOOT = 3;
const LEFT_HAND = 4;
const RIGHT_HAND = 5;
const HAND_HOME = 45;
const BOTH = 0;
const LEFT = 1;
const RIGHT = -1;
const DEG2RAD = Math.PI / 180;

function clamp(value, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n)) return min;
  return Math.max(min, Math.min(max, n));
}

function intArg(args, name, fallback, min, max) {
  return Math.round(clamp(args?.[name] ?? fallback, min, max));
}

function poseFromArray(values) {
  return Object.fromEntries(SERVO_IDS.map((id, index) => [id, clamp(values[index], 0, 180)]));
}

function arrayFromPose(pose = {}) {
  return SERVO_IDS.map((id, index) => {
    const fallback = index === LEFT_HAND ? HAND_HOME : index === RIGHT_HAND ? 180 - HAND_HOME : 90;
    const value = Number(pose[id]);
    return Number.isFinite(value) ? value : fallback;
  });
}

function lerp(a, b, t) {
  return a + (b - a) * t;
}

function interpolatePose(from, to, t) {
  return from.map((value, index) => lerp(value, to[index], t));
}

function oscillatorPose(amplitude, offset, phase, period, localMs) {
  const theta = (2 * Math.PI * localMs) / period;
  return SERVO_IDS.map((_, index) => (
    90 + offset[index] + amplitude[index] * Math.sin(theta + phase[index])
  ));
}

function handHomeArray() {
  return [90, 90, 90, 90, HAND_HOME, 180 - HAND_HOME];
}

function homeTarget(current, handsDown) {
  const target = handHomeArray();
  if (!handsDown) {
    target[LEFT_HAND] = current[LEFT_HAND];
    target[RIGHT_HAND] = current[RIGHT_HAND];
  }
  return target;
}

class ActionPlanBuilder {
  constructor(currentPose) {
    this.current = arrayFromPose(currentPose);
    this.segments = [];
    this.durationMs = 0;
  }

  appendLinear(target, durationMs) {
    const duration = Math.max(0, Number(durationMs) || 0);
    const from = [...this.current];
    const to = [...target];
    if (duration <= 0) {
      this.current = to;
      return;
    }
    this.segments.push({ type: 'linear', startMs: this.durationMs, durationMs: duration, from, to });
    this.durationMs += duration;
    this.current = to;
  }

  appendHold(durationMs) {
    const duration = Math.max(0, Number(durationMs) || 0);
    if (duration <= 0) return;
    const pose = [...this.current];
    this.segments.push({ type: 'hold', startMs: this.durationMs, durationMs: duration, pose });
    this.durationMs += duration;
  }

  appendOscillator(amplitude, offset, phase, periodMs, steps) {
    const period = Math.max(1, Number(periodMs) || 1);
    const duration = Math.max(0, period * Math.max(0, Number(steps) || 0));
    if (duration <= 0) return;
    this.segments.push({
      type: 'oscillator',
      startMs: this.durationMs,
      durationMs: duration,
      amplitude: [...amplitude],
      offset: [...offset],
      phase: [...phase],
      periodMs: period
    });
    this.durationMs += duration;
    this.current = oscillatorPose(amplitude, offset, phase, period, duration);
  }

  appendHome(handsDown) {
    this.appendLinear(homeTarget(this.current, handsDown), 500);
    this.appendHold(200);
  }

  valueAt(elapsedMs) {
    if (!this.segments.length) return poseFromArray(this.current);
    const t = clamp(elapsedMs, 0, this.durationMs);
    const segment = this.segments.find(item => t <= item.startMs + item.durationMs) || this.segments[this.segments.length - 1];
    const local = clamp(t - segment.startMs, 0, segment.durationMs);
    if (segment.type === 'linear') {
      return poseFromArray(interpolatePose(segment.from, segment.to, segment.durationMs ? local / segment.durationMs : 1));
    }
    if (segment.type === 'oscillator') {
      return poseFromArray(oscillatorPose(segment.amplitude, segment.offset, segment.phase, segment.periodMs, local));
    }
    return poseFromArray(segment.pose);
  }
}

function buildOscillatorAction(builder, action, args) {
  const steps = intArg(args, 'steps', 3, 1, 100);
  const period = intArg(args, 'speed', 1000, 500, 1500);
  const direction = intArg(args, 'direction', 1, -1, 1) || 1;
  const amount = intArg(args, 'amount', action === 'moonwalk' ? 25 : action === 'updown' ? 20 : 30, 0, 170);
  const armSwing = intArg(args, 'arm_swing', 50, 0, 170);
  const A = [0, 0, 0, 0, 0, 0];
  const O = [0, 0, 0, 0, HAND_HOME - 90, HAND_HOME];
  const phase = [0, 0, 0, 0, 0, 0];

  if (action === 'walk_forward' || action === 'walk') {
    A[LEFT_LEG] = A[RIGHT_LEG] = A[LEFT_FOOT] = A[RIGHT_FOOT] = 30;
    O[LEFT_FOOT] = 5;
    O[RIGHT_FOOT] = -5;
    phase[LEFT_FOOT] = direction * -90 * DEG2RAD;
    phase[RIGHT_FOOT] = direction * -90 * DEG2RAD;
    A[LEFT_HAND] = armSwing;
    A[RIGHT_HAND] = armSwing;
    phase[LEFT_HAND] = phase[RIGHT_LEG];
    phase[RIGHT_HAND] = phase[LEFT_LEG];
  } else if (action === 'turn_left' || action === 'turn') {
    A[LEFT_LEG] = direction === LEFT ? 30 : 0;
    A[RIGHT_LEG] = direction === LEFT ? 0 : 30;
    A[LEFT_FOOT] = A[RIGHT_FOOT] = 30;
    O[LEFT_FOOT] = 5;
    O[RIGHT_FOOT] = -5;
    phase[LEFT_FOOT] = -90 * DEG2RAD;
    phase[RIGHT_FOOT] = -90 * DEG2RAD;
    A[LEFT_HAND] = armSwing;
    A[RIGHT_HAND] = armSwing;
    phase[LEFT_HAND] = phase[LEFT_LEG];
    phase[RIGHT_HAND] = phase[RIGHT_LEG];
  } else if (action === 'swing') {
    A[LEFT_FOOT] = amount;
    A[RIGHT_FOOT] = amount;
    O[LEFT_FOOT] = amount / 2;
    O[RIGHT_FOOT] = -amount / 2;
  } else if (action === 'moonwalk') {
    A[LEFT_FOOT] = amount;
    A[RIGHT_FOOT] = amount;
    O[LEFT_FOOT] = amount / 2 + 2;
    O[RIGHT_FOOT] = -amount / 2 - 2;
    const phi = -direction * 90;
    phase[LEFT_FOOT] = phi * DEG2RAD;
    phase[RIGHT_FOOT] = (-60 * direction + phi) * DEG2RAD;
  } else if (action === 'updown') {
    A[LEFT_FOOT] = amount;
    A[RIGHT_FOOT] = amount;
    O[LEFT_FOOT] = amount;
    O[RIGHT_FOOT] = -amount;
    phase[LEFT_FOOT] = -90 * DEG2RAD;
    phase[RIGHT_FOOT] = 90 * DEG2RAD;
  } else {
    return false;
  }

  builder.appendOscillator(A, O, phase, period, steps);
  builder.appendHome(true);
  return true;
}

function buildKeyframeAction(builder, action, args) {
  const steps = intArg(args, 'steps', 1, 1, 100);
  const period = intArg(args, 'speed', 1000, 500, 1500);
  const direction = intArg(args, 'direction', 1, -1, 1);

  if (action === 'jump') {
    builder.appendLinear([90, 90, 150, 30, HAND_HOME, 180 - HAND_HOME], period);
    builder.appendLinear(handHomeArray(), period);
    builder.appendHome(true);
    return true;
  }

  if (action === 'bend') {
    let bend1 = [90, 90, 62, 35, HAND_HOME, 180 - HAND_HOME];
    let bend2 = [90, 90, 62, 105, HAND_HOME, 180 - HAND_HOME];
    if (direction === RIGHT) {
      bend1 = [90, 90, 180 - 35, 180 - 60, HAND_HOME, 180 - HAND_HOME];
      bend2 = [90, 90, 180 - 105, 180 - 60, HAND_HOME, 180 - HAND_HOME];
    }
    for (let i = 0; i < steps; i++) {
      builder.appendLinear(bend1, 400);
      builder.appendLinear(bend2, 400);
      builder.appendHold(period * 0.8);
      builder.appendLinear(handHomeArray(), 500);
    }
    builder.appendHome(true);
    return true;
  }

  if (action === 'shake_leg') {
    let p1 = [90, 90, 58, 35, HAND_HOME, 180 - HAND_HOME];
    let p2 = [90, 90, 58, 120, HAND_HOME, 180 - HAND_HOME];
    let p3 = [90, 90, 58, 60, HAND_HOME, 180 - HAND_HOME];
    if (direction === RIGHT) {
      p1 = [90, 90, 180 - 35, 180 - 58, HAND_HOME, 180 - HAND_HOME];
      p2 = [90, 90, 180 - 120, 180 - 58, HAND_HOME, 180 - HAND_HOME];
      p3 = [90, 90, 180 - 60, 180 - 58, HAND_HOME, 180 - HAND_HOME];
    }
    const moves = 2;
    const shakePeriod = Math.max(period - 1000, 200 * moves);
    for (let j = 0; j < steps; j++) {
      builder.appendLinear(p1, 500);
      builder.appendLinear(p2, 500);
      for (let i = 0; i < moves; i++) {
        builder.appendLinear(p3, shakePeriod / (2 * moves));
        builder.appendLinear(p2, shakePeriod / (2 * moves));
      }
      builder.appendLinear(handHomeArray(), 500);
    }
    builder.appendHold(shakePeriod);
    builder.appendHome(true);
    return true;
  }

  if (action === 'hands_up') {
    const target = handHomeArray();
    if (direction === BOTH) {
      target[LEFT_HAND] = 170;
      target[RIGHT_HAND] = 10;
    } else if (direction === LEFT) {
      target[LEFT_HAND] = 170;
      target[RIGHT_HAND] = builder.current[RIGHT_HAND];
    } else if (direction === RIGHT) {
      target[RIGHT_HAND] = 10;
      target[LEFT_HAND] = builder.current[LEFT_HAND];
    }
    builder.appendLinear(target, period);
    builder.appendHome(false);
    return true;
  }

  if (action === 'hands_down') {
    const target = handHomeArray();
    if (direction === LEFT) target[RIGHT_HAND] = builder.current[RIGHT_HAND];
    if (direction === RIGHT) target[LEFT_HAND] = builder.current[LEFT_HAND];
    builder.appendLinear(target, period);
    builder.appendHome(false);
    return true;
  }

  if (action === 'hand_wave') {
    if (direction === BOTH) {
      let target = [...builder.current];
      target[LEFT_HAND] = 170;
      target[RIGHT_HAND] = 10;
      builder.appendLinear(target, 300);
      for (let i = 0; i < 5; i++) {
        target = [...target];
        target[LEFT_HAND] = 140;
        target[RIGHT_HAND] = 40;
        builder.appendLinear(target, period / 10);
        target = [...target];
        target[LEFT_HAND] = 200;
        target[RIGHT_HAND] = -20;
        builder.appendLinear(target, period / 10);
      }
      target = [...target];
      target[LEFT_HAND] = HAND_HOME;
      target[RIGHT_HAND] = 180 - HAND_HOME;
      builder.appendLinear(target, 300);
      builder.appendHome(false);
      return true;
    }

    const servoIndex = direction === LEFT ? LEFT_HAND : RIGHT_HAND;
    const raised = servoIndex === LEFT_HAND ? 170 : 10;
    let target = [...builder.current];
    target[servoIndex] = raised;
    builder.appendLinear(target, 300);
    builder.appendHold(300);
    for (let i = 0; i < 5; i++) {
      target = [...target];
      target[servoIndex] = servoIndex === LEFT_HAND ? raised - 30 : raised + 30;
      builder.appendLinear(target, period / 10);
      builder.appendHold(period / 10);
      target = [...target];
      target[servoIndex] = servoIndex === LEFT_HAND ? raised + 30 : raised - 30;
      builder.appendLinear(target, period / 10);
      builder.appendHold(period / 10);
    }
    target = [...target];
    target[servoIndex] = servoIndex === LEFT_HAND ? HAND_HOME : 180 - HAND_HOME;
    builder.appendLinear(target, 300);
    builder.appendHome(false);
    return true;
  }

  if (action === 'home') {
    builder.appendHome(true);
    return true;
  }

  return false;
}

export function buildOttoActionPlan(action, args = {}, currentPose = {}) {
  const actionId = String(action || '').trim();
  const builder = new ActionPlanBuilder(currentPose);
  const ok = buildOscillatorAction(builder, actionId, args) || buildKeyframeAction(builder, actionId, args);
  if (!ok || builder.durationMs <= 0) return null;
  return {
    action: actionId,
    durationMs: builder.durationMs,
    valueAt: elapsedMs => builder.valueAt(elapsedMs)
  };
}

export function createOttoActionPreviewPlayer({ getCurrentPose, getCatalog, applyPose, onStatus } = {}) {
  let timer = 0;
  let activePlan = null;
  let startedAt = 0;

  function stop() {
    if (timer) window.clearTimeout(timer);
    timer = 0;
    activePlan = null;
  }

  function tick() {
    if (!activePlan) return;
    const elapsed = performance.now() - startedAt;
    applyPose?.(activePlan.valueAt(elapsed), {
      model: `Otto plan: ${activePlan.action}`,
      immediate: true,
      snapContact: true
    });
    if (elapsed >= activePlan.durationMs) {
      const action = activePlan.action;
      stop();
      onStatus?.(`动作预览完成: ${action}`, 'ok');
      return;
    }
    timer = window.setTimeout(tick, 33);
  }

  function playAction(action, args = {}) {
    const plan = buildOttoActionPlan(action, args, getCurrentPose?.() || {});
    if (!plan) return null;
    stop();
    activePlan = plan;
    startedAt = performance.now();
    applyPose?.(plan.valueAt(0), {
      model: `Otto plan: ${plan.action}`,
      immediate: true,
      snapContact: true
    });
    timer = window.setTimeout(tick, 33);
    onStatus?.(`按固件动作计划预览: ${plan.action} · ${Math.round(plan.durationMs)}ms`, 'ok');
    return plan;
  }

  return {
    playAction,
    stop,
    isPlaying() {
      return Boolean(activePlan);
    },
    currentPlan() {
      return activePlan;
    },
    getCatalog
  };
}

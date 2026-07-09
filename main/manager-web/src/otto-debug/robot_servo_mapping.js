export const FIRMWARE_SERVO_PROTOCOL = Object.freeze({
  left_leg: { servoHomeDeg: 90, servoMinDeg: 0, servoMaxDeg: 180, servoToJointSign: 1 },
  right_leg: { servoHomeDeg: 90, servoMinDeg: 0, servoMaxDeg: 180, servoToJointSign: 1 },
  left_foot: { servoHomeDeg: 90, servoMinDeg: 0, servoMaxDeg: 180, servoToJointSign: 1 },
  right_foot: { servoHomeDeg: 90, servoMinDeg: 0, servoMaxDeg: 180, servoToJointSign: 1 },
  left_hand: { servoHomeDeg: 45, servoMinDeg: 0, servoMaxDeg: 180, servoToJointSign: 1 },
  right_hand: { servoHomeDeg: 135, servoMinDeg: 0, servoMaxDeg: 180, servoToJointSign: 1 }
});

// UI and firmware use the robot's own left/right from the screen-facing front.
// Sidecar joint IDs stay CAD component based.
export const FIRMWARE_SERVO_TO_SIDECAR_JOINT = Object.freeze({
  left_leg: 'body_to_leg_6',
  right_leg: 'body_to_leg_5',
  left_foot: 'leg_6_to_foot_2',
  right_foot: 'leg_5_to_foot_1',
  left_hand: 'body_to_leg_2',
  right_hand: 'body_to_leg_1'
});

// Viewer-only compensation for the current physical build.
// This does not write device NVS and does not change firmware motion semantics.
export const WEB_PREVIEW_SERVO_TO_JOINT_SIGN = Object.freeze({
  left_leg: 1,
  right_leg: -1,
  left_foot: -1,
  right_foot: 1,
  left_hand: -1,
  right_hand: 1
});

export function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

export function getFiniteNumber(value, fallback = null) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

export function boolValue(value, fallback = false) {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  if (typeof value === 'string') return /^(1|true|yes|on|reverse|reversed)$/i.test(value.trim());
  return fallback;
}

export function servoNumberField(servo, names, fallback) {
  for (const name of names) {
    const value = getFiniteNumber(servo?.[name], null);
    if (value !== null) return value;
  }
  return fallback;
}

export function servoTextField(servo, names, fallback = '') {
  for (const name of names) {
    const value = servo?.[name];
    if (value !== undefined && value !== null && value !== '') return String(value);
  }
  return fallback;
}

export function servoMappingConfig(servo = {}, entry = {}) {
  const servoHomeDeg = servoNumberField(servo, ['home', 'homeAngle', 'home_angle'], entry.defaultServoHomeDeg ?? 90);
  const commandMinDeg = servoNumberField(servo, ['minAngle', 'min_angle'], entry.defaultServoMinDeg ?? 0);
  const commandMaxDeg = servoNumberField(servo, ['maxAngle', 'max_angle'], entry.defaultServoMaxDeg ?? 180);
  const safeMinDeg = Math.min(commandMinDeg, commandMaxDeg);
  const safeMaxDeg = Math.max(commandMinDeg, commandMaxDeg);
  const protocolMinDeg = entry.defaultServoMinDeg ?? 0;
  const protocolMaxDeg = entry.defaultServoMaxDeg ?? 180;
  const protocolSafeMinDeg = Math.min(protocolMinDeg, protocolMaxDeg);
  const protocolSafeMaxDeg = Math.max(protocolMinDeg, protocolMaxDeg);
  const reverse = boolValue(servo?.reverse, false);
  const trimDeg = servoNumberField(servo, ['trim', 'trimDeg', 'trim_deg', 'trimAngle', 'trim_angle'], 0);
  const physicalServo = servoTextField(servo, ['physical_servo', 'physicalServo', 'physical', 'physical_id'], entry.servoId || servo.id || '');

  return {
    servoHomeDeg,
    safeMinDeg,
    safeMaxDeg,
    protocolMinDeg,
    protocolMaxDeg,
    protocolSafeMinDeg,
    protocolSafeMaxDeg,
    reverse,
    trimDeg,
    physicalTrimDeg: reverse ? -trimDeg : trimDeg,
    physicalServo
  };
}

export function logicalToPhysicalServoAngle(logicalDeg, config) {
  return config.reverse ? 180 - logicalDeg : logicalDeg;
}

export function physicalToLogicalServoAngle(physicalDeg, config) {
  return config.reverse ? 180 - physicalDeg : physicalDeg;
}

export function mapServoCommand(servoAngle, servo = {}, entry = {}) {
  const config = servoMappingConfig(servo, entry);
  const requestedDeg = getFiniteNumber(servoAngle, config.servoHomeDeg);
  const safeLogicalDeg = clamp(requestedDeg, config.safeMinDeg, config.safeMaxDeg);
  const logicalDeg = clamp(safeLogicalDeg, config.protocolSafeMinDeg, config.protocolSafeMaxDeg);
  const physicalDeg = logicalToPhysicalServoAngle(logicalDeg, config);
  const pwmDeg = clamp(physicalDeg + config.physicalTrimDeg, 0, 180);

  return {
    ...config,
    requestedDeg,
    safeLogicalDeg,
    logicalDeg,
    physicalDeg,
    pwmDeg,
    servoDeltaDeg: logicalDeg - config.servoHomeDeg
  };
}

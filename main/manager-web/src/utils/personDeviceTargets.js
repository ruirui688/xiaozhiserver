function text(value) {
  return String(value || "").trim();
}

export function deviceDisplayName(device = {}) {
  const alias = text(device.alias);
  if (alias) return alias;
  const name = text(device.name);
  const macAddress = text(device.macAddress);
  if (name && name.toLowerCase() !== macAddress.toLowerCase()) return name;
  return "未命名设备";
}

export function deviceAgentName(device = {}) {
  return text(device.agentName) || "未绑定智能体";
}

export function deviceSearchText(device = {}) {
  return [
    deviceDisplayName(device),
    device.alias,
    device.name,
    device.macAddress,
    device.board,
    device.appVersion,
    device.agentName
  ].map(text).join(" ").toLowerCase();
}

export function matchesDeviceTarget(device = {}, keyword = "", agentFilter = "all") {
  const normalizedKeyword = text(keyword).toLowerCase();
  if (normalizedKeyword && !deviceSearchText(device).includes(normalizedKeyword)) {
    return false;
  }
  if (agentFilter === "all") return true;
  if (agentFilter === "unbound") return !text(device.agentId);
  return text(device.agentId) === agentFilter;
}

export function sortDeviceTargets(devices = []) {
  return [...devices].sort((left, right) => {
    const aliasRank = Number(!text(left.alias)) - Number(!text(right.alias));
    if (aliasRank !== 0) return aliasRank;
    return deviceDisplayName(left).localeCompare(deviceDisplayName(right), "zh-CN")
      || text(left.macAddress).localeCompare(text(right.macAddress));
  });
}

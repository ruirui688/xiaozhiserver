// Factors are generated in the employee's browser and never placed in URLs or storage.
export function generateTotpSecret() {
  if (!globalThis.crypto?.getRandomValues) throw new Error('请使用 HTTPS 浏览器设置身份验证器');
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(20));
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let buffer = 0, bits = 0, result = '';
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte; bits += 8;
    while (bits >= 5) { bits -= 5; result += alphabet[(buffer >>> bits) & 31]; }
  }
  return result;
}

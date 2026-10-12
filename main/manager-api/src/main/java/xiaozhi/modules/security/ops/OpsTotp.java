package xiaozhi.modules.security.ops;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 six-digit TOTP; the consumed step is atomically checked by the caller. */
public final class OpsTotp {
    private OpsTotp() {}
    public static long verify(String secret, String supplied, long epochSeconds, long lastStep) {
        if (supplied == null || !supplied.matches("[0-9]{6}")) return -1;
        for (long step = epochSeconds / 30 - 1; step <= epochSeconds / 30 + 1; step++) {
            if (step > lastStep && MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII))) return step;
        }
        return -1;
    }
    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = digest[digest.length - 1] & 15;
            int binary = ((digest[offset] & 127) << 24) | ((digest[offset+1] & 255) << 16)
                    | ((digest[offset+2] & 255) << 8) | (digest[offset+3] & 255);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1000000);
        } catch (Exception e) { throw new IllegalArgumentException("员工验证器配置无效", e); }
    }
    static byte[] decode(String value) {
        if (value == null || !value.matches("[A-Z2-7]{32,128}")) throw new IllegalArgumentException("TOTP密钥须为至少160位Base32值");
        var result = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : value.toCharArray()) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            bits += 5;
            if (bits >= 8) { bits -= 8; result.write((buffer >> bits) & 255); }
        }
        return result.toByteArray();
    }
}

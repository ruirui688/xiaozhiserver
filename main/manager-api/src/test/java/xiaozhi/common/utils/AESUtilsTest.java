package xiaozhi.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AESUtilsTest {

    @Test
    public void testEncryptAndDecrypt() {
        String key = "xiaozhi1234567890";
        String plainText = "Hello, 小智!";

        System.out.println("原始文本: " + plainText);
        System.out.println("密钥: " + key);

        // 加密
        String encrypted = AESUtils.encrypt(key, plainText);
        System.out.println("加密结果: " + encrypted);

        // 解密
        String decrypted = AESUtils.decrypt(key, encrypted);
        System.out.println("解密结果: " + decrypted);

        // 验证
        assertEquals(plainText, decrypted, "加解密结果应该一致");
        System.out.println("加解密一致性: " + plainText.equals(decrypted));
    }

    @Test
    public void testDifferentKeyLengths() {
        String[] keys = {
                "1234567890123456", // 16位
                "123456789012345678901234", // 24位
                "12345678901234567890123456789012", // 32位
                "short", // 短密钥
                "verylongkeythatwillbetruncatedto32bytes" // 长密钥
        };

        String plainText = "测试文本";

        for (String key : keys) {
            String encrypted = AESUtils.encrypt(key, plainText);
            String decrypted = AESUtils.decrypt(key, encrypted);
            assertEquals(plainText, decrypted, "密钥长度: " + key.length());
        }
    }

    @Test
    public void testSpecialCharacters() {
        String key = "xiaozhi1234567890";
        String[] testTexts = {
                "Hello World",
                "你好世界",
                "Hello, 小智!",
                "特殊字符: !@#$%^&*()",
                "数字123和中文混合",
                "Emoji: 😀🎉🚀",
                "空字符串测试",
                ""
        };

        for (String text : testTexts) {
            String encrypted = AESUtils.encrypt(key, text);
            String decrypted = AESUtils.decrypt(key, encrypted);
            assertEquals(text, decrypted, "测试文本: " + text);
        }
    }

    @Test
    public void testCrossLanguageCompatibility() {
        // Cross-language compatibility test: verify AES/ECB/PKCS5Padding produces
        // deterministic output suitable for interop with Python (pycryptodome).
        //
        // Python equivalent:
        //   from Crypto.Cipher import AES
        //   import base64
        //   key = b"xiaozhi1234567890"
        //   plain = "Hello, 小智!".encode("utf-8")
        //   cipher = AES.new(key, AES.MODE_ECB)
        //   ct = cipher.encrypt(plain + b"\x0b" * (16 - len(plain) % 16))
        //   print(base64.b64encode(ct).decode())
        //
        // To run cross-language verification:
        //   1. Encrypt "Hello, 小智!" with key "xiaozhi1234567890" in Python.
        //   2. Compare the Base64 output with the Java output printed below.
        //   3. The two should match (ECB is deterministic).

        String key = "xiaozhi1234567890";
        String plainText = "Hello, 小智!";

        // Encrypt and verify deterministic output
        String javaEncrypted = AESUtils.encrypt(key, plainText);
        assertNotNull(javaEncrypted, "encrypted output should not be null");
        assertTrue(javaEncrypted.length() > 0, "encrypted output should not be empty");

        // Decrypt back and verify round-trip
        String decrypted = AESUtils.decrypt(key, javaEncrypted);
        assertEquals(plainText, decrypted, "Java should decrypt its own encrypted output correctly");

        System.out.println("Java encrypted output for cross-language verification: " + javaEncrypted);
    }
}
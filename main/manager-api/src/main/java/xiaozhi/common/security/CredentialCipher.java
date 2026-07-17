package xiaozhi.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import xiaozhi.common.exception.RenException;

@Component
public class CredentialCipher {
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final String masterKey;
    private final SecureRandom secureRandom;

    @Autowired
    public CredentialCipher(
            @Value("${xiaozhi.security.credential-encryption-key:}") String masterKey) {
        this(masterKey, new SecureRandom());
    }

    CredentialCipher(String masterKey, SecureRandom secureRandom) {
        this.masterKey = StringUtils.trimToEmpty(masterKey);
        this.secureRandom = secureRandom;
    }

    public String encrypt(String scope, String plaintext) {
        requireConfigured();
        if (StringUtils.isBlank(scope) || StringUtils.isBlank(plaintext)) {
            throw new RenException("第三方凭据或加密作用域不能为空");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            throw new RenException("第三方凭据加密失败", e);
        }
    }

    public String decrypt(String scope, String ciphertext) {
        requireConfigured();
        if (StringUtils.isBlank(scope) || StringUtils.isBlank(ciphertext) || !ciphertext.startsWith(PREFIX)) {
            throw new RenException("第三方凭据密文格式无效");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new RenException("第三方凭据密文格式无效");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            throw new RenException("第三方凭据解密失败，请检查部署加密密钥是否发生变化", e);
        }
    }

    private SecretKeySpec key() throws Exception {
        return new SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8)),
                "AES");
    }

    private void requireConfigured() {
        if (masterKey.length() < 32) {
            throw new RenException("未配置 XIAOZHI_CREDENTIAL_ENCRYPTION_KEY 或长度不足32个字符");
        }
    }
}

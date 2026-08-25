package xiaozhi.modules.knowledge.source.connector;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.common.exception.RenException;

/** SSRF guard for administrator-configured public knowledge sources. */
public final class PublicUrlGuard {
    private static final Set<String> SENSITIVE_QUERY_KEYS = Set.of(
            "key", "api_key", "apikey", "token", "access_token", "secret", "password", "authorization");

    private PublicUrlGuard() {
    }

    public static URI requirePublicHttpUrl(String rawUrl) {
        try {
            URI uri = new URI(StringUtils.trimToEmpty(rawUrl));
            String scheme = StringUtils.lowerCase(uri.getScheme());
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new RenException("动态知识数据源只允许 http/https 地址");
            }
            if (StringUtils.isBlank(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new RenException("动态知识数据源地址格式无效");
            }
            rejectSecretsInQuery(uri.getRawQuery());
            validatePublicTarget(uri.getHost());
            return uri;
        } catch (RenException e) {
            throw e;
        } catch (URISyntaxException e) {
            throw new RenException("动态知识数据源地址格式无效", e);
        }
    }

    private static void rejectSecretsInQuery(String rawQuery) {
        if (StringUtils.isBlank(rawQuery)) {
            return;
        }
        for (String pair : rawQuery.split("&")) {
            String key = StringUtils.substringBefore(pair, "=").toLowerCase(Locale.ROOT);
            if (SENSITIVE_QUERY_KEYS.contains(key)) {
                throw new RenException("数据源地址禁止通过 URL query 传递密钥或 Token");
            }
        }
    }

    private static void validatePublicTarget(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new RenException("动态知识数据源域名无法解析");
            }
            for (InetAddress address : addresses) {
                if (isForbiddenAddress(address)) {
                    throw new RenException("动态知识数据源禁止访问本机、内网或保留地址");
                }
            }
        } catch (UnknownHostException e) {
            throw new RenException("动态知识数据源域名无法解析", e);
        }
    }

    static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19 || second == 51))
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224;
        }
        return address instanceof Inet6Address
                && ((Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc
                        || (bytes.length > 11
                                && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0
                                && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0
                                && bytes[8] == 0 && bytes[9] == 0
                                && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff));
    }
}

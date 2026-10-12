package xiaozhi.modules.platform.service;

import java.util.*;
import xiaozhi.common.exception.RenException;

/** Public catalog fields cannot be a second credential store, including nested JSON. */
public final class PlatformCatalogSecrets {
    private PlatformCatalogSecrets() {}
    public static boolean secret(String key) {
        String normalized=key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
        return Set.of("apikey","xapikey","apipassword","accesstoken","token","secretkey","apisecret","accesskeysecret","personalaccesstoken","accesskey","appid","appkey","password","authorization","privatekey","llmapikey","speechapikey").contains(normalized);
    }
    public static void validate(Object value) {
        if(value instanceof Map<?,?> map)map.forEach((key,item)->{
            if(secret(key.toString())&&item!=null&&!item.toString().isBlank()&&!placeholder(item.toString()))throw new RenException("模型目录不接受密钥，请在平台凭据库分配");
            if(!secret(key.toString()))validate(item);
        });else if(value instanceof Collection<?> list)list.forEach(PlatformCatalogSecrets::validate);
    }
    public static boolean placeholder(String value) {return "PLATFORM_MANAGED".equals(value)||value.contains("***");}
    public static Object publicValue(Object value) {
        if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->{if(!secret(key.toString()))result.put(key.toString(),publicValue(item));});return result;}
        if(value instanceof Collection<?> list)return list.stream().map(PlatformCatalogSecrets::publicValue).toList();return value;
    }
}

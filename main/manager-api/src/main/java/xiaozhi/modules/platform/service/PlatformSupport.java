package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.service.BillingSupport;

public final class PlatformSupport {
    private PlatformSupport() {}
    public static LocalDateTime now() { return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC); }
    public static String id() { return UUID.randomUUID().toString(); }
    public static BigDecimal amount(Object value) {
        try {
            BigDecimal result = new BigDecimal(String.valueOf(value));
            if (result.precision() > 30 || result.scale() > 9 || result.abs().compareTo(new BigDecimal("1000000000000")) > 0) throw new ArithmeticException();
            return result.setScale(9, RoundingMode.UNNECESSARY);
        } catch (RuntimeException e) { throw new RenException("INVALID_AMOUNT"); }
    }
    public static BigDecimal nonnegative(Object value) { BigDecimal result=amount(value); if(result.signum()<0)throw new RenException("INVALID_AMOUNT");return result; }
    public static BigDecimal positive(Object value) { BigDecimal result=nonnegative(value);if(result.signum()==0)throw new RenException("INVALID_AMOUNT");return result; }
    public static String decimal(Object value) { return amount(value).stripTrailingZeros().toPlainString(); }
    public static long number(Object value) { return ((Number)value).longValue(); }
    public static String required(String value,int max) { if(value==null||value.isBlank()||value.length()>max)throw new RenException("INVALID_REQUEST");return value.trim(); }
    public static String requestId(String value) { if(value==null||!value.matches("[A-Za-z0-9_.:-]{8,128}"))throw new RenException("INVALID_REQUEST_ID");return value; }
    public static Map<String,Object> map(Object... values) { Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result; }
    public static Object publicValue(Object value) {
        if(value instanceof BigDecimal amount)return amount.toPlainString();
        if(value instanceof Map<?,?> values){Map<String,Object> result=new LinkedHashMap<>();values.forEach((key,item)->result.put(key.toString(),publicValue(item)));return result;}
        if(value instanceof Collection<?> values)return values.stream().map(PlatformSupport::publicValue).toList();
        return value;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> publicMap(Map<String,Object> value){return (Map<String,Object>)publicValue(value);}
    public static String hash(String value) { return BillingSupport.hash(value); }
}

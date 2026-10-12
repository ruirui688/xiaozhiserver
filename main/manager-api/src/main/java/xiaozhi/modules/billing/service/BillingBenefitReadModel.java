package xiaozhi.modules.billing.service;

import java.util.*;

/** Multiple supplier observations of the same purchased instance are not additional assets. */
public final class BillingBenefitReadModel {
    private BillingBenefitReadModel() { }
    public static List<Map<String,Object>> assets(List<Map<String,Object>> observations) {
        Map<String,List<Map<String,Object>>> groups=new LinkedHashMap<>();int ordinal=0;
        for(var row:observations) {
            String provider=canonicalProvider(Objects.toString(row.get("provider"),""));
            String instance=Objects.toString(row.get("instanceNo"),"");
            String key=instance.isBlank()?"observation:"+(ordinal++):"instance:"+part(provider)+part(Objects.toString(row.get("accountRef"),""))+part(instance);
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(new LinkedHashMap<>(row));
        }
        List<Map<String,Object>> result=new ArrayList<>();
        groups.forEach((key,sources)->{
            Map<String,Object> asset=new LinkedHashMap<>(sources.getFirst());
            asset.put("assetId",BillingSupport.hash(key));asset.put("provider",canonicalProvider(Objects.toString(asset.get("provider"),"")));
            Set<String> kinds=new HashSet<>();sources.forEach(row->kinds.add(category(row)));kinds.remove("UNKNOWN");
            asset.put("category",kinds.size()==1?kinds.iterator().next():"UNKNOWN");
            asset.put("sourceObservations",sources);asset.put("deductionStatus","UNVERIFIED");
            Map<String,Map<String,Object>> alerts=new LinkedHashMap<>();
            for(var source:sources)if(source.get("alerts") instanceof List<?> items)for(Object item:items)
                if(item instanceof Map<?,?> alert){Map<String,Object> value=new LinkedHashMap<>();alert.forEach((k,v)->value.put(k.toString(),v));alerts.putIfAbsent(Objects.toString(value.get("code"),""),value);}
            asset.put("alerts",new ArrayList<>(alerts.values()));result.add(asset);
        });return result;
    }
    public static String canonicalProvider(String provider) { return "VOLCENGINE_SPEECH".equals(provider)?"VOLCENGINE":provider; }
    private static String part(String value) { return value.length()+":"+value; }
    private static String category(Map<String,Object> row) {
        String type=Objects.toString(row.get("packageType"),"").toLowerCase(Locale.ROOT);
        if("access".equals(type))return "ENTITLEMENT";
        if(Set.of("prepaid","quota","periodic","diminishing").contains(type) || row.get("totalAmount")!=null || row.get("availableAmount")!=null)return "QUOTA";
        return "UNKNOWN";
    }
}

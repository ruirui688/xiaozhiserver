package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BillingBenefitReadModelTest {
    @Test void stablePurchasedInstanceMergesSourcesWithoutAddingQuota() {
        var generic=observation("VOLCENGINE","account","instance","Diminishing");
        generic.put("totalAmount","100000");generic.put("availableAmount","75000");
        var speech=observation("VOLCENGINE_SPEECH","account","instance","prepaid");
        speech.put("purchasedAmount","100000");speech.put("currentUsage","25000");
        generic.put("alerts",List.of(Map.of("code","EXPIRING","message","ending")));
        speech.put("alerts",List.of(Map.of("code","EXPIRING","message","ending")));
        var assets=BillingBenefitReadModel.assets(List.of(generic,speech));assertEquals(1,assets.size());
        var asset=assets.getFirst();assertEquals("QUOTA",asset.get("category"));
        assertEquals("100000",asset.get("totalAmount"));assertEquals("75000",asset.get("availableAmount"));
        assertEquals(2,((List<?>)asset.get("sourceObservations")).size());assertEquals(1,((List<?>)asset.get("alerts")).size());
        assertEquals("UNVERIFIED",asset.get("deductionStatus"));
    }
    @Test void accountAndProviderArePartOfIdentityAndDelimitedValuesCannotCollide() {
        var assets=BillingBenefitReadModel.assets(List.of(
                observation("VOLCENGINE","account","instance","prepaid"),
                observation("VOLCENGINE","account-b","instance","prepaid"),
                observation("OTHER","account","instance","prepaid"),
                observation("VOLCENGINE","a:b","c","prepaid"),
                observation("VOLCENGINE","a","b:c","prepaid")));
        assertEquals(5,assets.size());assertEquals(5,assets.stream().map(row->row.get("assetId")).distinct().count());
    }
    @Test void missingInstanceNeverMergesByNameOrResourceAndAccessIsAnEntitlement() {
        var first=observation("VOLCENGINE_SPEECH","account",null,"access");
        first.put("name","shared voice");first.put("resourceId","tts");first.put("purchasedAmount","1");
        var second=new LinkedHashMap<>(first);
        var assets=BillingBenefitReadModel.assets(List.of(first,second));
        assertEquals(2,assets.size());assertTrue(assets.stream().allMatch(row->"ENTITLEMENT".equals(row.get("category"))));
        assertNotEquals(assets.get(0).get("assetId"),assets.get(1).get("assetId"));
        assertFalse(assets.getFirst().containsKey("availableAmount"));
    }
    private Map<String,Object> observation(String provider,String account,String instance,String type) {
        var row=new LinkedHashMap<String,Object>();row.put("provider",provider);row.put("accountRef",account);
        row.put("instanceNo",instance);row.put("packageType",type);return row;
    }
}

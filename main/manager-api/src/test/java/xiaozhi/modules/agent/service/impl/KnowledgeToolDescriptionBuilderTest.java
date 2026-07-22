package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;

class KnowledgeToolDescriptionBuilderTest {

    @Test
    void buildsDomainNeutralDescriptionWithoutUnrelatedProductRules() {
        KnowledgeBaseEntity knowledgeBase = knowledgeBase("dataset-1", "工厂介绍");
        String description = KnowledgeToolDescriptionBuilder.build(
                Map.of("dataset-1", List.of("知识库内容范围：生产线与设备手册")),
                List.of(knowledgeBase));

        assertTrue(description.contains("工厂介绍"));
        assertTrue(description.contains("生产线与设备手册"));
        assertTrue(description.contains("用户当前问题"));
        assertFalse(description.contains("酒类"));
        assertFalse(description.contains("酒杯"));
    }

    @Test
    void boundsDescriptionWhenDatasetsAndPoliciesAreLarge() {
        List<KnowledgeBaseEntity> knowledgeBases = new ArrayList<>();
        Map<String, List<String>> instructions = new LinkedHashMap<>();
        for (int index = 0; index < 30; index++) {
            String datasetId = "dataset-" + index;
            knowledgeBases.add(knowledgeBase(datasetId, index + "-" + "超长知识库名称".repeat(8)));
            instructions.put(datasetId, List.of(
                    "知识库内容范围：" + "范围说明".repeat(80),
                    "查询词生成规则：" + "查询规则".repeat(80)));
        }

        String description = KnowledgeToolDescriptionBuilder.build(instructions, knowledgeBases);

        assertTrue(description.length() <= KnowledgeToolDescriptionBuilder.MAX_DESCRIPTION_CHARS);
        assertTrue(description.contains("等30个知识库"));
        assertTrue(description.contains("不得擅自补充对象、品类或场景"));
    }

    @Test
    void handlesMissingMetadataDeterministically() {
        String description = KnowledgeToolDescriptionBuilder.build(null, List.of());

        assertTrue(description.contains("未命名知识库"));
        assertTrue(description.contains("question"));
        assertTrue(description.length() <= KnowledgeToolDescriptionBuilder.MAX_DESCRIPTION_CHARS);
    }

    private KnowledgeBaseEntity knowledgeBase(String datasetId, String name) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setDatasetId(datasetId);
        entity.setName(name);
        return entity;
    }
}

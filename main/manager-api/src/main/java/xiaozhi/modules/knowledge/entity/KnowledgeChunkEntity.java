package xiaozhi.modules.knowledge.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName(value = "ai_rag_knowledge_chunk", autoResultMap = true)
@Schema(description = "本地知识库切片表")
public class KnowledgeChunkEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    @Schema(description = "本地唯一ID")
    private String id;

    @Schema(description = "知识库ID")
    private String datasetId;

    @Schema(description = "文档ID")
    private String documentId;

    @Schema(description = "切片ID")
    private String chunkId;

    @Schema(description = "切片内容")
    private String content;

    @Schema(description = "问答模式的问题")
    private String question;

    @Schema(description = "问答模式的答案")
    private String answer;

    @Schema(description = "切片排序")
    private Integer sortOrder;

    @Schema(description = "粗略Token数")
    private Long tokenCount;

    @Schema(description = "文本嵌入向量（JSON数组），用于本地知识库向量相似度检索")
    private String embeddingJson;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.UPDATE)
    private Date updatedAt;
}

package xiaozhi.modules.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_model_benchmark_task")
@Schema(description = "模型专业评测任务")
public class ModelBenchmarkTaskEntity {

    @TableId
    private String id;

    private String modelType;

    private String modelIds;

    private Integer iterations;

    private Integer concurrency;

    private String status;

    private Integer progress;

    private String summary;

    private String errorMessage;

    private Date startedAt;

    private Date finishedAt;

    private Long durationMs;

    private String metricNotes;

    @TableField(fill = FieldFill.INSERT)
    private Long creator;

    @TableField(fill = FieldFill.INSERT)
    private Date createDate;

    @TableField(fill = FieldFill.UPDATE)
    private Long updater;

    @TableField(fill = FieldFill.UPDATE)
    private Date updateDate;
}

package xiaozhi.modules.device.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.device.entity.OtaEntity;

/**
 * OTA固件管理
 */
@Mapper
public interface OtaDao extends BaseMapper<OtaEntity> {
    @org.apache.ibatis.annotations.Select("SELECT o.* FROM ai_ota o JOIN ops_ota_channel c ON c.ota_id=o.id WHERE c.hardware_type=#{type}")
    OtaEntity selectPublishedChannel(@org.apache.ibatis.annotations.Param("type") String type);
}

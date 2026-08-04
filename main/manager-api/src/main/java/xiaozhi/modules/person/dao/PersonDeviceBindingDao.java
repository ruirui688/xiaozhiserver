package xiaozhi.modules.person.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.person.entity.PersonDeviceBindingEntity;

@Mapper
public interface PersonDeviceBindingDao extends BaseMapper<PersonDeviceBindingEntity> {
}

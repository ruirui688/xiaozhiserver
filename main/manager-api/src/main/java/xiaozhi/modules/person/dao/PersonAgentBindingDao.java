package xiaozhi.modules.person.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.person.entity.PersonAgentBindingEntity;

@Mapper
public interface PersonAgentBindingDao extends BaseMapper<PersonAgentBindingEntity> {
}

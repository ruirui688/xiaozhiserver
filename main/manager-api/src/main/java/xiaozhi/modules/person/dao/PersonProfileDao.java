package xiaozhi.modules.person.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.person.entity.PersonProfileEntity;

@Mapper
public interface PersonProfileDao extends BaseMapper<PersonProfileEntity> {
}

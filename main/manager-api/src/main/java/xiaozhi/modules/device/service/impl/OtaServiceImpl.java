package xiaozhi.modules.device.service.impl;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import io.micrometer.common.util.StringUtils;
import xiaozhi.common.page.PageData;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.modules.device.dao.OtaDao;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.service.OtaService;

@Service
public class OtaServiceImpl extends BaseServiceImpl<OtaDao, OtaEntity> implements OtaService {

    @Override
    public PageData<OtaEntity> page(Map<String, Object> params) {
        IPage<OtaEntity> page = baseDao.selectPage(
                getPage(params, "update_date", true),
                getWrapper(params));

        return new PageData<>(page.getRecords(), page.getTotal());
    }

    private QueryWrapper<OtaEntity> getWrapper(Map<String, Object> params) {
        String firmwareName = (String) params.get("firmwareName");

        QueryWrapper<OtaEntity> wrapper = new QueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(firmwareName), "firmware_name", firmwareName);

        return wrapper;
    }

    @Override
    public void update(OtaEntity entity) {
        throw new xiaozhi.common.exception.RenException("已发布固件不可原地修改");
    }

    @Override
    public void delete(String[] ids) {
        throw new xiaozhi.common.exception.RenException("已发布固件不可删除");
    }

    @Override
    public boolean save(OtaEntity entity) {
        throw new xiaozhi.common.exception.RenException("固件发布必须经过审批入口");
    }

    @Override
    public OtaEntity getLatestOta(String type) {
        OtaEntity published = baseDao.selectPublishedChannel(type);
        if (published != null) return published;
        QueryWrapper<OtaEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type)
                .orderByDesc("update_date")
                .last("LIMIT 1");
        return baseDao.selectOne(wrapper);
    }
}
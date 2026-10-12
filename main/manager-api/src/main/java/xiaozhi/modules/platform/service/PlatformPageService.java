package xiaozhi.modules.platform.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;

@Service @RequiredArgsConstructor
public class PlatformPageService {
    private final JdbcTemplate jdbc;
    public Map<String,Object> read(String table,String fields,String where,String order,int page,int limit,Object... parameters) {
        if(page<1||limit<1||limit>200||page>100000)throw new RenException("INVALID_PAGINATION");
        List<Object> args=new ArrayList<>(Arrays.asList(parameters));args.add(limit);args.add((long)(page-1)*limit);
        return PlatformSupport.publicMap(PlatformSupport.map("list",jdbc.queryForList("SELECT "+fields+" FROM "+table+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray()),
            "total",jdbc.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,parameters)));
    }
}

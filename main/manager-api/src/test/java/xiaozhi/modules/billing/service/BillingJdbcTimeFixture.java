package xiaozhi.modules.billing.service;

import java.sql.Timestamp;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Exercise Connector/J's DATETIME Java type while keeping deterministic, isolated H2 data. */
final class BillingJdbcTimeFixture {
    private BillingJdbcTimeFixture() { }

    static JdbcTemplate localDateTimes(JdbcTemplate source) {
        return new JdbcTemplate(source.getDataSource()) {
            @Override protected RowMapper<Map<String,Object>> getColumnMapRowMapper() {
                var delegate=super.getColumnMapRowMapper();
                return (rs,rowNum)->{
                    var row=delegate.mapRow(rs,rowNum);
                    row.replaceAll((key,value)->value instanceof Timestamp timestamp?timestamp.toLocalDateTime():value);
                    return row;
                };
            }
            @Override protected <T> RowMapper<T> getSingleColumnRowMapper(Class<T> requiredType) {
                var delegate=super.getSingleColumnRowMapper(requiredType);
                return (rs,rowNum)->{
                    T value=delegate.mapRow(rs,rowNum);
                    return requiredType==Object.class && value instanceof Timestamp timestamp
                            ?requiredType.cast(timestamp.toLocalDateTime()):value;
                };
            }
        };
    }
}

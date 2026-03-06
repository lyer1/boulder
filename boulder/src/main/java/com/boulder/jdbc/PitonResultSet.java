package com.boulder.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;
import com.boulder.merger.DynoMerger;

public class PitonResultSet extends PitonResultSetDecorator {

    public PitonResultSet(ResultSet delegate, String sql) throws SQLException {
        this(delegate, sql, new HashMap<>());
    }

    public PitonResultSet(ResultSet delegate, String sql, Map<Integer, Object> parameters) throws SQLException {
        super(DynoMerger.interceptRead(sql, delegate, parameters));
    }
}

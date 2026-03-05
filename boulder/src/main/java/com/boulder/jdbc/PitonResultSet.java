package com.boulder.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import com.boulder.merger.DynoMerger;

public class PitonResultSet extends PitonResultSetDecorator {

    public PitonResultSet(ResultSet delegate, String sql) throws SQLException {
        super(DynoMerger.interceptRead(sql, delegate));
    }
}

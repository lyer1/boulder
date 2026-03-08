package com.boulder.merger;

import com.boulder.jdbc.PitonResultSetDecorator;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class MergedResultSet extends PitonResultSetDecorator {

    private final String sql;
    private final Map<Integer, Object> statementParameters;

    public MergedResultSet(ResultSet delegate, String sql) {
        this(delegate, sql, new HashMap<>());
    }

    public MergedResultSet(ResultSet delegate, String sql, Map<Integer, Object> params) {
        super(delegate);
        this.sql = sql;
        this.statementParameters = params;
    }

    // Legacy virtual row merging (isAggregate, isSorted, loadVirtual, matchesAllFilters) 
    // has been completely removed to prepare for the Federated SQL Engine architecture.
    // This class now acts as a pure pass-through to the underlying ResultSet.
}

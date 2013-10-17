/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.embedded;

import com.foundationdb.server.expressions.TypesRegistryService;
import com.foundationdb.server.service.tree.KeyCreator;
import com.foundationdb.sql.embedded.JDBCResultSetMetaData.ResultColumn;
import com.foundationdb.sql.embedded.JDBCParameterMetaData.ParameterType;

import com.foundationdb.sql.server.ServerOperatorCompiler;
import com.foundationdb.sql.server.ServerPlanContext;

import com.foundationdb.sql.optimizer.NestedResultSetTypeComputer;
import com.foundationdb.sql.optimizer.TypesTranslation;
import com.foundationdb.sql.optimizer.plan.BasePlannable;
import com.foundationdb.sql.optimizer.plan.PhysicalSelect.PhysicalResultColumn;
import com.foundationdb.sql.optimizer.plan.PhysicalUpdate;
import com.foundationdb.sql.optimizer.plan.ResultSet.ResultField;
import com.foundationdb.sql.optimizer.rule.PlanContext;

import com.foundationdb.sql.StandardException;
import com.foundationdb.sql.parser.*;
import com.foundationdb.sql.types.DataTypeDescriptor;
import com.foundationdb.sql.types.TypeId;

import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.Table;
import com.foundationdb.qp.operator.Operator;
import com.foundationdb.server.error.SQLParserInternalException;
import com.foundationdb.server.types.TInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Types;
import java.util.*;

public class EmbeddedOperatorCompiler extends ServerOperatorCompiler
{
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedQueryContext.class);

    protected EmbeddedOperatorCompiler() {
    }

    protected static EmbeddedOperatorCompiler create(JDBCConnection connection, KeyCreator keyCreator) {
        EmbeddedOperatorCompiler compiler = new EmbeddedOperatorCompiler();
        compiler.initServer(connection, keyCreator);
        compiler.initDone();
        return compiler;
    }

    @Override
    public PhysicalResultColumn getResultColumn(ResultField field) {
        return getJDBCResultColumn(field.getName(), field.getSQLtype(), field.getAIScolumn(), field.getTInstance());
    }

    protected ResultColumn getJDBCResultColumn(String name, DataTypeDescriptor sqlType, 
                                               Column aisColumn, TInstance tInstance) {
        int jdbcType = Types.OTHER;
        JDBCResultSetMetaData nestedResultSet = null;
        if (sqlType != null) {
            jdbcType = sqlType.getJDBCTypeId();
            if (sqlType.getTypeId().isRowMultiSet()) {
                TypeId.RowMultiSetTypeId typeId = 
                    (TypeId.RowMultiSetTypeId)sqlType.getTypeId();
                String[] columnNames = typeId.getColumnNames();
                DataTypeDescriptor[] columnTypes = typeId.getColumnTypes();
                List<ResultColumn> nestedResultColumns = new ArrayList<>(columnNames.length);
                for (int i = 0; i < columnNames.length; i++) {
                    nestedResultColumns.add(getJDBCResultColumn(columnNames[i], columnTypes[i], null, TypesTranslation.toTInstance(columnTypes[i])));
                }
                nestedResultSet = new JDBCResultSetMetaData(nestedResultColumns);
            }
        }
        return new ResultColumn(name, jdbcType, sqlType, aisColumn, tInstance, nestedResultSet);
    }

    public ExecutableStatement compileExecutableStatement(DMLStatementNode sqlStmt, List<ParameterNode> sqlParams, boolean getParameterNames, ExecuteAutoGeneratedKeys autoGeneratedKeys, EmbeddedQueryContext context) {
        if (autoGeneratedKeys != null) {
            if (!(sqlStmt instanceof DMLModStatementNode))
                throw JDBCException.wrapped("SELECT Statement does not generate keys");
            DMLModStatementNode updateStmt = (DMLModStatementNode)sqlStmt;
            if (updateStmt.getReturningList() != null)
                throw JDBCException.wrapped("Statement already has RETURNING");
            addAutoGeneratedReturning(updateStmt, autoGeneratedKeys);
        }
        PlanContext planContext = new ServerPlanContext(this, context);
        BasePlannable result = compile(sqlStmt, sqlParams, planContext);
        Operator resultOperator = (Operator)result.getPlannable();
        JDBCResultSetMetaData resultSetMetaData = null;
        if (!result.isUpdate() || ((PhysicalUpdate)result).isReturning()) {
            List<ResultColumn> columns = new ArrayList<>();
            for (PhysicalResultColumn column : result.getResultColumns()) {
                columns.add((ResultColumn)column);
            }
            resultSetMetaData = new JDBCResultSetMetaData(columns);
        }
        JDBCParameterMetaData parameterMetaData = null;
        if (result.getParameterTypes() != null) {
            List<ParameterType> jdbcParams = new ArrayList<>();
            for (DataTypeDescriptor sqlType : result.getParameterTypes()) {
                jdbcParams.add(new ParameterType(sqlType));
            }
            parameterMetaData = new JDBCParameterMetaData(jdbcParams);
            if (getParameterNames) {
                // TODO: Only case through here will be ? = CALL fun(?,?,...),
                // which will look like SELECT fun(...).
            }
        }
        if (result.isUpdate())
            return new ExecutableModifyOperatorStatement(resultOperator,
                                                         resultSetMetaData,
                                                         parameterMetaData);
        else
            return new ExecutableQueryOperatorStatement(resultOperator,
                                                        resultSetMetaData,
                                                        parameterMetaData,
                                                        result.getCostEstimate());
    }

    protected void addAutoGeneratedReturning(DMLModStatementNode updateStmt, 
                                             ExecuteAutoGeneratedKeys autoGeneratedKeys) {
        try {
            TableName tableName = updateStmt.getTargetTableName();
            String schemaName = tableName.getSchemaName();
            if (schemaName == null)
                schemaName = getDefaultSchemaName();
            Table table = getSchema().ais().getTable(schemaName,  
                                                     tableName.getTableName());
            if (table == null) return; // Assuming same error will occur later.
            List<Column> columns = autoGeneratedKeys.getTargetColumns(table);

            NodeFactory nodeFactory = updateStmt.getNodeFactory();
            SQLParserContext parserContext = updateStmt.getParserContext();
            ResultColumnList rcl = (ResultColumnList)
                nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                    parserContext);
            for (Column column : columns) {
                String columnName = column.getName();
                ColumnReference columnRef = (ColumnReference)
                    nodeFactory.getNode(NodeTypes.COLUMN_REFERENCE,
                                        columnName, tableName,
                                        parserContext);
                com.foundationdb.sql.parser.ResultColumn resultColumn = (com.foundationdb.sql.parser.ResultColumn)
                    nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                        columnName, columnRef,
                                        parserContext);
                rcl.addResultColumn(resultColumn);
            }
            updateStmt.setReturningList(rcl);
        }
        catch (StandardException ex) {
            throw new SQLParserInternalException(ex);
        }
    }

    // TODO: Consider making these depend on a connection string parameter.

    @Override
    protected void initAIS(AkibanInformationSchema ais, String defaultSchemaName) {
        super.initAIS(ais, defaultSchemaName);
        binder.setAllowSubqueryMultipleColumns(true);
    }


    @Override
    protected void initFunctionsRegistry(TypesRegistryService functionsRegistry) {
        super.initFunctionsRegistry(functionsRegistry);
        typeComputer = new NestedResultSetTypeComputer(functionsRegistry);
    }
}

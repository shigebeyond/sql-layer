/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
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

package com.akiban.qp.operator;

import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.exec.UpdateResult;
import com.akiban.qp.row.Row;
import com.akiban.server.explain.*;
import com.akiban.server.explain.std.DUIOperatorExplainer;
import com.akiban.util.Strings;
import com.akiban.util.tap.InOutTap;
import com.akiban.util.tap.Tap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**

 <h1>Overview</h1>

 Inserts new rows into a table. This is an UpdatePlannable, not a PhysicalOperator.

 <h1>Arguments</h1>

 <ul>

 <li><b>PhysicalOperator inputOperator:</b> source of rows to be inserted

 </ul>

 <h1>Behaviour</h1>

 For each row in the insert operator, the row in inserted into the
 table. In practice, this is currently done via
 <i>StoreAdapater#insertRow</i>, which is implemented by
 <i>PersistitAdapater#insertRow</i>, which invokes
 <i>PersistitStore#insertRow</i>

 The result of this update is an UpdateResult instance which summarizes
 how many rows were updated and how long the operation took.

 <h1>Output</h1>

 N/A

 <h1>Assumptions</h1>

 The inputOperator is returning rows of the UserTableRowType of the table being inserted into.

 <h1>Performance</h1>

 Insert may be slow because because indexes are also updated. Insert
 may be able to be improved in performance by batching the index
 updates, but there is no current API to so.

 <h1>Memory Requirements</h1>

 None.

 */

class Insert_Default implements UpdatePlannable {

    public Insert_Default(Operator inputOperator, boolean usePValues) {
        this.inputOperator = inputOperator;
        this.usePValues = usePValues;
    }

    @Override
    public UpdateResult run(QueryContext context, QueryBindings bindings) {
        return new Execution(context, bindings, inputOperator.cursor(context, bindings)).run();
    }

    @Override
    public String describePlan() {
        return describePlan(inputOperator);
    }

    @Override
    public String describePlan(Operator inputOperator) {
        return inputOperator + Strings.nl() + this;
    }

    @Override
    public List<Operator> getInputOperators() {
        return Collections.singletonList(inputOperator);
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", getName(), inputOperator);
    }

    private final Operator inputOperator;
    private final boolean usePValues;
    private static final InOutTap INSERT_TAP = Tap.createTimer("operator: Insert_Default");
    private static final Logger LOG = LoggerFactory.getLogger(Insert_Default.class);

    @Override
    public CompoundExplainer getExplainer(ExplainContext context)
    {
        Attributes atts = new Attributes();
        if (context.hasExtraInfo(this))
            atts.putAll(context.getExtraInfo(this).get()); 
        return new DUIOperatorExplainer(getName(), atts, inputOperator, context);
    }

    // Inner classes

    private class Execution extends ExecutionBase
    {
        public UpdateResult run()
        {
            int seen = 0, modified = 0;
            if (TAP_NEXT_ENABLED) {
                INSERT_TAP.in();
            }
            try {
                input.open();
                Row row;
                while ((row = input.next()) != null) {
                    // LOG.warn("About to insert {}: {}", row.rowType().userTable(), row);
                    checkQueryCancelation();
                    ++seen;
                    context.checkConstraints(row, usePValues);
                    adapter().writeRow(row, null, usePValues);
                    ++modified;
                    if (LOG_EXECUTION && LOG.isDebugEnabled()) {
                        LOG.debug("Insert_Default: inserting {}", row);
                    }
                }
            } finally {
                if (input != null) {
                    input.close();
                }
                if (TAP_NEXT_ENABLED) {
                    INSERT_TAP.out();
                }
            }
            return new StandardUpdateResult(seen, modified);
        }

        protected Execution(QueryContext queryContext, QueryBindings queryBindings, Cursor input)
        {
            super(queryContext, queryBindings);
            this.input = input;
        }

        private final Cursor input;
    }
}

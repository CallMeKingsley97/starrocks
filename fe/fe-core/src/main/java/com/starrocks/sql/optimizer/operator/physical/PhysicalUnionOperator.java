// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.sql.optimizer.operator.physical;

import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.Projection;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.util.List;

public class PhysicalUnionOperator extends PhysicalSetOperation {
    private final boolean isUnionAll;

    public PhysicalUnionOperator(List<ColumnRefOperator> columnRef, List<List<ColumnRefOperator>> childOutputColumns,
                                 boolean isUnionAll,
                                 long limit,
                                 ScalarOperator predicate,
                                 Projection projection) {
        super(OperatorType.PHYSICAL_UNION, columnRef, childOutputColumns, limit, predicate, projection);
        this.isUnionAll = isUnionAll;
    }

    public boolean isUnionAll() {
        return isUnionAll;
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C context) {
        return visitor.visitPhysicalUnion(this, context);
    }

    @Override
    public <R, C> R accept(OptExpressionVisitor<R, C> visitor, OptExpression optExpression, C context) {
        return visitor.visitPhysicalUnion(optExpression, context);
    }
}

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.expression.rules;

import org.apache.doris.catalog.ListPartitionItem;
import org.apache.doris.catalog.PartitionItem;
import org.apache.doris.catalog.RangePartitionItem;
import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.trees.expressions.Cast;
import org.apache.doris.nereids.trees.expressions.ComparisonPredicate;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.literal.BooleanLiteral;
import org.apache.doris.nereids.trees.expressions.literal.DateLiteral;
import org.apache.doris.nereids.trees.expressions.literal.DateTimeLiteral;
import org.apache.doris.nereids.trees.expressions.literal.NullLiteral;
import org.apache.doris.nereids.trees.expressions.visitor.DefaultExpressionRewriter;
import org.apache.doris.nereids.types.DateTimeType;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PartitionPruner
 */
public class PartitionPruner extends DefaultExpressionRewriter<Void> {
    private final List<OnePartitionEvaluator> partitions;
    private final Expression partitionPredicate;

    /** Different type of table may have different partition prune behavior. */
    public enum PartitionTableType {
        OLAP,
        HIVE
    }

    private PartitionPruner(List<OnePartitionEvaluator> partitions, Expression partitionPredicate) {
        this.partitions = Objects.requireNonNull(partitions, "partitions cannot be null");
        this.partitionPredicate = Objects.requireNonNull(partitionPredicate.accept(this, null),
                "partitionPredicate cannot be null");
    }

    @Override
    public Expression visitComparisonPredicate(ComparisonPredicate cp, Void context) {
        // Date cp Date is not supported in BE storage engine. So cast to DateTime in SimplifyComparisonPredicate
        // for easy process partition prune, we convert back to date compare date here
        // see more info in SimplifyComparisonPredicate
        Expression left = cp.left();
        Expression right = cp.right();
        if (left.getDataType() != DateTimeType.INSTANCE || right.getDataType() != DateTimeType.INSTANCE) {
            return cp;
        }
        if (!(left instanceof DateTimeLiteral) && !(right instanceof DateTimeLiteral)) {
            return cp;
        }
        if (left instanceof DateTimeLiteral && ((DateTimeLiteral) left).isMidnight()
                && right instanceof Cast
                && ((Cast) right).child() instanceof SlotReference
                && ((Cast) right).child().getDataType().isDateType()) {
            DateTimeLiteral dt = (DateTimeLiteral) left;
            Cast cast = (Cast) right;
            return cp.withChildren(new DateLiteral(dt.getYear(), dt.getMonth(), dt.getDay()), cast.child());
        } else if (right instanceof DateTimeLiteral && ((DateTimeLiteral) right).isMidnight()
                && left instanceof Cast
                && ((Cast) left).child() instanceof SlotReference
                && ((Cast) left).child().getDataType().isDateType()) {
            DateTimeLiteral dt = (DateTimeLiteral) right;
            Cast cast = (Cast) left;
            return cp.withChildren(cast.child(), new DateLiteral(dt.getYear(), dt.getMonth(), dt.getDay()));
        } else {
            return cp;
        }
    }

    public List<Long> prune() {
        return partitions.stream()
                .filter(partitionEvaluator -> !canPrune(partitionEvaluator))
                .map(OnePartitionEvaluator::getPartitionId)
                .collect(ImmutableList.toImmutableList());
    }

    /**
     * prune partition with `idToPartitions` as parameter.
     */
    public static List<Long> prune(List<Slot> partitionSlots, Expression partitionPredicate,
            Map<Long, PartitionItem> idToPartitions, CascadesContext cascadesContext,
            PartitionTableType partitionTableType) {
        partitionPredicate = TryEliminateUninterestedPredicates.rewrite(
                partitionPredicate, ImmutableSet.copyOf(partitionSlots), cascadesContext);
        partitionPredicate = PredicateRewriteForPartitionPrune.rewrite(partitionPredicate, cascadesContext);
        List<OnePartitionEvaluator> evaluators = idToPartitions.entrySet()
                .stream()
                .map(kv -> toPartitionEvaluator(kv.getKey(), kv.getValue(), partitionSlots, cascadesContext,
                        partitionTableType))
                .collect(ImmutableList.toImmutableList());

        partitionPredicate = OrToIn.INSTANCE.rewrite(partitionPredicate, null);
        PartitionPruner partitionPruner = new PartitionPruner(evaluators, partitionPredicate);
        //TODO: we keep default partition because it's too hard to prune it, we return false in canPrune().
        return partitionPruner.prune();
    }

    /**
     * convert partition item to partition evaluator
     */
    public static final OnePartitionEvaluator toPartitionEvaluator(long id, PartitionItem partitionItem,
            List<Slot> partitionSlots, CascadesContext cascadesContext, PartitionTableType partitionTableType) {
        if (partitionItem instanceof ListPartitionItem) {
            if (partitionTableType == PartitionTableType.HIVE
                    && ((ListPartitionItem) partitionItem).isHiveDefaultPartition()) {
                return new HiveDefaultPartitionEvaluator(id, partitionSlots);
            } else {
                return new OneListPartitionEvaluator(
                        id, partitionSlots, (ListPartitionItem) partitionItem, cascadesContext);
            }
        } else if (partitionItem instanceof RangePartitionItem) {
            return new OneRangePartitionEvaluator(
                    id, partitionSlots, (RangePartitionItem) partitionItem, cascadesContext);
        } else {
            return new UnknownPartitionEvaluator(id, partitionItem);
        }
    }

    private boolean canPrune(OnePartitionEvaluator evaluator) {
        List<Map<Slot, PartitionSlotInput>> onePartitionInputs = evaluator.getOnePartitionInputs();
        for (Map<Slot, PartitionSlotInput> currentInputs : onePartitionInputs) {
            Expression result = evaluator.evaluateWithDefaultPartition(partitionPredicate, currentInputs);
            if (!result.equals(BooleanLiteral.FALSE) && !(result instanceof NullLiteral)) {
                return false;
            }
        }
        return true;
    }
}

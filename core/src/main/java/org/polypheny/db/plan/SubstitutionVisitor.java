/*
 * Copyright 2019-2021 The Polypheny Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file incorporates code covered by the following terms:
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polypheny.db.plan;


import static org.polypheny.db.rex.RexUtil.andNot;
import static org.polypheny.db.rex.RexUtil.removeAll;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.calcite.linq4j.Ord;
import org.polypheny.db.algebra.AlgNode;
import org.polypheny.db.algebra.core.Aggregate;
import org.polypheny.db.algebra.core.AggregateCall;
import org.polypheny.db.algebra.core.AlgFactories;
import org.polypheny.db.algebra.fun.AggFunction;
import org.polypheny.db.algebra.logical.LogicalAggregate;
import org.polypheny.db.algebra.logical.LogicalFilter;
import org.polypheny.db.algebra.logical.LogicalJoin;
import org.polypheny.db.algebra.logical.LogicalProject;
import org.polypheny.db.algebra.logical.LogicalTableScan;
import org.polypheny.db.algebra.logical.LogicalUnion;
import org.polypheny.db.algebra.mutable.Holder;
import org.polypheny.db.algebra.mutable.MutableAggregate;
import org.polypheny.db.algebra.mutable.MutableAlg;
import org.polypheny.db.algebra.mutable.MutableAlgVisitor;
import org.polypheny.db.algebra.mutable.MutableAlgs;
import org.polypheny.db.algebra.mutable.MutableFilter;
import org.polypheny.db.algebra.mutable.MutableProject;
import org.polypheny.db.algebra.mutable.MutableScan;
import org.polypheny.db.algebra.operators.OperatorName;
import org.polypheny.db.algebra.type.AlgDataType;
import org.polypheny.db.algebra.type.AlgDataTypeField;
import org.polypheny.db.config.RuntimeConfig;
import org.polypheny.db.languages.OperatorRegistry;
import org.polypheny.db.rex.RexBuilder;
import org.polypheny.db.rex.RexCall;
import org.polypheny.db.rex.RexExecutor;
import org.polypheny.db.rex.RexExecutorImpl;
import org.polypheny.db.rex.RexInputRef;
import org.polypheny.db.rex.RexLiteral;
import org.polypheny.db.rex.RexNode;
import org.polypheny.db.rex.RexShuttle;
import org.polypheny.db.rex.RexSimplify;
import org.polypheny.db.rex.RexUtil;
import org.polypheny.db.tools.AlgBuilder;
import org.polypheny.db.tools.AlgBuilderFactory;
import org.polypheny.db.util.Bug;
import org.polypheny.db.util.ControlFlowException;
import org.polypheny.db.util.ImmutableBitSet;
import org.polypheny.db.util.Litmus;
import org.polypheny.db.util.Pair;
import org.polypheny.db.util.Util;
import org.polypheny.db.util.mapping.Mapping;
import org.polypheny.db.util.mapping.Mappings;
import org.polypheny.db.util.trace.PolyphenyDbTrace;
import org.slf4j.Logger;


/**
 * Substitutes part of a tree of relational expressions with another tree.
 *
 * The call {@code new SubstitutionVisitor(target, query).go(replacement))} will return {@code query} with every occurrence of {@code target} replaced by {@code replacement}.
 *
 * The following example shows how {@code SubstitutionVisitor} can be used for materialized view recognition.
 *
 * <ul>
 * <li>query = SELECT a, c FROM t WHERE x = 5 AND b = 4</li>
 * <li>target = SELECT a, b, c FROM t WHERE x = 5</li>
 * <li>replacement = SELECT * FROM mv</li>
 * <li>result = SELECT a, c FROM mv WHERE b = 4</li>
 * </ul>
 *
 * Note that {@code result} uses the materialized view table {@code mv} and a simplified condition {@code b = 4}.
 *
 * Uses a bottom-up matching algorithm. Nodes do not need to be identical. At each level, returns the residue.
 *
 * The inputs must only include the core relational operators:
 * {@link LogicalTableScan},
 * {@link LogicalFilter},
 * {@link LogicalProject},
 * {@link LogicalJoin},
 * {@link LogicalUnion},
 * {@link LogicalAggregate}.
 */
public class SubstitutionVisitor {

    private static final boolean DEBUG = RuntimeConfig.DEBUG.getBoolean();

    private static final Logger LOGGER = PolyphenyDbTrace.getPlannerTracer();

    protected static final ImmutableList<UnifyRule> DEFAULT_RULES =
            ImmutableList.of(
                    TrivialRule.INSTANCE,
                    ScanToProjectUnifyRule.INSTANCE,
                    ProjectToProjectUnifyRule.INSTANCE,
                    FilterToProjectUnifyRule.INSTANCE,
//          ProjectToFilterUnifyRule.INSTANCE,
//          FilterToFilterUnifyRule.INSTANCE,
                    AggregateToAggregateUnifyRule.INSTANCE,
                    AggregateOnProjectToAggregateUnifyRule.INSTANCE );

    /**
     * Factory for a builder for relational expressions.
     */
    protected final AlgBuilder algBuilder;

    private final ImmutableList<UnifyRule> rules;
    private final Map<Pair<Class, Class>, List<UnifyRule>> ruleMap = new HashMap<>();
    private final AlgOptCluster cluster;
    private final RexSimplify simplify;
    private final Holder query;
    private final MutableAlg target;

    /**
     * Nodes in {@link #target} that have no children.
     */
    final List<MutableAlg> targetLeaves;

    /**
     * Nodes in {@link #query} that have no children.
     */
    final List<MutableAlg> queryLeaves;

    final Map<MutableAlg, MutableAlg> replacementMap = new HashMap<>();

    final Multimap<MutableAlg, MutableAlg> equivalents =
            LinkedHashMultimap.create();

    /**
     * Workspace while rule is being matched.
     * Careful, re-entrant!
     * Assumes no rule needs more than 2 slots.
     */
    protected final MutableAlg[] slots = new MutableAlg[2];


    /**
     * Creates a SubstitutionVisitor with the default rule set.
     */
    public SubstitutionVisitor( AlgNode target_, AlgNode query_ ) {
        this( target_, query_, DEFAULT_RULES, AlgFactories.LOGICAL_BUILDER );
    }


    /**
     * Creates a SubstitutionVisitor with the default logical builder.
     */
    public SubstitutionVisitor( AlgNode target_, AlgNode query_, ImmutableList<UnifyRule> rules ) {
        this( target_, query_, rules, AlgFactories.LOGICAL_BUILDER );
    }


    public SubstitutionVisitor( AlgNode target_, AlgNode query_, ImmutableList<UnifyRule> rules, AlgBuilderFactory algBuilderFactory ) {
        this.cluster = target_.getCluster();
        final RexExecutor executor = Util.first( cluster.getPlanner().getExecutor(), RexUtil.EXECUTOR );
        final AlgOptPredicateList predicates = AlgOptPredicateList.EMPTY;
        this.simplify = new RexSimplify( cluster.getRexBuilder(), predicates, executor );
        this.rules = rules;
        this.query = Holder.of( MutableAlgs.toMutable( query_ ) );
        this.target = MutableAlgs.toMutable( target_ );
        this.algBuilder = algBuilderFactory.create( cluster, null );
        final Set<MutableAlg> parents = Sets.newIdentityHashSet();
        final List<MutableAlg> allNodes = new ArrayList<>();
        final MutableAlgVisitor visitor =
                new MutableAlgVisitor() {
                    @Override
                    public void visit( MutableAlg node ) {
                        parents.add( node.getParent() );
                        allNodes.add( node );
                        super.visit( node );
                    }
                };
        visitor.go( target );

        // Populate the list of leaves in the tree under "target". Leaves are all nodes that are not parents.
        // For determinism, it is important that the list is in scan order.
        allNodes.removeAll( parents );
        targetLeaves = ImmutableList.copyOf( allNodes );

        allNodes.clear();
        parents.clear();
        visitor.go( query );
        allNodes.removeAll( parents );
        queryLeaves = ImmutableList.copyOf( allNodes );
    }


    void register( MutableAlg result, MutableAlg query ) {
    }


    /**
     * Maps a condition onto a target.
     *
     * If condition is stronger than target, returns the residue.
     * If it is equal to target, returns the expression that evaluates to the constant {@code true}. If it is weaker than target, returns {@code null}.
     *
     * The terms satisfy the relation
     *
     * <blockquote>
     * <pre>{@code condition = target AND residue}</pre>
     * </blockquote>
     *
     * and {@code residue} must be as weak as possible.
     *
     * Example #1: condition stronger than target
     * <ul>
     * <li>condition: x = 1 AND y = 2</li>
     * <li>target: x = 1</li>
     * <li>residue: y = 2</li>
     * </ul>
     *
     * Note that residue {@code x &gt; 0 AND y = 2} would also satisfy the relation {@code condition = target AND residue} but is stronger than necessary, so we prefer {@code y = 2}.
     *
     * Example #2: target weaker than condition (valid, but not currently implemented)
     * <ul>
     * <li>condition: x = 1</li>
     * <li>target: x = 1 OR z = 3</li>
     * <li>residue: x = 1</li>
     * </ul>
     *
     * Example #3: condition and target are equivalent
     * <ul>
     * <li>condition: x = 1 AND y = 2</li>
     * <li>target: y = 2 AND x = 1</li>
     * <li>residue: TRUE</li>
     * </ul>
     *
     * Example #4: condition weaker than target
     * <ul>
     * <li>condition: x = 1</li>
     * <li>target: x = 1 AND y = 2</li>
     * <li>residue: null (i.e. no match)</li>
     * </ul>
     *
     * There are many other possible examples. It amounts to solving whether {@code condition AND NOT target} can ever evaluate to true, and therefore is a form of the NP-complete
     * <a href="http://en.wikipedia.org/wiki/Satisfiability">Satisfiability</a> problem.
     */
    @VisibleForTesting
    public static RexNode splitFilter( final RexSimplify simplify, RexNode condition, RexNode target ) {
        final RexBuilder rexBuilder = simplify.rexBuilder;
        RexNode condition2 = canonizeNode( rexBuilder, condition );
        RexNode target2 = canonizeNode( rexBuilder, target );

        // First, try splitting into ORs.
        // Given target    c1 OR c2 OR c3 OR c4
        // and condition   c2 OR c4
        // residue is      c2 OR c4
        // Also deals with case target [x] condition [x] yields residue [true].
        RexNode z = splitOr( rexBuilder, condition2, target2 );
        if ( z != null ) {
            return z;
        }

        if ( isEquivalent( rexBuilder, condition2, target2 ) ) {
            return rexBuilder.makeLiteral( true );
        }

        RexNode x = andNot( rexBuilder, target2, condition2 );
        if ( mayBeSatisfiable( x ) ) {
            RexNode x2 = RexUtil.composeConjunction( rexBuilder, ImmutableList.of( condition2, target2 ) );
            RexNode r = canonizeNode( rexBuilder, simplify.simplifyUnknownAsFalse( x2 ) );
            if ( !r.isAlwaysFalse() && isEquivalent( rexBuilder, condition2, r ) ) {
                List<RexNode> conjs = AlgOptUtil.conjunctions( r );
                for ( RexNode e : AlgOptUtil.conjunctions( target2 ) ) {
                    removeAll( conjs, e );
                }
                return RexUtil.composeConjunction( rexBuilder, conjs );
            }
        }
        return null;
    }


    /**
     * Reorders some of the operands in this expression so structural comparison, i.e., based on string representation, can be more precise.
     */
    private static RexNode canonizeNode( RexBuilder rexBuilder, RexNode condition ) {
        switch ( condition.getKind() ) {
            case AND:
            case OR: {
                RexCall call = (RexCall) condition;
                SortedMap<String, RexNode> newOperands = new TreeMap<>();
                for ( RexNode operand : call.operands ) {
                    operand = canonizeNode( rexBuilder, operand );
                    newOperands.put( operand.toString(), operand );
                }
                if ( newOperands.size() < 2 ) {
                    return newOperands.values().iterator().next();
                }
                return rexBuilder.makeCall( call.getOperator(), ImmutableList.copyOf( newOperands.values() ) );
            }
            case EQUALS:
            case NOT_EQUALS:
            case LESS_THAN:
            case GREATER_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN_OR_EQUAL: {
                RexCall call = (RexCall) condition;
                final RexNode left = call.getOperands().get( 0 );
                final RexNode right = call.getOperands().get( 1 );
                if ( left.toString().compareTo( right.toString() ) <= 0 ) {
                    return call;
                }
                return RexUtil.invert( rexBuilder, call );
            }
            default:
                return condition;
        }
    }


    private static RexNode splitOr( final RexBuilder rexBuilder, RexNode condition, RexNode target ) {
        List<RexNode> conditions = AlgOptUtil.disjunctions( condition );
        int conditionsLength = conditions.size();
        int targetsLength = 0;
        for ( RexNode e : AlgOptUtil.disjunctions( target ) ) {
            removeAll( conditions, e );
            targetsLength++;
        }
        if ( conditions.isEmpty() && conditionsLength == targetsLength ) {
            return rexBuilder.makeLiteral( true );
        } else if ( conditions.isEmpty() ) {
            return condition;
        }
        return null;
    }


    private static boolean isEquivalent( RexBuilder rexBuilder, RexNode condition, RexNode target ) {
        // Example:
        //  e: x = 1 AND y = 2 AND z = 3 AND NOT (x = 1 AND y = 2)
        //  disjunctions: {x = 1, y = 2, z = 3}
        //  notDisjunctions: {x = 1 AND y = 2}
        final Set<String> conditionDisjunctions = new HashSet<>( RexUtil.strings( AlgOptUtil.conjunctions( condition ) ) );
        final Set<String> targetDisjunctions = new HashSet<>( RexUtil.strings( AlgOptUtil.conjunctions( target ) ) );
        return conditionDisjunctions.equals( targetDisjunctions );
    }


    /**
     * Returns whether a boolean expression ever returns true.
     *
     * This method may give false positives. For instance, it will say that {@code x = 5 AND x > 10} is satisfiable, because at present it cannot prove that it is not.
     */
    public static boolean mayBeSatisfiable( RexNode e ) {
        // Example:
        //  e: x = 1 AND y = 2 AND z = 3 AND NOT (x = 1 AND y = 2)
        //  disjunctions: {x = 1, y = 2, z = 3}
        //  notDisjunctions: {x = 1 AND y = 2}
        final List<RexNode> disjunctions = new ArrayList<>();
        final List<RexNode> notDisjunctions = new ArrayList<>();
        AlgOptUtil.decomposeConjunction( e, disjunctions, notDisjunctions );

        // If there is a single FALSE or NOT TRUE, the whole expression is always false.
        for ( RexNode disjunction : disjunctions ) {
            switch ( disjunction.getKind() ) {
                case LITERAL:
                    if ( !RexLiteral.booleanValue( disjunction ) ) {
                        return false;
                    }
            }
        }
        for ( RexNode disjunction : notDisjunctions ) {
            switch ( disjunction.getKind() ) {
                case LITERAL:
                    if ( RexLiteral.booleanValue( disjunction ) ) {
                        return false;
                    }
            }
        }
        // If one of the not-disjunctions is a disjunction that is wholly contained in the disjunctions list, the expression is not satisfiable.
        //
        // Example #1. x AND y AND z AND NOT (x AND y)  - not satisfiable
        // Example #2. x AND y AND NOT (x AND y)        - not satisfiable
        // Example #3. x AND y AND NOT (x AND y AND z)  - may be satisfiable
        for ( RexNode notDisjunction : notDisjunctions ) {
            final List<RexNode> disjunctions2 = AlgOptUtil.conjunctions( notDisjunction );
            if ( disjunctions.containsAll( disjunctions2 ) ) {
                return false;
            }
        }
        return true;
    }


    public AlgNode go0( AlgNode replacement_ ) {
        assert false; // not called
        MutableAlg replacement = MutableAlgs.toMutable( replacement_ );
        assert equalType( "target", target, "replacement", replacement, Litmus.THROW );
        replacementMap.put( target, replacement );
        final UnifyResult unifyResult = matchRecurse( target );
        if ( unifyResult == null ) {
            return null;
        }
        final MutableAlg node0 = unifyResult.result;
        MutableAlg node = node0; // replaceAncestors(node0);
        if ( DEBUG ) {
            System.out.println( "Convert: query:\n"
                    + query.deep()
                    + "\nunify.query:\n"
                    + unifyResult.call.query.deep()
                    + "\nunify.result:\n"
                    + unifyResult.result.deep()
                    + "\nunify.target:\n"
                    + unifyResult.call.target.deep()
                    + "\nnode0:\n"
                    + node0.deep()
                    + "\nnode:\n"
                    + node.deep() );
        }
        return MutableAlgs.fromMutable( node, algBuilder );
    }


    /**
     * Returns a list of all possible rels that result from substituting the matched {@link AlgNode} with the replacement {@link AlgNode} within the query.
     *
     * For example, the substitution result of A join B, while A and B are both a qualified match for replacement R, is R join B, R join R, A join R.
     */
    public List<AlgNode> go( AlgNode replacement_ ) {
        List<List<Replacement>> matches = go( MutableAlgs.toMutable( replacement_ ) );
        if ( matches.isEmpty() ) {
            return ImmutableList.of();
        }
        List<AlgNode> sub = new ArrayList<>();
        sub.add( MutableAlgs.fromMutable( query.getInput(), algBuilder ) );
        reverseSubstitute( algBuilder, query, matches, sub, 0, matches.size() );
        return sub;
    }


    /**
     * Substitutes the query with replacement whenever possible but meanwhile keeps track of all the substitutions and their original alg before replacement, so that in later processing stage,
     * the replacement can be recovered individually to produce a list of all possible rels with substitution in different places.
     */
    private List<List<Replacement>> go( MutableAlg replacement ) {
        assert equalType( "target", target, "replacement", replacement, Litmus.THROW );
        final List<MutableAlg> queryDescendants = MutableAlgs.descendants( query );
        final List<MutableAlg> targetDescendants = MutableAlgs.descendants( target );

        // Populate "equivalents" with (q, t) for each query descendant q and target descendant t that are equal.
        final Map<MutableAlg, MutableAlg> map = new HashMap<>();
        for ( MutableAlg queryDescendant : queryDescendants ) {
            map.put( queryDescendant, queryDescendant );
        }
        for ( MutableAlg targetDescendant : targetDescendants ) {
            MutableAlg queryDescendant = map.get( targetDescendant );
            if ( queryDescendant != null ) {
                assert queryDescendant.rowType.equals( targetDescendant.rowType );
                equivalents.put( queryDescendant, targetDescendant );
            }
        }
        map.clear();

        final List<Replacement> attempted = new ArrayList<>();
        List<List<Replacement>> substitutions = new ArrayList<>();

        for ( ; ; ) {
            int count = 0;
            MutableAlg queryDescendant = query;
            outer:
            while ( queryDescendant != null ) {
                for ( Replacement r : attempted ) {
                    if ( queryDescendant == r.after ) {
                        // This node has been replaced by previous iterations in the hope to match its ancestors, so the node itself should not be matched again.
                        queryDescendant = MutableAlgs.preOrderTraverseNext( queryDescendant );
                        continue outer;
                    }
                }
                final MutableAlg next = MutableAlgs.preOrderTraverseNext( queryDescendant );
                final MutableAlg childOrNext =
                        queryDescendant.getInputs().isEmpty()
                                ? next
                                : queryDescendant.getInputs().get( 0 );
                for ( MutableAlg targetDescendant : targetDescendants ) {
                    for ( UnifyRule rule : applicableRules( queryDescendant, targetDescendant ) ) {
                        UnifyRuleCall call = rule.match( this, queryDescendant, targetDescendant );
                        if ( call != null ) {
                            final UnifyResult result = rule.apply( call );
                            if ( result != null ) {
                                ++count;
                                attempted.add( new Replacement( result.call.query, result.result ) );
                                MutableAlg parent = result.call.query.replaceInParent( result.result );

                                // Replace previous equivalents with new equivalents, higher up the tree.
                                for ( int i = 0; i < rule.slotCount; i++ ) {
                                    Collection<MutableAlg> equi = equivalents.get( slots[i] );
                                    if ( !equi.isEmpty() ) {
                                        equivalents.remove( slots[i], equi.iterator().next() );
                                    }
                                }
                                assert result.result.rowType.equals( result.call.query.rowType ) : Pair.of( result.result, result.call.query );
                                equivalents.put( result.result, result.call.query );
                                if ( targetDescendant == target ) {
                                    // A real substitution happens. We purge the attempted replacement list and add them into substitution list.
                                    // Meanwhile we stop matching the descendants and jump to the next subtree in pre-order traversal.
                                    if ( !target.equals( replacement ) ) {
                                        Replacement r = replace( query.getInput(), target, replacement.clone() );
                                        assert r != null : rule + "should have returned a result containing the target.";
                                        attempted.add( r );
                                    }
                                    substitutions.add( ImmutableList.copyOf( attempted ) );
                                    attempted.clear();
                                    queryDescendant = next;
                                    continue outer;
                                }
                                // We will try walking the query tree all over again to see if there can be any substitutions after the replacement attempt.
                                break outer;
                            }
                        }
                    }
                }
                queryDescendant = childOrNext;
            }
            // Quit the entire loop if:
            // 1) we have walked the entire query tree with one or more successful substitutions, thus count != 0 && attempted.isEmpty();
            // 2) we have walked the entire query tree but have made no replacement attempt, thus count == 0 && attempted.isEmpty();
            // 3) we had done some replacement attempt in a previous walk, but in this one we have not found any potential matches or substitutions, thus count == 0 && !attempted.isEmpty().
            if ( count == 0 || attempted.isEmpty() ) {
                break;
            }
        }
        if ( !attempted.isEmpty() ) {
            // We had done some replacement attempt in the previous walk, but that did not lead to any substitutions in this walk, so we need to recover the replacement.
            undoReplacement( attempted );
        }
        return substitutions;
    }


    /**
     * Represents a replacement action: before &rarr; after.
     */
    static class Replacement {

        final MutableAlg before;
        final MutableAlg after;


        Replacement( MutableAlg before, MutableAlg after ) {
            this.before = before;
            this.after = after;
        }

    }


    /**
     * Within a relational expression {@code query}, replaces occurrences of {@code find} with {@code replace}.
     *
     * Assumes relational expressions (and their descendants) are not null. Does not handle cycles.
     */
    public static Replacement replace( MutableAlg query, MutableAlg find, MutableAlg replace ) {
        if ( find.equals( replace ) ) {
            // Short-cut common case.
            return null;
        }
        assert equalType( "find", find, "replace", replace, Litmus.THROW );
        return replaceRecurse( query, find, replace );
    }


    /**
     * Helper for {@link #replace}.
     */
    private static Replacement replaceRecurse( MutableAlg query, MutableAlg find, MutableAlg replace ) {
        if ( find.equals( query ) ) {
            query.replaceInParent( replace );
            return new Replacement( query, replace );
        }
        for ( MutableAlg input : query.getInputs() ) {
            Replacement r = replaceRecurse( input, find, replace );
            if ( r != null ) {
                return r;
            }
        }
        return null;
    }


    private static void undoReplacement( List<Replacement> replacement ) {
        for ( int i = replacement.size() - 1; i >= 0; i-- ) {
            Replacement r = replacement.get( i );
            r.after.replaceInParent( r.before );
        }
    }


    private static void redoReplacement( List<Replacement> replacement ) {
        for ( Replacement r : replacement ) {
            r.before.replaceInParent( r.after );
        }
    }


    private static void reverseSubstitute( AlgBuilder algBuilder, Holder query, List<List<Replacement>> matches, List<AlgNode> sub, int replaceCount, int maxCount ) {
        if ( matches.isEmpty() ) {
            return;
        }
        final List<List<Replacement>> rem = matches.subList( 1, matches.size() );
        reverseSubstitute( algBuilder, query, rem, sub, replaceCount, maxCount );
        undoReplacement( matches.get( 0 ) );
        if ( ++replaceCount < maxCount ) {
            sub.add( MutableAlgs.fromMutable( query.getInput(), algBuilder ) );
        }
        reverseSubstitute( algBuilder, query, rem, sub, replaceCount, maxCount );
        redoReplacement( matches.get( 0 ) );
    }


    private UnifyResult matchRecurse( MutableAlg target ) {
        assert false; // not called
        final List<MutableAlg> targetInputs = target.getInputs();
        MutableAlg queryParent = null;

        for ( MutableAlg targetInput : targetInputs ) {
            UnifyResult unifyResult = matchRecurse( targetInput );
            if ( unifyResult == null ) {
                return null;
            }
            queryParent = unifyResult.call.query.replaceInParent( unifyResult.result );
        }

        if ( targetInputs.isEmpty() ) {
            for ( MutableAlg queryLeaf : queryLeaves ) {
                for ( UnifyRule rule : applicableRules( queryLeaf, target ) ) {
                    final UnifyResult x = apply( rule, queryLeaf, target );
                    if ( x != null ) {
                        if ( DEBUG ) {
                            System.out.println(
                                    "Rule: " + rule
                                            + "\nQuery:\n"
                                            + queryParent
                                            + (x.call.query != queryParent ? "\nQuery (original):\n" + queryParent : "")
                                            + "\nTarget:\n"
                                            + target.deep()
                                            + "\nResult:\n"
                                            + x.result.deep()
                                            + "\n" );
                        }
                        return x;
                    }
                }
            }
        } else {
            assert queryParent != null;
            for ( UnifyRule rule : applicableRules( queryParent, target ) ) {
                final UnifyResult x = apply( rule, queryParent, target );
                if ( x != null ) {
                    if ( DEBUG ) {
                        System.out.println(
                                "Rule: " + rule
                                        + "\nQuery:\n"
                                        + queryParent.deep()
                                        + (x.call.query != queryParent ? "\nQuery (original):\n" + queryParent.toString() : "")
                                        + "\nTarget:\n"
                                        + target.deep()
                                        + "\nResult:\n"
                                        + x.result.deep()
                                        + "\n" );
                    }
                    return x;
                }
            }
        }
        if ( DEBUG ) {
            System.out.println( "Unify failed:" + "\nQuery:\n" + queryParent.toString() + "\nTarget:\n" + target.toString() + "\n" );
        }
        return null;
    }


    private UnifyResult apply( UnifyRule rule, MutableAlg query, MutableAlg target ) {
        final UnifyRuleCall call = new UnifyRuleCall( rule, query, target, null );
        return rule.apply( call );
    }


    private List<UnifyRule> applicableRules( MutableAlg query, MutableAlg target ) {
        final Class queryClass = query.getClass();
        final Class targetClass = target.getClass();
        final Pair<Class, Class> key = Pair.of( queryClass, targetClass );
        List<UnifyRule> list = ruleMap.get( key );
        if ( list == null ) {
            final ImmutableList.Builder<UnifyRule> builder = ImmutableList.builder();
            for ( UnifyRule rule : rules ) {
                if ( mightMatch( rule, queryClass, targetClass ) ) {
                    builder.add( rule );
                }
            }
            list = builder.build();
            ruleMap.put( key, list );
        }
        return list;
    }


    private static boolean mightMatch( UnifyRule rule, Class queryClass, Class targetClass ) {
        return rule.queryOperand.clazz.isAssignableFrom( queryClass ) && rule.targetOperand.clazz.isAssignableFrom( targetClass );
    }


    /**
     * Exception thrown to exit a matcher. Not really an error.
     */
    protected static class MatchFailed extends ControlFlowException {

        public static final MatchFailed INSTANCE = new MatchFailed();

    }


    /**
     * Rule that attempts to match a query relational expression against a target relational expression.
     *
     * The rule declares the query and target types; this allows the engine to fire only a few rules in a given context.
     */
    protected abstract static class UnifyRule {

        protected final int slotCount;
        protected final Operand queryOperand;
        protected final Operand targetOperand;


        protected UnifyRule( int slotCount, Operand queryOperand, Operand targetOperand ) {
            this.slotCount = slotCount;
            this.queryOperand = queryOperand;
            this.targetOperand = targetOperand;
        }


        /**
         * Applies this rule to a particular node in a query. The goal is to convert {@code query} into {@code target}. Before the rule is invoked, Polypheny-DB has made sure that query's
         * children are equivalent to target's children.
         *
         * There are 3 possible outcomes:
         *
         * <ul>
         * <li>{@code query} already exactly matches {@code target}; returns {@code target}</li>
         * <li>{@code query} is sufficiently close to a match for {@code target}; returns {@code target}</li>
         * <li>{@code query} cannot be made to match {@code target}; returns null</li>
         * </ul>
         *
         * REVIEW: Is possible that we match query PLUS one or more of its ancestors?
         *
         * @param call Input parameters
         */
        protected abstract UnifyResult apply( UnifyRuleCall call );


        protected UnifyRuleCall match( SubstitutionVisitor visitor, MutableAlg query, MutableAlg target ) {
            if ( queryOperand.matches( visitor, query ) ) {
                if ( targetOperand.matches( visitor, target ) ) {
                    return visitor.new UnifyRuleCall( this, query, target, copy( visitor.slots, slotCount ) );
                }
            }
            return null;
        }


        protected <E> ImmutableList<E> copy( E[] slots, int slotCount ) {
            // Optimize if there are 0 or 1 slots.
            switch ( slotCount ) {
                case 0:
                    return ImmutableList.of();
                case 1:
                    return ImmutableList.of( slots[0] );
                default:
                    return ImmutableList.copyOf( slots ).subList( 0, slotCount );
            }
        }

    }


    /**
     * Arguments to an application of a {@link UnifyRule}.
     */
    protected class UnifyRuleCall {

        protected final UnifyRule rule;
        public final MutableAlg query;
        public final MutableAlg target;
        protected final ImmutableList<MutableAlg> slots;


        public UnifyRuleCall( UnifyRule rule, MutableAlg query, MutableAlg target, ImmutableList<MutableAlg> slots ) {
            this.rule = Objects.requireNonNull( rule );
            this.query = Objects.requireNonNull( query );
            this.target = Objects.requireNonNull( target );
            this.slots = Objects.requireNonNull( slots );
        }


        public UnifyResult result( MutableAlg result ) {
            assert MutableAlgs.contains( result, target );
            assert equalType( "result", result, "query", query, Litmus.THROW );
            MutableAlg replace = replacementMap.get( target );
            if ( replace != null ) {
                assert false; // replacementMap is always empty
                // result =
                replace( result, target, replace );
            }
            register( result, query );
            return new UnifyResult( this, result );
        }


        /**
         * Creates a {@link UnifyRuleCall} based on the parent of {@code query}.
         */
        public UnifyRuleCall create( MutableAlg query ) {
            return new UnifyRuleCall( rule, query, target, slots );
        }


        public AlgOptCluster getCluster() {
            return cluster;
        }


        public RexSimplify getSimplify() {
            return simplify;
        }

    }


    /**
     * Result of an application of a {@link UnifyRule} indicating that the rule successfully matched {@code query} against {@code target} and
     * generated a {@code result} that is equivalent to {@code query} and contains {@code target}.
     */
    protected static class UnifyResult {

        private final UnifyRuleCall call;
        // equivalent to "query", contains "result"
        private final MutableAlg result;


        UnifyResult( UnifyRuleCall call, MutableAlg result ) {
            this.call = call;
            assert equalType( "query", call.query, "result", result, Litmus.THROW );
            this.result = result;
        }

    }


    /**
     * Abstract base class for implementing {@link UnifyRule}.
     */
    protected abstract static class AbstractUnifyRule extends UnifyRule {

        public AbstractUnifyRule( Operand queryOperand, Operand targetOperand, int slotCount ) {
            super( slotCount, queryOperand, targetOperand );
            //noinspection AssertWithSideEffects
            assert isValid();
        }


        protected boolean isValid() {
            final SlotCounter slotCounter = new SlotCounter();
            slotCounter.visit( queryOperand );
            assert slotCounter.queryCount == slotCount;
            assert slotCounter.targetCount == 0;
            slotCounter.queryCount = 0;
            slotCounter.visit( targetOperand );
            assert slotCounter.queryCount == 0;
            assert slotCounter.targetCount == slotCount;
            return true;
        }


        /**
         * Creates an operand with given inputs.
         */
        protected static Operand operand( Class<? extends MutableAlg> clazz, Operand... inputOperands ) {
            return new InternalOperand( clazz, ImmutableList.copyOf( inputOperands ) );
        }


        /**
         * Creates an operand that doesn't check inputs.
         */
        protected static Operand any( Class<? extends MutableAlg> clazz ) {
            return new AnyOperand( clazz );
        }


        /**
         * Creates an operand that matches a relational expression in the query.
         */
        protected static Operand query( int ordinal ) {
            return new QueryOperand( ordinal );
        }


        /**
         * Creates an operand that matches a relational expression in the
         * target.
         */
        protected static Operand target( int ordinal ) {
            return new TargetOperand( ordinal );
        }

    }


    /**
     * Implementation of {@link UnifyRule} that matches if the query is already equal to the target.
     *
     * Matches scans to the same table, because these will be {@link MutableScan}s with the same {@link org.polypheny.db.algebra.logical.LogicalTableScan} instance.
     */
    private static class TrivialRule extends AbstractUnifyRule {

        private static final TrivialRule INSTANCE = new TrivialRule();


        private TrivialRule() {
            super( any( MutableAlg.class ), any( MutableAlg.class ), 0 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            if ( call.query.equals( call.target ) ) {
                return call.result( call.query );
            }
            return null;
        }

    }


    /**
     * Implementation of {@link UnifyRule} that matches {@link org.polypheny.db.algebra.logical.LogicalTableScan}.
     */
    private static class ScanToProjectUnifyRule extends AbstractUnifyRule {

        public static final ScanToProjectUnifyRule INSTANCE = new ScanToProjectUnifyRule();


        private ScanToProjectUnifyRule() {
            super( any( MutableScan.class ), any( MutableProject.class ), 0 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            final MutableProject target = (MutableProject) call.target;
            final MutableScan query = (MutableScan) call.query;
            // We do not need to check query's parent type to avoid duplication of ProjectToProjectUnifyRule or FilterToProjectUnifyRule, since SubstitutionVisitor performs a top-down match.
            if ( !query.equals( target.getInput() ) ) {
                return null;
            }
            final RexShuttle shuttle = getRexShuttle( target );
            final RexBuilder rexBuilder = target.cluster.getRexBuilder();
            final List<RexNode> newProjects;
            try {
                newProjects = (List<RexNode>) shuttle.apply( rexBuilder.identityProjects( query.rowType ) );
            } catch ( MatchFailed e ) {
                return null;
            }
            final MutableProject newProject = MutableProject.of( query.rowType, target, newProjects );
            final MutableAlg newProject2 = MutableAlgs.strip( newProject );
            return call.result( newProject2 );
        }

    }


    /**
     * Implementation of {@link UnifyRule} that matches {@link org.polypheny.db.algebra.logical.LogicalProject}.
     */
    private static class ProjectToProjectUnifyRule extends AbstractUnifyRule {

        public static final ProjectToProjectUnifyRule INSTANCE = new ProjectToProjectUnifyRule();


        private ProjectToProjectUnifyRule() {
            super( operand( MutableProject.class, query( 0 ) ), operand( MutableProject.class, target( 0 ) ), 1 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            final MutableProject target = (MutableProject) call.target;
            final MutableProject query = (MutableProject) call.query;
            final RexShuttle shuttle = getRexShuttle( target );
            final List<RexNode> newProjects;
            try {
                newProjects = shuttle.apply( query.projects );
            } catch ( MatchFailed e ) {
                return null;
            }
            final MutableProject newProject = MutableProject.of( query.rowType, target, newProjects );
            final MutableAlg newProject2 = MutableAlgs.strip( newProject );
            return call.result( newProject2 );
        }

    }


    /**
     * Implementation of {@link UnifyRule} that matches a {@link MutableFilter} to a {@link MutableProject}.
     */
    private static class FilterToProjectUnifyRule extends AbstractUnifyRule {

        public static final FilterToProjectUnifyRule INSTANCE = new FilterToProjectUnifyRule();


        private FilterToProjectUnifyRule() {
            super( operand( MutableFilter.class, query( 0 ) ), operand( MutableProject.class, target( 0 ) ), 1 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            // Child of projectTarget is equivalent to child of filterQuery.
            try {
                // TODO: make sure that constants are ok
                final MutableProject target = (MutableProject) call.target;
                final RexShuttle shuttle = getRexShuttle( target );
                final RexNode newCondition;
                final MutableFilter query = (MutableFilter) call.query;
                try {
                    newCondition = query.condition.accept( shuttle );
                } catch ( MatchFailed e ) {
                    return null;
                }
                final MutableFilter newFilter = MutableFilter.of( target, newCondition );
                if ( query.getParent() instanceof MutableProject ) {
                    final MutableAlg inverse = invert( ((MutableProject) query.getParent()).getNamedProjects(), newFilter, shuttle );
                    return call.create( query.getParent() ).result( inverse );
                } else {
                    final MutableAlg inverse = invert( query, newFilter, target );
                    return call.result( inverse );
                }
            } catch ( MatchFailed e ) {
                return null;
            }
        }


        protected MutableAlg invert( List<Pair<RexNode, String>> namedProjects, MutableAlg input, RexShuttle shuttle ) {
            LOGGER.trace( "SubstitutionVisitor: invert:\nprojects: {}\ninput: {}\nproject: {}\n", namedProjects, input, shuttle );
            final List<RexNode> exprList = new ArrayList<>();
            final RexBuilder rexBuilder = input.cluster.getRexBuilder();
            final List<RexNode> projects = Pair.left( namedProjects );
            for ( RexNode expr : projects ) {
                exprList.add( rexBuilder.makeZeroLiteral( expr.getType() ) );
            }
            for ( Ord<RexNode> expr : Ord.zip( projects ) ) {
                final RexNode node = expr.e.accept( shuttle );
                if ( node == null ) {
                    throw MatchFailed.INSTANCE;
                }
                exprList.set( expr.i, node );
            }
            return MutableProject.of( input, exprList, Pair.right( namedProjects ) );
        }


        protected MutableAlg invert( MutableAlg model, MutableAlg input, MutableProject project ) {
            LOGGER.trace( "SubstitutionVisitor: invert:\nmodel: {}\ninput: {}\nproject: {}\n", model, input, project );
            if ( project.projects.size() < model.rowType.getFieldCount() ) {
                throw MatchFailed.INSTANCE;
            }
            final List<RexNode> exprList = new ArrayList<>();
            final RexBuilder rexBuilder = model.cluster.getRexBuilder();
            for ( AlgDataTypeField field : model.rowType.getFieldList() ) {
                exprList.add( rexBuilder.makeZeroLiteral( field.getType() ) );
            }
            for ( Ord<RexNode> expr : Ord.zip( project.projects ) ) {
                if ( expr.e instanceof RexInputRef ) {
                    final int target = ((RexInputRef) expr.e).getIndex();
                    exprList.set( target, rexBuilder.ensureType( expr.e.getType(), RexInputRef.of( expr.i, input.rowType ), false ) );
                } else {
                    throw MatchFailed.INSTANCE;
                }
            }
            return MutableProject.of( model.rowType, input, exprList );
        }

    }


    /**
     * Implementation of {@link UnifyRule} that matches a {@link MutableFilter}.
     */
    private static class FilterToFilterUnifyRule extends AbstractUnifyRule {

        public static final FilterToFilterUnifyRule INSTANCE = new FilterToFilterUnifyRule();


        private FilterToFilterUnifyRule() {
            super( operand( MutableFilter.class, query( 0 ) ),
                    operand( MutableFilter.class, target( 0 ) ), 1 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            // in.query can be rewritten in terms of in.target if its condition
            // is weaker. For example:
            //   query: SELECT * FROM t WHERE x = 1 AND y = 2
            //   target: SELECT * FROM t WHERE x = 1
            // transforms to
            //   result: SELECT * FROM (target) WHERE y = 2
            final MutableFilter query = (MutableFilter) call.query;
            final MutableFilter target = (MutableFilter) call.target;
            final MutableFilter newFilter = createFilter( call, query, target );
            if ( newFilter == null ) {
                return null;
            }
            return call.result( newFilter );
        }


        MutableFilter createFilter( UnifyRuleCall call, MutableFilter query, MutableFilter target ) {
            final RexNode newCondition = splitFilter( call.getSimplify(), query.condition, target.condition );
            if ( newCondition == null ) {
                // Could not map query onto target.
                return null;
            }
            if ( newCondition.isAlwaysTrue() ) {
                return target;
            }
            return MutableFilter.of( target, newCondition );
        }

    }


    /**
     * Implementation of {@link UnifyRule} that matches a {@link MutableProject} to a {@link MutableFilter}.
     */
    private static class ProjectToFilterUnifyRule extends AbstractUnifyRule {

        public static final ProjectToFilterUnifyRule INSTANCE = new ProjectToFilterUnifyRule();


        private ProjectToFilterUnifyRule() {
            super( operand( MutableProject.class, query( 0 ) ),
                    operand( MutableFilter.class, target( 0 ) ), 1 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            if ( call.query.getParent() instanceof MutableFilter ) {
                final UnifyRuleCall in2 = call.create( call.query.getParent() );
                final MutableFilter query = (MutableFilter) in2.query;
                final MutableFilter target = (MutableFilter) in2.target;
                final MutableFilter newFilter = FilterToFilterUnifyRule.INSTANCE.createFilter( call, query, target );
                if ( newFilter == null ) {
                    return null;
                }
                return in2.result( query.replaceInParent( newFilter ) );
            }
            return null;
        }

    }


    /**
     * Implementation of {@link UnifyRule} that matches a {@link org.polypheny.db.algebra.logical.LogicalAggregate} to a {@link org.polypheny.db.algebra.logical.LogicalAggregate},
     * provided that they have the same child.
     */
    private static class AggregateToAggregateUnifyRule extends AbstractUnifyRule {

        public static final AggregateToAggregateUnifyRule INSTANCE = new AggregateToAggregateUnifyRule();


        private AggregateToAggregateUnifyRule() {
            super( operand( MutableAggregate.class, query( 0 ) ),
                    operand( MutableAggregate.class, target( 0 ) ), 1 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            final MutableAggregate query = (MutableAggregate) call.query;
            final MutableAggregate target = (MutableAggregate) call.target;
            assert query != target;
            // in.query can be rewritten in terms of in.target if its groupSet is a subset, and its aggCalls are a superset. For example:
            //   query: SELECT x, COUNT(b) FROM t GROUP BY x
            //   target: SELECT x, y, SUM(a) AS s, COUNT(b) AS cb FROM t GROUP BY x, y
            // transforms to
            //   result: SELECT x, SUM(cb) FROM (target) GROUP BY x
            if ( query.getInput() != target.getInput() ) {
                return null;
            }
            if ( !target.groupSet.contains( query.groupSet ) ) {
                return null;
            }
            MutableAlg result = unifyAggregates( query, target );
            if ( result == null ) {
                return null;
            }
            return call.result( result );
        }

    }


    public static MutableAggregate permute( MutableAggregate aggregate, MutableAlg input, Mapping mapping ) {
        ImmutableBitSet groupSet = Mappings.apply( mapping, aggregate.groupSet );
        ImmutableList<ImmutableBitSet> groupSets = Mappings.apply2( mapping, aggregate.groupSets );
        List<AggregateCall> aggregateCalls = Util.transform( aggregate.aggCalls, call -> call.transform( mapping ) );
        return MutableAggregate.of( input, groupSet, groupSets, aggregateCalls );
    }


    public static MutableAlg unifyAggregates( MutableAggregate query, MutableAggregate target ) {
        if ( query.getGroupType() != Aggregate.Group.SIMPLE || target.getGroupType() != Aggregate.Group.SIMPLE ) {
            throw new AssertionError( Bug.CALCITE_461_FIXED );
        }
        MutableAlg result;
        if ( query.groupSet.equals( target.groupSet ) ) {
            // Same level of aggregation. Generate a project.
            final List<Integer> projects = new ArrayList<>();
            final int groupCount = query.groupSet.cardinality();
            for ( int i = 0; i < groupCount; i++ ) {
                projects.add( i );
            }
            for ( AggregateCall aggregateCall : query.aggCalls ) {
                int i = target.aggCalls.indexOf( aggregateCall );
                if ( i < 0 ) {
                    return null;
                }
                projects.add( groupCount + i );
            }
            result = MutableAlgs.createProject( target, projects );
        } else {
            // Target is coarser level of aggregation. Generate an aggregate.
            final ImmutableBitSet.Builder groupSet = ImmutableBitSet.builder();
            final List<Integer> targetGroupList = target.groupSet.asList();
            for ( int c : query.groupSet ) {
                int c2 = targetGroupList.indexOf( c );
                if ( c2 < 0 ) {
                    return null;
                }
                groupSet.set( c2 );
            }
            final List<AggregateCall> aggregateCalls = new ArrayList<>();
            for ( AggregateCall aggregateCall : query.aggCalls ) {
                if ( aggregateCall.isDistinct() ) {
                    return null;
                }
                int i = target.aggCalls.indexOf( aggregateCall );
                if ( i < 0 ) {
                    return null;
                }
                aggregateCalls.add(
                        AggregateCall.create(
                                getRollup( aggregateCall.getAggregation() ),
                                aggregateCall.isDistinct(),
                                aggregateCall.isApproximate(),
                                ImmutableList.of( target.groupSet.cardinality() + i ),
                                -1,
                                aggregateCall.collation,
                                aggregateCall.type,
                                aggregateCall.name ) );
            }
            result = MutableAggregate.of( target, groupSet.build(), null, aggregateCalls );
        }
        return MutableAlgs.createCastAlg( result, query.rowType, true );
    }


    /**
     * Implementation of {@link UnifyRule} that matches a {@link MutableAggregate} on a {@link MutableProject} query to an {@link MutableAggregate} target.
     *
     * The rule is necessary when we unify query=Aggregate(x) with target=Aggregate(x, y). Query will tend to have an extra Project(x) on its input, which this rule knows is safe to ignore.
     */
    private static class AggregateOnProjectToAggregateUnifyRule extends AbstractUnifyRule {

        public static final AggregateOnProjectToAggregateUnifyRule INSTANCE = new AggregateOnProjectToAggregateUnifyRule();


        private AggregateOnProjectToAggregateUnifyRule() {
            super( operand( MutableAggregate.class, operand( MutableProject.class, query( 0 ) ) ),
                    operand( MutableAggregate.class, target( 0 ) ), 1 );
        }


        @Override
        public UnifyResult apply( UnifyRuleCall call ) {
            final MutableAggregate query = (MutableAggregate) call.query;
            final MutableAggregate target = (MutableAggregate) call.target;
            if ( !(query.getInput() instanceof MutableProject) ) {
                return null;
            }
            final MutableProject project = (MutableProject) query.getInput();
            if ( project.getInput() != target.getInput() ) {
                return null;
            }
            final Mappings.TargetMapping mapping = project.getMapping();
            if ( mapping == null ) {
                return null;
            }
            final MutableAggregate aggregate2 = permute( query, project.getInput(), mapping.inverse() );
            final MutableAlg result = unifyAggregates( aggregate2, target );
            return result == null ? null : call.result( result );
        }

    }


    public static AggFunction getRollup( AggFunction aggregation ) {
        if ( Arrays.asList(
                OperatorName.SUM,
                OperatorName.MIN,
                OperatorName.MAX,
                OperatorName.SUM0,
                OperatorName.ANY_VALUE ).contains( aggregation.getOperatorName() ) ) {
            return aggregation;
        } else if ( aggregation.getOperatorName() == OperatorName.COUNT ) {
            return OperatorRegistry.getAgg( OperatorName.SUM0 );
        } else {
            return null;
        }
    }


    /**
     * Builds a shuttle that stores a list of expressions, and can map incoming expressions to references to them.
     */
    protected static RexShuttle getRexShuttle( MutableProject target ) {
        final Map<RexNode, Integer> map = new HashMap<>();
        for ( RexNode e : target.projects ) {
            map.put( e, map.size() );
        }
        return new RexShuttle() {
            @Override
            public RexNode visitInputRef( RexInputRef ref ) {
                final Integer integer = map.get( ref );
                if ( integer != null ) {
                    return new RexInputRef( integer, ref.getType() );
                }
                throw MatchFailed.INSTANCE;
            }


            @Override
            public RexNode visitCall( RexCall call ) {
                final Integer integer = map.get( call );
                if ( integer != null ) {
                    return new RexInputRef( integer, call.getType() );
                }
                return super.visitCall( call );
            }
        };
    }


    /**
     * Returns if one alg is weaker than another.
     */
    protected boolean isWeaker( MutableAlg alg0, MutableAlg alg ) {
        if ( alg0 == alg || equivalents.get( alg0 ).contains( alg ) ) {
            return false;
        }

        if ( !(alg0 instanceof MutableFilter) || !(alg instanceof MutableFilter) ) {
            return false;
        }

        if ( !alg.rowType.equals( alg0.rowType ) ) {
            return false;
        }

        final MutableAlg alg0input = ((MutableFilter) alg0).getInput();
        final MutableAlg alginput = ((MutableFilter) alg).getInput();
        if ( alg0input != alginput && !equivalents.get( alg0input ).contains( alginput ) ) {
            return false;
        }

        RexExecutorImpl rexImpl = (RexExecutorImpl) (alg.cluster.getPlanner().getExecutor());
        RexImplicationChecker rexImplicationChecker = new RexImplicationChecker( alg.cluster.getRexBuilder(), rexImpl, alg.rowType );

        return rexImplicationChecker.implies( ((MutableFilter) alg0).condition, ((MutableFilter) alg).condition );
    }


    /**
     * Returns whether two relational expressions have the same row-type.
     */
    public static boolean equalType( String desc0, MutableAlg alg0, String desc1, MutableAlg alg1, Litmus litmus ) {
        return AlgOptUtil.equal( desc0, alg0.rowType, desc1, alg1.rowType, litmus );
    }


    /**
     * Operand to a {@link UnifyRule}.
     */
    protected abstract static class Operand {

        protected final Class<? extends MutableAlg> clazz;


        protected Operand( Class<? extends MutableAlg> clazz ) {
            this.clazz = clazz;
        }


        public abstract boolean matches( SubstitutionVisitor visitor, MutableAlg alg );


        public boolean isWeaker( SubstitutionVisitor visitor, MutableAlg alg ) {
            return false;
        }

    }


    /**
     * Operand to a {@link UnifyRule} that matches a relational expression of a given type. It has zero or more child operands.
     */
    private static class InternalOperand extends Operand {

        private final List<Operand> inputs;


        InternalOperand( Class<? extends MutableAlg> clazz, List<Operand> inputs ) {
            super( clazz );
            this.inputs = inputs;
        }


        @Override
        public boolean matches( SubstitutionVisitor visitor, MutableAlg alg ) {
            return clazz.isInstance( alg ) && allMatch( visitor, inputs, alg.getInputs() );
        }


        @Override
        public boolean isWeaker( SubstitutionVisitor visitor, MutableAlg alg ) {
            return clazz.isInstance( alg ) && allWeaker( visitor, inputs, alg.getInputs() );
        }


        private static boolean allMatch( SubstitutionVisitor visitor, List<Operand> operands, List<MutableAlg> algs ) {
            if ( operands.size() != algs.size() ) {
                return false;
            }
            for ( Pair<Operand, MutableAlg> pair : Pair.zip( operands, algs ) ) {
                if ( !pair.left.matches( visitor, pair.right ) ) {
                    return false;
                }
            }
            return true;
        }


        private static boolean allWeaker( SubstitutionVisitor visitor, List<Operand> operands, List<MutableAlg> algs ) {
            if ( operands.size() != algs.size() ) {
                return false;
            }
            for ( Pair<Operand, MutableAlg> pair : Pair.zip( operands, algs ) ) {
                if ( !pair.left.isWeaker( visitor, pair.right ) ) {
                    return false;
                }
            }
            return true;
        }

    }


    /**
     * Operand to a {@link UnifyRule} that matches a relational expression of a given type.
     */
    private static class AnyOperand extends Operand {

        AnyOperand( Class<? extends MutableAlg> clazz ) {
            super( clazz );
        }


        @Override
        public boolean matches( SubstitutionVisitor visitor, MutableAlg alg ) {
            return clazz.isInstance( alg );
        }

    }


    /**
     * Operand that assigns a particular relational expression to a variable.
     *
     * It is applied to a descendant of the query, writes the operand into the slots array, and always matches.
     * There is a corresponding operand of type {@link TargetOperand} that checks whether its relational expression, a descendant of the target, is equivalent to this {@code QueryOperand}'s relational expression.
     */
    private static class QueryOperand extends Operand {

        private final int ordinal;


        protected QueryOperand( int ordinal ) {
            super( MutableAlg.class );
            this.ordinal = ordinal;
        }


        @Override
        public boolean matches( SubstitutionVisitor visitor, MutableAlg alg ) {
            visitor.slots[ordinal] = alg;
            return true;
        }

    }


    /**
     * Operand that checks that a relational expression matches the corresponding relational expression that was passed to a {@link QueryOperand}.
     */
    private static class TargetOperand extends Operand {

        private final int ordinal;


        protected TargetOperand( int ordinal ) {
            super( MutableAlg.class );
            this.ordinal = ordinal;
        }


        @Override
        public boolean matches( SubstitutionVisitor visitor, MutableAlg alg ) {
            final MutableAlg alg0 = visitor.slots[ordinal];
            assert alg0 != null : "QueryOperand should have been called first";
            return alg0 == alg || visitor.equivalents.get( alg0 ).contains( alg );
        }


        @Override
        public boolean isWeaker( SubstitutionVisitor visitor, MutableAlg alg ) {
            final MutableAlg alg0 = visitor.slots[ordinal];
            assert alg0 != null : "QueryOperand should have been called first";
            return visitor.isWeaker( alg0, alg );
        }

    }


    /**
     * Visitor that counts how many {@link QueryOperand} and {@link TargetOperand} in an operand tree.
     */
    private static class SlotCounter {

        int queryCount;
        int targetCount;


        void visit( Operand operand ) {
            if ( operand instanceof QueryOperand ) {
                ++queryCount;
            } else if ( operand instanceof TargetOperand ) {
                ++targetCount;
            } else if ( operand instanceof AnyOperand ) {
                // nothing
            } else {
                for ( Operand input : ((InternalOperand) operand).inputs ) {
                    visit( input );
                }
            }
        }

    }


    /**
     * Rule that converts a {@link org.polypheny.db.algebra.logical.LogicalFilter} on top of a {@link org.polypheny.db.algebra.logical.LogicalProject}
     * into a trivial filter (on a boolean column).
     */
    public static class FilterOnProjectRule extends AlgOptRule {

        public static final FilterOnProjectRule INSTANCE = new FilterOnProjectRule( AlgFactories.LOGICAL_BUILDER );


        /**
         * Creates a FilterOnProjectRule.
         *
         * @param algBuilderFactory Builder for relational expressions
         */
        public FilterOnProjectRule( AlgBuilderFactory algBuilderFactory ) {
            super(
                    operandJ(
                            LogicalFilter.class,
                            null,
                            filter -> filter.getCondition() instanceof RexInputRef,
                            some( operand( LogicalProject.class, any() ) ) ),
                    algBuilderFactory, null );
        }


        @Override
        public void onMatch( AlgOptRuleCall call ) {
            final LogicalFilter filter = call.alg( 0 );
            final LogicalProject project = call.alg( 1 );

            final List<RexNode> newProjects = new ArrayList<>( project.getProjects() );
            newProjects.add( filter.getCondition() );

            final AlgOptCluster cluster = filter.getCluster();
            AlgDataType newRowType =
                    cluster.getTypeFactory().builder()
                            .addAll( project.getRowType().getFieldList() )
                            .add( "condition", null, Util.last( newProjects ).getType() )
                            .build();
            final AlgNode newProject =
                    project.copy(
                            project.getTraitSet(),
                            project.getInput(),
                            newProjects,
                            newRowType );

            final RexInputRef newCondition = cluster.getRexBuilder().makeInputRef( newProject, newProjects.size() - 1 );

            call.transformTo( LogicalFilter.create( newProject, newCondition ) );
        }

    }

}


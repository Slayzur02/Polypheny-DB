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

package org.polypheny.db.adapter.druid;


import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Ord;
import org.joda.time.Interval;
import org.polypheny.db.adapter.DataContext;
import org.polypheny.db.algebra.AbstractAlgNode;
import org.polypheny.db.algebra.AlgFieldCollation;
import org.polypheny.db.algebra.AlgFieldCollation.Direction;
import org.polypheny.db.algebra.AlgNode;
import org.polypheny.db.algebra.AlgWriter;
import org.polypheny.db.algebra.constant.Kind;
import org.polypheny.db.algebra.core.Aggregate;
import org.polypheny.db.algebra.core.AggregateCall;
import org.polypheny.db.algebra.core.Filter;
import org.polypheny.db.algebra.core.Project;
import org.polypheny.db.algebra.core.Sort;
import org.polypheny.db.algebra.core.TableScan;
import org.polypheny.db.algebra.metadata.AlgMdUtil;
import org.polypheny.db.algebra.metadata.AlgMetadataQuery;
import org.polypheny.db.algebra.operators.OperatorName;
import org.polypheny.db.algebra.type.AlgDataType;
import org.polypheny.db.algebra.type.AlgDataTypeField;
import org.polypheny.db.config.PolyphenyDbConnectionConfig;
import org.polypheny.db.config.RuntimeConfig;
import org.polypheny.db.interpreter.BindableAlg;
import org.polypheny.db.interpreter.Bindables;
import org.polypheny.db.interpreter.Compiler;
import org.polypheny.db.interpreter.Node;
import org.polypheny.db.interpreter.Sink;
import org.polypheny.db.languages.OperatorRegistry;
import org.polypheny.db.nodes.Operator;
import org.polypheny.db.plan.AlgOptCluster;
import org.polypheny.db.plan.AlgOptCost;
import org.polypheny.db.plan.AlgOptPlanner;
import org.polypheny.db.plan.AlgOptRule;
import org.polypheny.db.plan.AlgOptTable;
import org.polypheny.db.plan.AlgTraitSet;
import org.polypheny.db.rex.RexCall;
import org.polypheny.db.rex.RexInputRef;
import org.polypheny.db.rex.RexLiteral;
import org.polypheny.db.rex.RexNode;
import org.polypheny.db.runtime.Hook;
import org.polypheny.db.schema.ScannableTable;
import org.polypheny.db.type.PolyType;
import org.polypheny.db.type.PolyTypeFamily;
import org.polypheny.db.util.ImmutableBitSet;
import org.polypheny.db.util.Litmus;
import org.polypheny.db.util.Pair;
import org.polypheny.db.util.Util;
import org.polypheny.db.util.ValidatorUtil;


/**
 * Relational expression representing a scan of a Druid data set.
 */
public class DruidQuery extends AbstractAlgNode implements BindableAlg {

    /**
     * Provides a standard list of supported Polypheny-DB operators that can be converted to Druid Expressions. This can be used as is or re-adapted based on underline engine operator syntax.
     */
    public static final List<DruidSqlOperatorConverter> DEFAULT_OPERATORS_LIST =
            ImmutableList.<DruidSqlOperatorConverter>builder()
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.EXP ), "exp" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.CONCAT ), "concat" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.DIVIDE_INTEGER ), "div" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.LIKE ), "like" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.LN ), "log" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.SQRT ), "sqrt" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.LOWER ), "lower" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.LOG10 ), "log10" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.REPLACE ), "replace" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.UPPER ), "upper" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.POWER ), "pow" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.ABS ), "abs" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.SIN ), "sin" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.COS ), "cos" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.TAN ), "tan" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.CASE ), "case_searched" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.CHAR_LENGTH ), "strlen" ) )
                    .add( new DirectOperatorConversion( OperatorRegistry.get( OperatorName.CHARACTER_LENGTH ), "strlen" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.EQUALS ), "==" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.NOT_EQUALS ), "!=" ) )
                    .add( new NaryOperatorConverter( OperatorRegistry.get( OperatorName.OR ), "||" ) )
                    .add( new NaryOperatorConverter( OperatorRegistry.get( OperatorName.AND ), "&&" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.LESS_THAN ), "<" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.LESS_THAN_OR_EQUAL ), "<=" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.GREATER_THAN ), ">" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.GREATER_THAN_OR_EQUAL ), ">=" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.PLUS ), "+" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.MINUS ), "-" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.MULTIPLY ), "*" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.DIVIDE ), "/" ) )
                    .add( new BinaryOperatorConversion( OperatorRegistry.get( OperatorName.MOD ), "%" ) )
                    .add( new DruidSqlCastConverter() )
                    .add( new ExtractOperatorConversion() )
                    .add( new UnaryPrefixOperatorConversion( OperatorRegistry.get( OperatorName.NOT ), "!" ) )
                    .add( new UnaryPrefixOperatorConversion( OperatorRegistry.get( OperatorName.UNARY_MINUS ), "-" ) )
                    .add( new UnarySuffixOperatorConversion( OperatorRegistry.get( OperatorName.IS_FALSE ), "<= 0" ) )
                    .add( new UnarySuffixOperatorConversion( OperatorRegistry.get( OperatorName.IS_NOT_TRUE ), "<= 0" ) )
                    .add( new UnarySuffixOperatorConversion( OperatorRegistry.get( OperatorName.IS_TRUE ), "> 0" ) )
                    .add( new UnarySuffixOperatorConversion( OperatorRegistry.get( OperatorName.IS_NOT_FALSE ), "> 0" ) )
                    .add( new UnarySuffixOperatorConversion( OperatorRegistry.get( OperatorName.IS_NULL ), "== null" ) )
                    .add( new UnarySuffixOperatorConversion( OperatorRegistry.get( OperatorName.IS_NOT_NULL ), "!= null" ) )
                    .add( new FloorOperatorConversion() )
                    .add( new CeilOperatorConversion() )
                    .add( new SubstringOperatorConversion() )
                    .build();
    protected QuerySpec querySpec;

    final AlgOptTable table;
    final DruidTable druidTable;
    final ImmutableList<Interval> intervals;
    final ImmutableList<AlgNode> algs;
    /**
     * This operator map provides DruidSqlOperatorConverter instance to convert a Polypheny-DB RexNode to Druid Expression when possible.
     */
    final Map<Operator, DruidSqlOperatorConverter> converterOperatorMap;

    private static final Pattern VALID_SIG = Pattern.compile( "sf?p?(a?|ah|ah?o)l?" );
    private static final String EXTRACT_COLUMN_NAME_PREFIX = "extract";
    private static final String FLOOR_COLUMN_NAME_PREFIX = "floor";
    protected static final String DRUID_QUERY_FETCH = "druid.query.fetch";
    private static final int DAYS_IN_TEN_YEARS = 10 * 365;


    /**
     * Creates a DruidQuery.
     *
     * @param cluster Cluster
     * @param traitSet Traits
     * @param table Table
     * @param druidTable Druid table
     * @param intervals Intervals for the query
     * @param algs Internal relational expressions
     * @param converterOperatorMap mapping of Polypheny-DB Sql Operator to Druid Expression API.
     */
    protected DruidQuery( AlgOptCluster cluster, AlgTraitSet traitSet, AlgOptTable table, DruidTable druidTable, List<Interval> intervals, List<AlgNode> algs, Map<Operator, DruidSqlOperatorConverter> converterOperatorMap ) {
        super( cluster, traitSet );
        this.table = table;
        this.druidTable = druidTable;
        this.intervals = ImmutableList.copyOf( intervals );
        this.algs = ImmutableList.copyOf( algs );
        this.converterOperatorMap = Objects.requireNonNull( converterOperatorMap, "Operator map can not be null" );
        assert isValid( Litmus.THROW, null );
    }


    /**
     * Returns whether a signature represents an sequence of relational operators that can be translated into a valid Druid query.
     */
    static boolean isValidSignature( String signature ) {
        return VALID_SIG.matcher( signature ).matches();
    }


    /**
     * Creates a DruidQuery.
     */
    public static DruidQuery create( AlgOptCluster cluster, AlgTraitSet traitSet, AlgOptTable table, DruidTable druidTable, List<AlgNode> algs ) {
        final ImmutableMap.Builder<Operator, DruidSqlOperatorConverter> mapBuilder = ImmutableMap.builder();
        for ( DruidSqlOperatorConverter converter : DEFAULT_OPERATORS_LIST ) {
            mapBuilder.put( converter.polyphenyDbOperator(), converter );
        }
        return create( cluster, traitSet, table, druidTable, druidTable.intervals, algs, mapBuilder.build() );
    }


    /**
     * Creates a DruidQuery.
     */
    public static DruidQuery create( AlgOptCluster cluster, AlgTraitSet traitSet, AlgOptTable table, DruidTable druidTable, List<AlgNode> algs, Map<Operator, DruidSqlOperatorConverter> converterOperatorMap ) {
        return create( cluster, traitSet, table, druidTable, druidTable.intervals, algs, converterOperatorMap );
    }


    /**
     * Creates a DruidQuery.
     */
    private static DruidQuery create( AlgOptCluster cluster, AlgTraitSet traitSet, AlgOptTable table, DruidTable druidTable, List<Interval> intervals, List<AlgNode> algs, Map<Operator, DruidSqlOperatorConverter> converterOperatorMap ) {
        return new DruidQuery( cluster, traitSet, table, druidTable, intervals, algs, converterOperatorMap );
    }


    /**
     * Extends a DruidQuery.
     */
    public static DruidQuery extendQuery( DruidQuery query, AlgNode r ) {
        final ImmutableList.Builder<AlgNode> builder = ImmutableList.builder();
        return DruidQuery.create( query.getCluster(), r.getTraitSet().replace( query.getConvention() ), query.getTable(), query.druidTable, query.intervals, builder.addAll( query.algs ).add( r ).build(), query.getOperatorConversionMap() );
    }


    /**
     * Extends a DruidQuery.
     */
    public static DruidQuery extendQuery( DruidQuery query, List<Interval> intervals ) {
        return DruidQuery.create( query.getCluster(), query.getTraitSet(), query.getTable(), query.druidTable, intervals, query.algs, query.getOperatorConversionMap() );
    }


    /**
     * @param rexNode leaf Input Ref to Druid Column
     * @param rowType row type
     * @param druidQuery druid query
     * @return {@link Pair} of Column name and Extraction Function on the top of the input ref or {@link Pair of(null, null)} when can not translate to valid Druid column
     */
    protected static Pair<String, ExtractionFunction> toDruidColumn( RexNode rexNode, AlgDataType rowType, DruidQuery druidQuery ) {
        final String columnName;
        final ExtractionFunction extractionFunction;
        final Granularity granularity;
        switch ( rexNode.getKind() ) {
            case INPUT_REF:
                columnName = extractColumnName( rexNode, rowType, druidQuery );
                if ( rexNode.getType().getPolyType() == PolyType.DATE
                        || rexNode.getType().getPolyType() == PolyType.TIMESTAMP
                        || rexNode.getType().getPolyType() == PolyType.TIMESTAMP_WITH_LOCAL_TIME_ZONE ) {
                    // Use UTC for DATE and TIMESTAMP types
                    extractionFunction = TimeExtractionFunction.createDefault( DateTimeUtils.UTC_ZONE.getID() );
                } else {
                    extractionFunction = null;
                }
                break;
            case EXTRACT:
                granularity = DruidDateTimeUtils.extractGranularity( rexNode, druidQuery.getConnectionConfig().timeZone() );
                if ( granularity == null ) {
                    // unknown Granularity
                    return Pair.of( null, null );
                }
                if ( !TimeExtractionFunction.isValidTimeExtract( (RexCall) rexNode ) ) {
                    return Pair.of( null, null );
                }
                RexNode extractValueNode = ((RexCall) rexNode).getOperands().get( 1 );
                if ( extractValueNode.getType().getPolyType() == PolyType.DATE || extractValueNode.getType().getPolyType() == PolyType.TIMESTAMP ) {
                    // Use 'UTC' at the extraction level
                    extractionFunction = TimeExtractionFunction.createExtractFromGranularity( granularity, DateTimeUtils.UTC_ZONE.getID() );
                    columnName = extractColumnName( extractValueNode, rowType, druidQuery );
                } else if ( extractValueNode.getType().getPolyType() == PolyType.TIMESTAMP_WITH_LOCAL_TIME_ZONE ) {
                    // Use local time zone at the extraction level
                    extractionFunction = TimeExtractionFunction.createExtractFromGranularity( granularity, druidQuery.getConnectionConfig().timeZone() );
                    columnName = extractColumnName( extractValueNode, rowType, druidQuery );
                } else {
                    return Pair.of( null, null );
                }
                break;
            case FLOOR:
                granularity = DruidDateTimeUtils.extractGranularity( rexNode, druidQuery.getConnectionConfig().timeZone() );
                if ( granularity == null ) {
                    // unknown Granularity
                    return Pair.of( null, null );
                }
                if ( !TimeExtractionFunction.isValidTimeFloor( (RexCall) rexNode ) ) {
                    return Pair.of( null, null );
                }
                RexNode floorValueNode = ((RexCall) rexNode).getOperands().get( 0 );
                if ( floorValueNode.getType().getPolyType() == PolyType.DATE
                        || floorValueNode.getType().getPolyType() == PolyType.TIMESTAMP
                        || floorValueNode.getType().getPolyType() == PolyType.TIMESTAMP_WITH_LOCAL_TIME_ZONE ) {
                    // Use 'UTC' at the extraction level, since all datetime types are represented in 'UTC'
                    extractionFunction = TimeExtractionFunction.createFloorFromGranularity( granularity, DateTimeUtils.UTC_ZONE.getID() );
                    columnName = extractColumnName( floorValueNode, rowType, druidQuery );
                } else {
                    return Pair.of( null, null );
                }
                break;
            case CAST:
                // CASE we have a cast over InputRef. Check that cast is valid
                if ( !isValidLeafCast( rexNode ) ) {
                    return Pair.of( null, null );
                }
                columnName = extractColumnName( ((RexCall) rexNode).getOperands().get( 0 ), rowType, druidQuery );
                // CASE CAST to TIME/DATE need to make sure that we have valid extraction fn
                final PolyType toTypeName = rexNode.getType().getPolyType();
                if ( toTypeName == PolyType.DATE || toTypeName == PolyType.TIMESTAMP || toTypeName == PolyType.TIMESTAMP_WITH_LOCAL_TIME_ZONE ) {
                    extractionFunction = TimeExtractionFunction.translateCastToTimeExtract( rexNode, TimeZone.getTimeZone( druidQuery.getConnectionConfig().timeZone() ) );
                    if ( extractionFunction == null ) {
                        // no extraction Function means cast is not valid thus bail out
                        return Pair.of( null, null );
                    }
                } else {
                    extractionFunction = null;
                }
                break;
            default:
                return Pair.of( null, null );
        }
        return Pair.of( columnName, extractionFunction );
    }


    /**
     * @param rexNode rexNode
     * @return true if the operand is an inputRef and it is a valid Druid Cast operation
     */
    private static boolean isValidLeafCast( RexNode rexNode ) {
        assert rexNode.isA( Kind.CAST );
        final RexNode input = ((RexCall) rexNode).getOperands().get( 0 );
        if ( !input.isA( Kind.INPUT_REF ) ) {
            // it is not a leaf cast don't bother going further.
            return false;
        }
        final PolyType toTypeName = rexNode.getType().getPolyType();
        if ( toTypeName.getFamily() == PolyTypeFamily.CHARACTER ) {
            // CAST of input to character type
            return true;
        }
        if ( toTypeName.getFamily() == PolyTypeFamily.NUMERIC ) {
            // CAST of input to numeric type, it is part of a bounded comparison
            return true;
        }
        if ( toTypeName.getFamily() == PolyTypeFamily.TIMESTAMP || toTypeName.getFamily() == PolyTypeFamily.DATETIME ) {
            // CAST of literal to timestamp type
            return true;
        }
        if ( toTypeName.getFamily().contains( input.getType() ) ) {
            // same type it is okay to push it
            return true;
        }
        // Currently other CAST operations cannot be pushed to Druid
        return false;

    }


    /**
     * @param rexNode Druid input ref node
     * @param rowType rowType
     * @param query Druid Query
     * @return Druid column name or null when not possible to translate.
     */
    @Nullable
    protected static String extractColumnName( RexNode rexNode, AlgDataType rowType, DruidQuery query ) {
        if ( rexNode.getKind() == Kind.INPUT_REF ) {
            final RexInputRef ref = (RexInputRef) rexNode;
            final String columnName = rowType.getFieldNames().get( ref.getIndex() );
            if ( columnName == null ) {
                return null;
            }
            // Polypheny-DB has this un-direct renaming of timestampFieldName to native druid `__time`
            if ( query.getDruidTable().timestampFieldName.equals( columnName ) ) {
                return DruidTable.DEFAULT_TIMESTAMP_COLUMN;
            }
            return columnName;
        }
        return null;
    }


    /**
     * Equivalent of String.format(Locale.ENGLISH, message, formatArgs).
     */
    public static String format( String message, Object... formatArgs ) {
        return String.format( Locale.ENGLISH, message, formatArgs );
    }


    /**
     * Returns a string describing the operations inside this query.
     *
     * For example, "sfpahol" means {@link TableScan} (s)
     * followed by {@link Filter} (f)
     * followed by {@link Project} (p)
     * followed by {@link Aggregate} (a)
     * followed by {@link Filter} (h)
     * followed by {@link Project} (o)
     * followed by {@link Sort} (l).
     *
     * @see #isValidSignature(String)
     */
    String signature() {
        final StringBuilder b = new StringBuilder();
        boolean flag = false;
        for ( AlgNode alg : algs ) {
            b.append( alg instanceof TableScan ? 's'
                    : (alg instanceof Project && flag) ? 'o'
                            : (alg instanceof Filter && flag) ? 'h'
                                    : alg instanceof Aggregate ? 'a'
                                            : alg instanceof Filter ? 'f'
                                                    : alg instanceof Sort ? 'l'
                                                            : alg instanceof Project ? 'p'
                                                                    : '!' );
            flag = flag || alg instanceof Aggregate;
        }
        return b.toString();
    }


    @Override
    public boolean isValid( Litmus litmus, Context context ) {
        if ( !super.isValid( litmus, context ) ) {
            return false;
        }
        final String signature = signature();
        if ( !isValidSignature( signature ) ) {
            return litmus.fail( "invalid signature [{}]", signature );
        }
        if ( algs.isEmpty() ) {
            return litmus.fail( "must have at least one rel" );
        }
        for ( int i = 0; i < algs.size(); i++ ) {
            final AlgNode r = algs.get( i );
            if ( i == 0 ) {
                if ( !(r instanceof TableScan) ) {
                    return litmus.fail( "first alg must be TableScan, was ", r );
                }
                if ( r.getTable() != table ) {
                    return litmus.fail( "first alg must be based on table table" );
                }
            } else {
                final List<AlgNode> inputs = r.getInputs();
                if ( inputs.size() != 1 || inputs.get( 0 ) != algs.get( i - 1 ) ) {
                    return litmus.fail( "each alg must have a single input" );
                }
                if ( r instanceof Aggregate ) {
                    final Aggregate aggregate = (Aggregate) r;
                    if ( aggregate.getGroupSets().size() != 1 || aggregate.indicator ) {
                        return litmus.fail( "no grouping sets" );
                    }
                }
                if ( r instanceof Filter ) {
                    final Filter filter = (Filter) r;
                    final DruidJsonFilter druidJsonFilter = DruidJsonFilter.toDruidFilters( filter.getCondition(), filter.getInput().getRowType(), this );
                    if ( druidJsonFilter == null ) {
                        return litmus.fail( "invalid filter [{}]", filter.getCondition() );
                    }
                }
                if ( r instanceof Sort ) {
                    final Sort sort = (Sort) r;
                    if ( sort.offset != null && RexLiteral.intValue( sort.offset ) != 0 ) {
                        return litmus.fail( "offset not supported" );
                    }
                }
            }
        }
        return true;
    }


    protected Map<Operator, DruidSqlOperatorConverter> getOperatorConversionMap() {
        return converterOperatorMap;
    }


    @Override
    public AlgNode copy( AlgTraitSet traitSet, List<AlgNode> inputs ) {
        assert inputs.isEmpty();
        return this;
    }


    @Override
    public AlgDataType deriveRowType() {
        return getCluster().getTypeFactory().createStructType(
                Pair.right( Util.last( algs ).getRowType().getFieldList() ),
                getQuerySpec().fieldNames );
    }


    public TableScan getTableScan() {
        return (TableScan) algs.get( 0 );
    }


    public AlgNode getTopNode() {
        return Util.last( algs );
    }


    @Override
    public AlgOptTable getTable() {
        return table;
    }


    public DruidTable getDruidTable() {
        return druidTable;
    }


    @Override
    public AlgWriter explainTerms( AlgWriter pw ) {
        for ( AlgNode alg : algs ) {
            if ( alg instanceof TableScan ) {
                TableScan tableScan = (TableScan) alg;
                pw.item( "table", tableScan.getTable().getQualifiedName() );
                pw.item( "intervals", intervals );
            } else if ( alg instanceof Filter ) {
                pw.item( "filter", ((Filter) alg).getCondition() );
            } else if ( alg instanceof Project ) {
                if ( ((Project) alg).getInput() instanceof Aggregate ) {
                    pw.item( "post_projects", ((Project) alg).getProjects() );
                } else {
                    pw.item( "projects", ((Project) alg).getProjects() );
                }
            } else if ( alg instanceof Aggregate ) {
                final Aggregate aggregate = (Aggregate) alg;
                pw.item( "groups", aggregate.getGroupSet() ).item( "aggs", aggregate.getAggCallList() );
            } else if ( alg instanceof Sort ) {
                final Sort sort = (Sort) alg;
                for ( Ord<AlgFieldCollation> ord : Ord.zip( sort.collation.getFieldCollations() ) ) {
                    pw.item( "sort" + ord.i, ord.e.getFieldIndex() );
                }
                for ( Ord<AlgFieldCollation> ord : Ord.zip( sort.collation.getFieldCollations() ) ) {
                    pw.item( "dir" + ord.i, ord.e.shortString() );
                }
                pw.itemIf( "fetch", sort.fetch, sort.fetch != null );
            } else {
                throw new AssertionError( "rel type not supported in Druid query " + alg );
            }
        }
        return pw;
    }


    @Override
    public AlgOptCost computeSelfCost( AlgOptPlanner planner, AlgMetadataQuery mq ) {
        return Util.last( algs ).computeSelfCost( planner, mq )
                // Cost increases with the number of fields queried. A plan returning 100 or more columns will have 2x the cost of a plan returning 2 columns. A plan where all extra columns are pruned will be preferred.
                .multiplyBy( AlgMdUtil.linear( querySpec.fieldNames.size(), 2, 100, 1d, 2d ) )
                .multiplyBy( getQueryTypeCostMultiplier() )
                // A Scan leaf filter is better than having filter spec if possible.
                .multiplyBy( algs.size() > 1 && algs.get( 1 ) instanceof Filter ? 0.5 : 1.0 )
                // a plan with sort pushed to druid is better than doing sort outside of druid
                .multiplyBy( Util.last( algs ) instanceof Sort ? 0.1 : 1.0 )
                .multiplyBy( getIntervalCostMultiplier() );
    }


    private double getIntervalCostMultiplier() {
        int days = 0;
        for ( Interval interval : intervals ) {
            days += interval.toDuration().getStandardDays();
        }
        // Cost increases with the wider interval being queries. A plan querying 10 or more years of data will have 10x the cost of a plan returning 1 day data. A plan where least interval is queries will be preferred.
        return AlgMdUtil.linear( days, 1, DAYS_IN_TEN_YEARS, 0.1d, 1d );
    }


    private double getQueryTypeCostMultiplier() {
        // Cost of Select > GroupBy > Timeseries > TopN
        switch ( querySpec.queryType ) {
            case SELECT:
                return .1;
            case GROUP_BY:
                return .08;
            case TIMESERIES:
                return .06;
            case TOP_N:
                return .04;
            default:
                return .2;
        }
    }


    @Override
    public void register( AlgOptPlanner planner ) {
        for ( AlgOptRule rule : DruidRules.RULES ) {
            planner.addRule( rule );
        }
        for ( AlgOptRule rule : Bindables.RULES ) {
            planner.addRule( rule );
        }
    }


    @Override
    public String algCompareString() {
        return this.getClass().getSimpleName() + "$" +
                String.join( ".", table.getQualifiedName() ) + "$" +
                (algs != null ? algs.stream().map( AlgNode::algCompareString ).collect( Collectors.joining( "$" ) ) : "") + "&";
    }


    @Override
    public Class<Object[]> getElementType() {
        return Object[].class;
    }


    @Override
    public Enumerable<Object[]> bind( DataContext dataContext ) {
        return table.unwrap( ScannableTable.class ).scan( dataContext );
    }


    @Override
    public Node implement( InterpreterImplementor implementor ) {
        return new DruidQueryNode( implementor.compiler, this );
    }


    public QuerySpec getQuerySpec() {
        if ( querySpec == null ) {
            querySpec = deriveQuerySpec();
            assert querySpec != null : this;
        }
        return querySpec;
    }


    protected QuerySpec deriveQuerySpec() {
        final AlgDataType rowType = table.getRowType();
        int i = 1;

        Filter filterRel = null;
        if ( i < algs.size() && algs.get( i ) instanceof Filter ) {
            filterRel = (Filter) algs.get( i++ );
        }

        Project project = null;
        if ( i < algs.size() && algs.get( i ) instanceof Project ) {
            project = (Project) algs.get( i++ );
        }

        ImmutableBitSet groupSet = null;
        List<AggregateCall> aggCalls = null;
        List<String> aggNames = null;
        if ( i < algs.size() && algs.get( i ) instanceof Aggregate ) {
            final Aggregate aggregate = (Aggregate) algs.get( i++ );
            groupSet = aggregate.getGroupSet();
            aggCalls = aggregate.getAggCallList();
            aggNames = Util.skip( aggregate.getRowType().getFieldNames(), groupSet.cardinality() );
        }

        Filter havingFilter = null;
        if ( i < algs.size() && algs.get( i ) instanceof Filter ) {
            havingFilter = (Filter) algs.get( i++ );
        }

        Project postProject = null;
        if ( i < algs.size() && algs.get( i ) instanceof Project ) {
            postProject = (Project) algs.get( i++ );
        }

        List<Integer> collationIndexes = null;
        List<Direction> collationDirections = null;
        ImmutableBitSet.Builder numericCollationBitSetBuilder = ImmutableBitSet.builder();
        Integer fetch = null;
        if ( i < algs.size() && algs.get( i ) instanceof Sort ) {
            final Sort sort = (Sort) algs.get( i++ );
            collationIndexes = new ArrayList<>();
            collationDirections = new ArrayList<>();
            for ( AlgFieldCollation fCol : sort.collation.getFieldCollations() ) {
                collationIndexes.add( fCol.getFieldIndex() );
                collationDirections.add( fCol.getDirection() );
                if ( sort.getRowType().getFieldList().get( fCol.getFieldIndex() ).getType().getFamily() == PolyTypeFamily.NUMERIC ) {
                    numericCollationBitSetBuilder.set( fCol.getFieldIndex() );
                }
            }
            fetch = sort.fetch != null ? RexLiteral.intValue( sort.fetch ) : null;
        }

        if ( i != algs.size() ) {
            throw new AssertionError( "could not implement all rels" );
        }

        return getQuery( rowType, filterRel, project, groupSet, aggCalls, aggNames, collationIndexes, collationDirections, numericCollationBitSetBuilder.build(), fetch, postProject, havingFilter );
    }


    public QueryType getQueryType() {
        return getQuerySpec().queryType;
    }


    public String getQueryString() {
        return getQuerySpec().queryString;
    }


    protected PolyphenyDbConnectionConfig getConnectionConfig() {
        return getCluster().getPlanner().getContext().unwrap( PolyphenyDbConnectionConfig.class );
    }


    /**
     * Translates Filter alg to Druid Filter Json object if possible. Currently Filter alg input has to be Druid Table scan
     *
     * @param filterRel input filter rel
     * @param druidQuery Druid query
     * @return DruidJson Filter or null if can not translate one of filters
     */
    @Nullable
    private static DruidJsonFilter computeFilter( @Nullable Filter filterRel, DruidQuery druidQuery ) {
        if ( filterRel == null ) {
            return null;
        }
        final RexNode filter = filterRel.getCondition();
        final AlgDataType inputRowType = filterRel.getInput().getRowType();
        if ( filter != null ) {
            return DruidJsonFilter.toDruidFilters( filter, inputRowType, druidQuery );
        }
        return null;
    }


    /**
     * Translates list of projects to Druid Column names and Virtual Columns if any. We can not use {@link Pair#zip(Object[], Object[])}, since size can be different
     *
     * @param projectRel Project Rel
     * @param druidQuery Druid query
     * @return Pair of list of Druid Columns and Expression Virtual Columns or null when can not translate one of the projects.
     */
    @Nullable
    protected static Pair<List<String>, List<VirtualColumn>> computeProjectAsScan( @Nullable Project projectRel, AlgDataType inputRowType, DruidQuery druidQuery ) {
        if ( projectRel == null ) {
            return null;
        }
        final Set<String> usedFieldNames = new HashSet<>();
        final ImmutableList.Builder<VirtualColumn> virtualColumnsBuilder = ImmutableList.builder();
        final ImmutableList.Builder<String> projectedColumnsBuilder = ImmutableList.builder();
        final List<RexNode> projects = projectRel.getProjects();
        for ( RexNode project : projects ) {
            Pair<String, ExtractionFunction> druidColumn = toDruidColumn( project, inputRowType, druidQuery );
            if ( druidColumn.left == null || druidColumn.right != null ) {
                // It is a complex project pushed as expression
                final String expression = DruidExpressions.toDruidExpression( project, inputRowType, druidQuery );
                if ( expression == null ) {
                    return null;
                }
                final String virColName = ValidatorUtil.uniquify( "vc", usedFieldNames, ValidatorUtil.EXPR_SUGGESTER );
                virtualColumnsBuilder.add( VirtualColumn.builder()
                        .withName( virColName )
                        .withExpression( expression ).withType( DruidExpressions.EXPRESSION_TYPES.get( project.getType().getPolyType() ) )
                        .build() );
                usedFieldNames.add( virColName );
                projectedColumnsBuilder.add( virColName );
            } else {
                // simple inputRef or extractable function
                if ( usedFieldNames.contains( druidColumn.left ) ) {
                    final String virColName = ValidatorUtil.uniquify( "vc", usedFieldNames, ValidatorUtil.EXPR_SUGGESTER );
                    virtualColumnsBuilder.add( VirtualColumn.builder()
                            .withName( virColName )
                            .withExpression( DruidExpressions.fromColumn( druidColumn.left ) ).withType( DruidExpressions.EXPRESSION_TYPES.get( project.getType().getPolyType() ) )
                            .build() );
                    usedFieldNames.add( virColName );
                    projectedColumnsBuilder.add( virColName );
                } else {
                    projectedColumnsBuilder.add( druidColumn.left );
                    usedFieldNames.add( druidColumn.left );
                }
            }
        }
        return Pair.of( projectedColumnsBuilder.build(), virtualColumnsBuilder.build() );
    }


    /**
     * Computes the project group set.
     *
     * @param projectNode Project under the Aggregates if any
     * @param groupSet Ids of grouping keys as they are listed in {@code projects} list
     * @param inputRowType Input row type under the project
     * @param druidQuery Druid Query
     * @return A list of {@link DimensionSpec} containing the group by dimensions, and a list of {@link VirtualColumn} containing Druid virtual column projections; or null, if translation is not possible. Note that the size of lists can be different.
     */
    @Nullable
    protected static Pair<List<DimensionSpec>, List<VirtualColumn>> computeProjectGroupSet( @Nullable Project projectNode, ImmutableBitSet groupSet, AlgDataType inputRowType, DruidQuery druidQuery ) {
        final List<DimensionSpec> dimensionSpecList = new ArrayList<>();
        final List<VirtualColumn> virtualColumnList = new ArrayList<>();
        final Set<String> usedFieldNames = new HashSet<>();
        for ( int groupKey : groupSet ) {
            final DimensionSpec dimensionSpec;
            final RexNode project;
            if ( projectNode == null ) {
                project = RexInputRef.of( groupKey, inputRowType );
            } else {
                project = projectNode.getProjects().get( groupKey );
            }

            Pair<String, ExtractionFunction> druidColumn = toDruidColumn( project, inputRowType, druidQuery );
            if ( druidColumn.left != null && druidColumn.right == null ) {
                // SIMPLE INPUT REF
                dimensionSpec = new DefaultDimensionSpec( druidColumn.left, druidColumn.left, DruidExpressions.EXPRESSION_TYPES.get( project.getType().getPolyType() ) );
                usedFieldNames.add( druidColumn.left );
            } else if ( druidColumn.left != null && druidColumn.right != null ) {
                // CASE it is an extraction Dimension
                final String columnPrefix;
                // TODO: Remove it! if else statement is not really needed it is here to make tests pass.
                if ( project.getKind() == Kind.EXTRACT ) {
                    columnPrefix = EXTRACT_COLUMN_NAME_PREFIX + "_" + Objects
                            .requireNonNull( DruidDateTimeUtils
                                    .extractGranularity( project, druidQuery.getConnectionConfig().timeZone() )
                                    .getType().lowerName );
                } else if ( project.getKind() == Kind.FLOOR ) {
                    columnPrefix =
                            FLOOR_COLUMN_NAME_PREFIX + "_" + Objects
                                    .requireNonNull( DruidDateTimeUtils
                                            .extractGranularity( project, druidQuery.getConnectionConfig().timeZone() )
                                            .getType().lowerName );
                } else {
                    columnPrefix = "extract";
                }
                final String uniqueExtractColumnName = ValidatorUtil.uniquify( columnPrefix, usedFieldNames, ValidatorUtil.EXPR_SUGGESTER );
                dimensionSpec = new ExtractionDimensionSpec( druidColumn.left, druidColumn.right, uniqueExtractColumnName );
                usedFieldNames.add( uniqueExtractColumnName );
            } else {
                // CASE it is Expression
                final String expression = DruidExpressions.toDruidExpression( project, inputRowType, druidQuery );
                if ( Strings.isNullOrEmpty( expression ) ) {
                    return null;
                }
                final String name = ValidatorUtil.uniquify( "vc", usedFieldNames, ValidatorUtil.EXPR_SUGGESTER );
                VirtualColumn vc = new VirtualColumn( name, expression, DruidExpressions.EXPRESSION_TYPES.get( project.getType().getPolyType() ) );
                virtualColumnList.add( vc );
                dimensionSpec = new DefaultDimensionSpec( name, name, DruidExpressions.EXPRESSION_TYPES.get( project.getType().getPolyType() ) );
                usedFieldNames.add( name );

            }

            dimensionSpecList.add( dimensionSpec );
        }
        return Pair.of( dimensionSpecList, virtualColumnList );
    }


    /**
     * Translates aggregate calls to Druid {@link JsonAggregation}s when possible.
     *
     * @param aggCalls List of AggregateCalls to translate
     * @param aggNames List of aggregate names
     * @param project Input project under the aggregate calls, or null if we have {@link TableScan} immediately under the {@link Aggregate}
     * @param druidQuery Druid Query Rel
     * @return List of valid Druid {@link JsonAggregation}s, or null if any of the aggregates is not supported
     */
    @Nullable
    protected static List<JsonAggregation> computeDruidJsonAgg( List<AggregateCall> aggCalls, List<String> aggNames, @Nullable Project project, DruidQuery druidQuery ) {
        final List<JsonAggregation> aggregations = new ArrayList<>();
        for ( Pair<AggregateCall, String> agg : Pair.zip( aggCalls, aggNames ) ) {
            final String fieldName;
            final String expression;
            final AggregateCall aggCall = agg.left;
            final RexNode filterNode;
            // Type check First
            final AlgDataType type = aggCall.getType();
            final PolyType polyType = type.getPolyType();
            final boolean isNotAcceptedType;
            if ( PolyTypeFamily.APPROXIMATE_NUMERIC.getTypeNames().contains( polyType ) || PolyTypeFamily.INTEGER.getTypeNames().contains( polyType ) ) {
                isNotAcceptedType = false;
            } else if ( PolyTypeFamily.EXACT_NUMERIC.getTypeNames().contains( polyType ) && (type.getScale() == 0 || RuntimeConfig.APPROXIMATE_DECIMAL.getBoolean()) ) {
                // Decimal, If scale is zero or we allow approximating decimal, we can proceed
                isNotAcceptedType = false;
            } else {
                isNotAcceptedType = true;
            }
            if ( isNotAcceptedType ) {
                return null;
            }

            // Extract filters
            if ( project != null && aggCall.hasFilter() ) {
                filterNode = project.getProjects().get( aggCall.filterArg );
            } else {
                filterNode = null;
            }
            if ( aggCall.getArgList().size() == 0 ) {
                fieldName = null;
                expression = null;
            } else {
                int index = Iterables.getOnlyElement( aggCall.getArgList() );
                if ( project == null ) {
                    fieldName = druidQuery.table.getRowType().getFieldNames().get( index );
                    expression = null;
                } else {
                    final RexNode rexNode = project.getProjects().get( index );
                    final AlgDataType inputRowType = project.getInput().getRowType();
                    if ( rexNode.isA( Kind.INPUT_REF ) ) {
                        expression = null;
                        fieldName = extractColumnName( rexNode, inputRowType, druidQuery );
                    } else {
                        expression = DruidExpressions.toDruidExpression( rexNode, inputRowType, druidQuery );
                        if ( Strings.isNullOrEmpty( expression ) ) {
                            return null;
                        }
                        fieldName = null;
                    }
                }
                // One should be not null and the other should be null.
                assert expression == null ^ fieldName == null;
            }
            final JsonAggregation jsonAggregation = getJsonAggregation( agg.right, agg.left, filterNode, fieldName, expression, druidQuery );
            if ( jsonAggregation == null ) {
                return null;
            }
            aggregations.add( jsonAggregation );
        }
        return aggregations;
    }


    protected QuerySpec getQuery(
            AlgDataType rowType, Filter filter, Project project, ImmutableBitSet groupSet, List<AggregateCall> aggCalls, List<String> aggNames, List<Integer> collationIndexes, List<Direction> collationDirections,
            ImmutableBitSet numericCollationIndexes, Integer fetch, Project postProject, Filter havingFilter ) {
        // Handle filter
        final DruidJsonFilter jsonFilter = computeFilter( filter, this );

        if ( groupSet == null ) {
            // It is Scan Query since no Grouping
            assert aggCalls == null;
            assert aggNames == null;
            assert collationIndexes == null || collationIndexes.isEmpty();
            assert collationDirections == null || collationDirections.isEmpty();
            final List<String> scanColumnNames;
            final List<VirtualColumn> virtualColumnList = new ArrayList<>();
            if ( project != null ) {
                // project some fields only
                Pair<List<String>, List<VirtualColumn>> projectResult = computeProjectAsScan( project, project.getInput().getRowType(), this );
                scanColumnNames = projectResult.left;
                virtualColumnList.addAll( projectResult.right );
            } else {
                // Scan all the fields
                scanColumnNames = rowType.getFieldNames();
            }
            final ScanQuery scanQuery = new ScanQuery( druidTable.dataSource, intervals, jsonFilter, virtualColumnList, scanColumnNames, fetch );
            return new QuerySpec( QueryType.SCAN, scanQuery.toQuery(), scanColumnNames );
        }

        // At this Stage we have a valid Aggregate thus Query is one of Timeseries, TopN, or GroupBy Handling aggregate and sort is more complex, since we need to extract the
        // conditions to know whether the query will be executed as a Timeseries, TopN, or GroupBy in Druid
        assert aggCalls != null;
        assert aggNames != null;
        assert aggCalls.size() == aggNames.size();

        final List<JsonExpressionPostAgg> postAggs = new ArrayList<>();
        final JsonLimit limit;
        final AlgDataType aggInputRowType = table.getRowType();
        final List<String> aggregateStageFieldNames = new ArrayList<>();

        Pair<List<DimensionSpec>, List<VirtualColumn>> projectGroupSet = computeProjectGroupSet( project, groupSet, aggInputRowType, this );

        final List<DimensionSpec> groupByKeyDims = projectGroupSet.left;
        final List<VirtualColumn> virtualColumnList = projectGroupSet.right;
        for ( DimensionSpec dim : groupByKeyDims ) {
            aggregateStageFieldNames.add( dim.getOutputName() );
        }
        final List<JsonAggregation> aggregations = computeDruidJsonAgg( aggCalls, aggNames, project, this );
        for ( JsonAggregation jsonAgg : aggregations ) {
            aggregateStageFieldNames.add( jsonAgg.name );
        }

        final DruidJsonFilter havingJsonFilter;
        if ( havingFilter != null ) {
            havingJsonFilter = DruidJsonFilter.toDruidFilters( havingFilter.getCondition(), havingFilter.getInput().getRowType(), this );
        } else {
            havingJsonFilter = null;
        }

        // Then we handle projects after aggregates as Druid Post Aggregates
        final List<String> postAggregateStageFieldNames;
        if ( postProject != null ) {
            final List<String> postProjectDimListBuilder = new ArrayList<>();
            final AlgDataType postAggInputRowType = getCluster().getTypeFactory().createStructType( Pair.right( postProject.getInput().getRowType().getFieldList() ), aggregateStageFieldNames );
            final Set<String> existingAggFieldsNames = new HashSet<>( aggregateStageFieldNames );
            // this is an index of existing columns coming out aggregate layer. Will use this index to: filter out any project down the road that doesn't change values e.g inputRef/identity cast
            Map<String, String> existingProjects = Maps.uniqueIndex( aggregateStageFieldNames, DruidExpressions::fromColumn );
            for ( Pair<RexNode, String> pair : postProject.getNamedProjects() ) {
                final RexNode postProjectRexNode = pair.left;
                String expression = DruidExpressions.toDruidExpression( postProjectRexNode, postAggInputRowType, this );
                final String existingFieldName = existingProjects.get( expression );
                if ( existingFieldName != null ) {
                    // simple input ref or Druid runtime identity cast will skip it, since it is here already
                    postProjectDimListBuilder.add( existingFieldName );
                } else {
                    final String uniquelyProjectFieldName = ValidatorUtil.uniquify( pair.right, existingAggFieldsNames, ValidatorUtil.EXPR_SUGGESTER );
                    postAggs.add( new JsonExpressionPostAgg( uniquelyProjectFieldName, expression, null ) );
                    postProjectDimListBuilder.add( uniquelyProjectFieldName );
                    existingAggFieldsNames.add( uniquelyProjectFieldName );
                }
            }
            postAggregateStageFieldNames = postProjectDimListBuilder;
        } else {
            postAggregateStageFieldNames = null;
        }

        // final Query output row field names.
        final List<String> queryOutputFieldNames = postAggregateStageFieldNames == null
                ? aggregateStageFieldNames
                : postAggregateStageFieldNames;

        // handle sort all together
        limit = computeSort( fetch, collationIndexes, collationDirections, numericCollationIndexes, queryOutputFieldNames );

        final String timeSeriesQueryString = planAsTimeSeries( groupByKeyDims, jsonFilter,
                virtualColumnList, aggregations, postAggs, limit, havingJsonFilter );
        if ( timeSeriesQueryString != null ) {
            final String timeExtractColumn = groupByKeyDims.isEmpty()
                    ? null
                    : groupByKeyDims.get( 0 ).getOutputName();
            if ( timeExtractColumn != null ) {
                // Case we have transformed the group by time to druid timeseries with Granularity. Need to replace the name of the column with druid timestamp field name.
                final List<String> timeseriesFieldNames =
                        Lists.transform( queryOutputFieldNames, input -> {
                            if ( timeExtractColumn.equals( input ) ) {
                                return "timestamp";
                            }
                            return input;
                        } );
                return new QuerySpec( QueryType.TIMESERIES, timeSeriesQueryString, timeseriesFieldNames );
            }
            return new QuerySpec( QueryType.TIMESERIES, timeSeriesQueryString, queryOutputFieldNames );
        }
        final String topNQuery = planAsTopN( groupByKeyDims, jsonFilter, virtualColumnList, aggregations, postAggs, limit, havingJsonFilter );
        if ( topNQuery != null ) {
            return new QuerySpec( QueryType.TOP_N, topNQuery, queryOutputFieldNames );
        }

        final String groupByQuery = planAsGroupBy( groupByKeyDims, jsonFilter, virtualColumnList, aggregations, postAggs, limit, havingJsonFilter );

        if ( groupByQuery == null ) {
            throw new IllegalStateException( "Can not plan Druid Query" );
        }
        return new QuerySpec( QueryType.GROUP_BY, groupByQuery, queryOutputFieldNames );
    }


    /**
     * @param fetch limit to fetch
     * @param collationIndexes index of fields as listed in query row output
     * @param collationDirections direction of sort
     * @param numericCollationIndexes flag of to determine sort comparator
     * @param queryOutputFieldNames query output fields
     * @return always an non null Json Limit object
     */
    private JsonLimit computeSort( @Nullable Integer fetch, List<Integer> collationIndexes, List<Direction> collationDirections, ImmutableBitSet numericCollationIndexes, List<String> queryOutputFieldNames ) {
        final List<JsonCollation> collations;
        if ( collationIndexes != null ) {
            assert collationDirections != null;
            ImmutableList.Builder<JsonCollation> colBuilder = ImmutableList.builder();
            for ( Pair<Integer, Direction> p : Pair.zip( collationIndexes, collationDirections ) ) {
                final String dimensionOrder = numericCollationIndexes.get( p.left )
                        ? "numeric"
                        : "lexicographic";
                colBuilder.add( new JsonCollation( queryOutputFieldNames.get( p.left ), p.right == Direction.DESCENDING ? "descending" : "ascending", dimensionOrder ) );
            }
            collations = colBuilder.build();
        } else {
            collations = null;
        }
        return new JsonLimit( "default", fetch, collations );
    }


    @Nullable
    private String planAsTimeSeries( List<DimensionSpec> groupByKeyDims, DruidJsonFilter jsonFilter, List<VirtualColumn> virtualColumnList, List<JsonAggregation> aggregations, List<JsonExpressionPostAgg> postAggregations, JsonLimit limit, DruidJsonFilter havingFilter ) {
        if ( havingFilter != null ) {
            return null;
        }
        if ( groupByKeyDims.size() > 1 ) {
            return null;
        }
        if ( limit.limit != null ) {
            // it has a limit not supported by time series
            return null;
        }
        if ( limit.collations != null && limit.collations.size() > 1 ) {
            // it has multiple sort columns
            return null;
        }
        final String sortDirection;
        if ( limit.collations != null && limit.collations.size() == 1 ) {
            if ( groupByKeyDims.isEmpty() || !limit.collations.get( 0 ).dimension.equals( groupByKeyDims.get( 0 ).getOutputName() ) ) {
                // sort column is not time column
                return null;
            }
            sortDirection = limit.collations.get( 0 ).direction;
        } else {
            sortDirection = null;
        }

        final Granularity timeseriesGranularity;
        if ( groupByKeyDims.size() == 1 ) {
            DimensionSpec dimensionSpec = Iterables.getOnlyElement( groupByKeyDims );
            Granularity granularity = ExtractionDimensionSpec.toQueryGranularity( dimensionSpec );
            // case we have project expression on the top of the time extract then can not use timeseries
            boolean hasExpressionOnTopOfTimeExtract = false;
            for ( JsonExpressionPostAgg postAgg : postAggregations ) {
                if ( postAgg instanceof JsonExpressionPostAgg ) {
                    if ( postAgg.expression.contains( groupByKeyDims.get( 0 ).getOutputName() ) ) {
                        hasExpressionOnTopOfTimeExtract = true;
                    }
                }
            }
            timeseriesGranularity = hasExpressionOnTopOfTimeExtract ? null : granularity;
            if ( timeseriesGranularity == null ) {
                // can not extract granularity bailout
                return null;
            }
        } else {
            timeseriesGranularity = Granularities.all();
        }

        final boolean skipEmptyBuckets = Granularities.all() != timeseriesGranularity;

        final StringWriter sw = new StringWriter();
        final JsonFactory factory = new JsonFactory();
        try {
            final JsonGenerator generator = factory.createGenerator( sw );
            generator.writeStartObject();
            generator.writeStringField( "queryType", "timeseries" );
            generator.writeStringField( "dataSource", druidTable.dataSource );
            generator.writeBooleanField( "descending", sortDirection != null && sortDirection.equals( "descending" ) );
            writeField( generator, "granularity", timeseriesGranularity );
            writeFieldIf( generator, "filter", jsonFilter );
            writeField( generator, "aggregations", aggregations );
            writeFieldIf( generator, "virtualColumns", virtualColumnList.size() > 0 ? virtualColumnList : null );
            writeFieldIf( generator, "postAggregations", postAggregations.size() > 0 ? postAggregations : null );
            writeField( generator, "intervals", intervals );
            generator.writeFieldName( "context" );
            // The following field is necessary to conform with SQL semantics (POLYPHENYDB-1589)
            generator.writeStartObject();
            // Count(*) returns 0 if result set is empty thus need to set skipEmptyBuckets to false
            generator.writeBooleanField( "skipEmptyBuckets", skipEmptyBuckets );
            generator.writeEndObject();
            generator.close();
        } catch ( IOException e ) {
            throw new RuntimeException( e );
        }
        return sw.toString();
    }


    @Nullable
    private String planAsTopN( List<DimensionSpec> groupByKeyDims, DruidJsonFilter jsonFilter, List<VirtualColumn> virtualColumnList, List<JsonAggregation> aggregations, List<JsonExpressionPostAgg> postAggregations, JsonLimit limit, DruidJsonFilter havingFilter ) {
        if ( havingFilter != null ) {
            return null;
        }
        if ( !RuntimeConfig.APPROXIMATE_TOP_N.getBoolean() || groupByKeyDims.size() != 1 || limit.limit == null || limit.collations == null || limit.collations.size() != 1 ) {
            return null;
        }
        if ( limit.collations.get( 0 ).dimension.equals( groupByKeyDims.get( 0 ).getOutputName() ) ) {
            return null;
        }
        if ( limit.collations.get( 0 ).direction.equals( "ascending" ) ) {
            // Only DESC is allowed
            return null;
        }

        final String topNMetricColumnName = limit.collations.get( 0 ).dimension;
        final StringWriter sw = new StringWriter();
        final JsonFactory factory = new JsonFactory();
        try {
            final JsonGenerator generator = factory.createGenerator( sw );
            generator.writeStartObject();

            generator.writeStringField( "queryType", "topN" );
            generator.writeStringField( "dataSource", druidTable.dataSource );
            writeField( generator, "granularity", Granularities.all() );
            writeField( generator, "dimension", groupByKeyDims.get( 0 ) );
            writeFieldIf( generator, "virtualColumns", virtualColumnList.size() > 0 ? virtualColumnList : null );
            generator.writeStringField( "metric", topNMetricColumnName );
            writeFieldIf( generator, "filter", jsonFilter );
            writeField( generator, "aggregations", aggregations );
            writeFieldIf( generator, "postAggregations", postAggregations.size() > 0 ? postAggregations : null );
            writeField( generator, "intervals", intervals );
            generator.writeNumberField( "threshold", limit.limit );
            generator.writeEndObject();
            generator.close();
        } catch ( IOException e ) {
            throw new RuntimeException( e );
        }
        return sw.toString();
    }


    @Nullable
    private String planAsGroupBy( List<DimensionSpec> groupByKeyDims, DruidJsonFilter jsonFilter, List<VirtualColumn> virtualColumnList, List<JsonAggregation> aggregations, List<JsonExpressionPostAgg> postAggregations, JsonLimit limit, DruidJsonFilter havingFilter ) {
        final StringWriter sw = new StringWriter();
        final JsonFactory factory = new JsonFactory();
        try {
            final JsonGenerator generator = factory.createGenerator( sw );

            generator.writeStartObject();
            generator.writeStringField( "queryType", "groupBy" );
            generator.writeStringField( "dataSource", druidTable.dataSource );
            writeField( generator, "granularity", Granularities.all() );
            writeField( generator, "dimensions", groupByKeyDims );
            writeFieldIf( generator, "virtualColumns", virtualColumnList.size() > 0 ? virtualColumnList : null );
            writeFieldIf( generator, "limitSpec", limit );
            writeFieldIf( generator, "filter", jsonFilter );
            writeField( generator, "aggregations", aggregations );
            writeFieldIf( generator, "postAggregations", postAggregations.size() > 0 ? postAggregations : null );
            writeField( generator, "intervals", intervals );
            writeFieldIf( generator, "having", havingFilter == null ? null : new DruidJsonFilter.JsonDimHavingFilter( havingFilter ) );
            generator.writeEndObject();
            generator.close();
        } catch ( IOException e ) {
            throw new RuntimeException( e );
        }
        return sw.toString();
    }


    /**
     * Druid Scan Query Body
     */
    private static class ScanQuery {

        private String dataSource;

        private List<Interval> intervals;

        private DruidJsonFilter jsonFilter;

        private List<VirtualColumn> virtualColumnList;

        private List<String> columns;

        private Integer fetchLimit;


        ScanQuery( String dataSource, List<Interval> intervals, DruidJsonFilter jsonFilter, List<VirtualColumn> virtualColumnList, List<String> columns, Integer fetchLimit ) {
            this.dataSource = dataSource;
            this.intervals = intervals;
            this.jsonFilter = jsonFilter;
            this.virtualColumnList = virtualColumnList;
            this.columns = columns;
            this.fetchLimit = fetchLimit;
        }


        @Nonnull
        public String toQuery() {
            final StringWriter sw = new StringWriter();
            try {
                final JsonFactory factory = new JsonFactory();
                final JsonGenerator generator = factory.createGenerator( sw );
                generator.writeStartObject();
                generator.writeStringField( "queryType", "scan" );
                generator.writeStringField( "dataSource", dataSource );
                writeField( generator, "intervals", intervals );
                writeFieldIf( generator, "filter", jsonFilter );
                writeFieldIf( generator, "virtualColumns", virtualColumnList.size() > 0 ? virtualColumnList : null );
                writeField( generator, "columns", columns );
                generator.writeStringField( "resultFormat", "compactedList" );
                if ( fetchLimit != null ) {
                    generator.writeNumberField( "limit", fetchLimit );
                }
                generator.writeEndObject();
                generator.close();
            } catch ( IOException e ) {
                throw new RuntimeException( e );
            }
            return sw.toString();
        }

    }


    @Nullable
    private static JsonAggregation getJsonAggregation( String name, AggregateCall aggCall, RexNode filterNode, String fieldName, String aggExpression, DruidQuery druidQuery ) {
        final boolean fractional;
        final AlgDataType type = aggCall.getType();
        final PolyType polyType = type.getPolyType();
        final JsonAggregation aggregation;

        if ( PolyTypeFamily.APPROXIMATE_NUMERIC.getTypeNames().contains( polyType ) ) {
            fractional = true;
        } else if ( PolyTypeFamily.INTEGER.getTypeNames().contains( polyType ) ) {
            fractional = false;
        } else if ( PolyTypeFamily.EXACT_NUMERIC.getTypeNames().contains( polyType ) ) {
            // Decimal
            assert polyType == PolyType.DECIMAL;
            if ( type.getScale() == 0 ) {
                fractional = false;
            } else {
                fractional = true;
            }
        } else {
            // Cannot handle this aggregate function type
            return null;
        }

        // Convert from a complex metric
        ComplexMetric complexMetric = druidQuery.druidTable.resolveComplexMetric( fieldName, aggCall );

        switch ( aggCall.getAggregation().getKind() ) {
            case COUNT:
                if ( aggCall.isDistinct() ) {
                    final boolean approximateDistinctCount = RuntimeConfig.APPROXIMATE_DISTINCT_COUNT.getBoolean();
                    if ( aggCall.isApproximate() || approximateDistinctCount ) {
                        if ( complexMetric == null ) {
                            aggregation = new JsonCardinalityAggregation( "cardinality", name, ImmutableList.of( fieldName ) );
                        } else {
                            aggregation = new JsonAggregation( complexMetric.getMetricType(), name, complexMetric.getMetricName(), null );
                        }
                        break;
                    } else {
                        // when approximate results were not told be acceptable.
                        return null;
                    }
                }
                if ( aggCall.getArgList().size() == 1 && !aggCall.isDistinct() ) {
                    // case we have count(column) push it as count(*) where column is not null
                    final DruidJsonFilter matchNulls;
                    if ( fieldName == null ) {
                        matchNulls = new DruidJsonFilter.JsonExpressionFilter( aggExpression + " == null" );
                    } else {
                        matchNulls = DruidJsonFilter.getSelectorFilter( fieldName, null, null );
                    }
                    aggregation = new JsonFilteredAggregation( DruidJsonFilter.toNotDruidFilter( matchNulls ), new JsonAggregation( "count", name, fieldName, aggExpression ) );
                } else if ( !aggCall.isDistinct() ) {
                    aggregation = new JsonAggregation( "count", name, fieldName, aggExpression );
                } else {
                    aggregation = null;
                }

                break;
            case SUM:
            case SUM0:
                aggregation = new JsonAggregation( fractional ? "doubleSum" : "longSum", name, fieldName, aggExpression );
                break;
            case MIN:
                aggregation = new JsonAggregation( fractional ? "doubleMin" : "longMin", name, fieldName, aggExpression );
                break;
            case MAX:
                aggregation = new JsonAggregation( fractional ? "doubleMax" : "longMax", name, fieldName, aggExpression );
                break;
            default:
                return null;
        }

        if ( aggregation == null ) {
            return null;
        }
        // translate filters
        if ( filterNode != null ) {
            DruidJsonFilter druidFilter = DruidJsonFilter.toDruidFilters( filterNode, druidQuery.table.getRowType(), druidQuery );
            if ( druidFilter == null ) {
                // can not translate filter
                return null;
            }
            return new JsonFilteredAggregation( druidFilter, aggregation );
        }

        return aggregation;
    }


    protected static void writeField( JsonGenerator generator, String fieldName, Object o ) throws IOException {
        generator.writeFieldName( fieldName );
        writeObject( generator, o );
    }


    protected static void writeFieldIf( JsonGenerator generator, String fieldName, Object o ) throws IOException {
        if ( o != null ) {
            writeField( generator, fieldName, o );
        }
    }


    protected static void writeArray( JsonGenerator generator, List<?> elements ) throws IOException {
        generator.writeStartArray();
        for ( Object o : elements ) {
            writeObject( generator, o );
        }
        generator.writeEndArray();
    }


    protected static void writeObject( JsonGenerator generator, Object o ) throws IOException {
        if ( o instanceof String ) {
            String s = (String) o;
            generator.writeString( s );
        } else if ( o instanceof Interval ) {
            generator.writeString( o.toString() );
        } else if ( o instanceof Integer ) {
            Integer i = (Integer) o;
            generator.writeNumber( i );
        } else if ( o instanceof List ) {
            writeArray( generator, (List<?>) o );
        } else if ( o instanceof DruidJson ) {
            ((DruidJson) o).write( generator );
        } else {
            throw new AssertionError( "not a json object: " + o );
        }
    }


    /**
     * Generates a JSON string to query metadata about a data source.
     */
    static String metadataQuery( String dataSourceName, List<Interval> intervals ) {
        final StringWriter sw = new StringWriter();
        final JsonFactory factory = new JsonFactory();
        try {
            final JsonGenerator generator = factory.createGenerator( sw );
            generator.writeStartObject();
            generator.writeStringField( "queryType", "segmentMetadata" );
            generator.writeStringField( "dataSource", dataSourceName );
            generator.writeBooleanField( "merge", true );
            generator.writeBooleanField( "lenientAggregatorMerge", true );
            generator.writeArrayFieldStart( "analysisTypes" );
            generator.writeString( "aggregators" );
            generator.writeEndArray();
            writeFieldIf( generator, "intervals", intervals );
            generator.writeEndObject();
            generator.close();
        } catch ( IOException e ) {
            throw new RuntimeException( e );
        }
        return sw.toString();
    }


    /**
     * Druid query specification.
     */
    public static class QuerySpec {

        final QueryType queryType;
        final String queryString;
        final List<String> fieldNames;


        QuerySpec( QueryType queryType, String queryString, List<String> fieldNames ) {
            this.queryType = Objects.requireNonNull( queryType );
            this.queryString = Objects.requireNonNull( queryString );
            this.fieldNames = ImmutableList.copyOf( fieldNames );
        }


        @Override
        public int hashCode() {
            return Objects.hash( queryType, queryString, fieldNames );
        }


        @Override
        public boolean equals( Object obj ) {
            return obj == this
                    || obj instanceof QuerySpec
                    && queryType == ((QuerySpec) obj).queryType
                    && queryString.equals( ((QuerySpec) obj).queryString )
                    && fieldNames.equals( ((QuerySpec) obj).fieldNames );
        }


        @Override
        public String toString() {
            return "{queryType: " + queryType + ", queryString: " + queryString + ", fieldNames: " + fieldNames + "}";
        }


        public String getQueryString( String pagingIdentifier, int offset ) {
            if ( pagingIdentifier == null ) {
                return queryString;
            }
            return queryString.replace(
                    "\"threshold\":",
                    "\"pagingIdentifiers\":{\"" + pagingIdentifier + "\":" + offset + "},\"threshold\":" );
        }

    }


    /**
     * Interpreter node that executes a Druid query and sends the results to a {@link Sink}.
     */
    private static class DruidQueryNode implements Node {

        private final Sink sink;
        private final DruidQuery query;
        private final QuerySpec querySpec;


        DruidQueryNode( Compiler interpreter, DruidQuery query ) {
            this.query = query;
            this.sink = interpreter.sink( query );
            this.querySpec = query.getQuerySpec();
            Hook.QUERY_PLAN.run( querySpec );
        }


        @Override
        public void run() throws InterruptedException {
            final List<ColumnMetaData.Rep> fieldTypes = new ArrayList<>();
            for ( AlgDataTypeField field : query.getRowType().getFieldList() ) {
                fieldTypes.add( getPrimitive( field ) );
            }
            final DruidConnectionImpl connection = new DruidConnectionImpl( query.druidTable.schema.url, query.druidTable.schema.coordinatorUrl );
            final boolean limitQuery = containsLimit( querySpec );
            final DruidConnectionImpl.Page page = new DruidConnectionImpl.Page();
            do {
                final String queryString = querySpec.getQueryString( page.pagingIdentifier, page.offset );
                connection.request( querySpec.queryType, queryString, sink, querySpec.fieldNames, fieldTypes, page );
            } while ( !limitQuery && page.pagingIdentifier != null && page.totalRowCount > 0 );
        }


        private static boolean containsLimit( QuerySpec querySpec ) {
            return querySpec.queryString.contains( "\"context\":{\"" + DRUID_QUERY_FETCH + "\":true" );
        }


        private ColumnMetaData.Rep getPrimitive( AlgDataTypeField field ) {
            switch ( field.getType().getPolyType() ) {
                case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                case TIMESTAMP:
                    return ColumnMetaData.Rep.JAVA_SQL_TIMESTAMP;
                case BIGINT:
                    return ColumnMetaData.Rep.LONG;
                case INTEGER:
                    return ColumnMetaData.Rep.INTEGER;
                case SMALLINT:
                    return ColumnMetaData.Rep.SHORT;
                case TINYINT:
                    return ColumnMetaData.Rep.BYTE;
                case REAL:
                    return ColumnMetaData.Rep.FLOAT;
                case DOUBLE:
                case FLOAT:
                    return ColumnMetaData.Rep.DOUBLE;
                default:
                    return null;
            }
        }

    }


    /**
     * Aggregation element of a Druid "groupBy" or "topN" query.
     */
    private static class JsonAggregation implements DruidJson {

        final String type;
        final String name;
        final String fieldName;
        final String expression;


        private JsonAggregation( String type, String name, String fieldName, String expression ) {
            this.type = type;
            this.name = name;
            this.fieldName = fieldName;
            this.expression = expression;
        }


        @Override
        public void write( JsonGenerator generator ) throws IOException {
            generator.writeStartObject();
            generator.writeStringField( "type", type );
            generator.writeStringField( "name", name );
            writeFieldIf( generator, "fieldName", fieldName );
            writeFieldIf( generator, "expression", expression );
            generator.writeEndObject();
        }

    }


    /**
     * Druid Json Expression post aggregate.
     */
    private static class JsonExpressionPostAgg extends JsonPostAggregation {

        private final String expression;
        private final String ordering;


        private JsonExpressionPostAgg( String name, String expression, String ordering ) {
            super( name, "expression" );
            this.expression = expression;
            this.ordering = ordering;
        }


        @Override
        public void write( JsonGenerator generator ) throws IOException {
            super.write( generator );
            writeFieldIf( generator, "expression", expression );
            writeFieldIf( generator, "ordering", ordering );
            generator.writeEndObject();
        }

    }


    /**
     * Collation element of a Druid "groupBy" query.
     */
    private static class JsonLimit implements DruidJson {

        final String type;
        final Integer limit;
        final List<JsonCollation> collations;


        private JsonLimit( String type, Integer limit, List<JsonCollation> collations ) {
            this.type = type;
            this.limit = limit;
            this.collations = collations;
        }


        @Override
        public void write( JsonGenerator generator ) throws IOException {
            generator.writeStartObject();
            generator.writeStringField( "type", type );
            writeFieldIf( generator, "limit", limit );
            writeFieldIf( generator, "columns", collations );
            generator.writeEndObject();
        }

    }


    /**
     * Collation element of a Druid "groupBy" query.
     */
    private static class JsonCollation implements DruidJson {

        final String dimension;
        final String direction;
        final String dimensionOrder;


        private JsonCollation( String dimension, String direction, String dimensionOrder ) {
            this.dimension = dimension;
            this.direction = direction;
            this.dimensionOrder = dimensionOrder;
        }


        @Override
        public void write( JsonGenerator generator ) throws IOException {
            generator.writeStartObject();
            generator.writeStringField( "dimension", dimension );
            writeFieldIf( generator, "direction", direction );
            writeFieldIf( generator, "dimensionOrder", dimensionOrder );
            generator.writeEndObject();
        }

    }


    /**
     * Aggregation element that calls the "cardinality" function.
     */
    private static class JsonCardinalityAggregation extends JsonAggregation {

        final List<String> fieldNames;


        private JsonCardinalityAggregation(
                String type, String name,
                List<String> fieldNames ) {
            super( type, name, null, null );
            this.fieldNames = fieldNames;
        }


        @Override
        public void write( JsonGenerator generator ) throws IOException {
            generator.writeStartObject();
            generator.writeStringField( "type", type );
            generator.writeStringField( "name", name );
            writeFieldIf( generator, "fieldNames", fieldNames );
            generator.writeEndObject();
        }

    }


    /**
     * Aggregation element that contains a filter
     */
    private static class JsonFilteredAggregation extends JsonAggregation {

        final DruidJsonFilter filter;
        final JsonAggregation aggregation;


        private JsonFilteredAggregation( DruidJsonFilter filter, JsonAggregation aggregation ) {
            // Filtered aggregations don't use the "name" and "fieldName" fields directly, but rather use the ones defined in their "aggregation" field.
            super( "filtered", aggregation.name, aggregation.fieldName, null );
            this.filter = filter;
            this.aggregation = aggregation;
        }


        @Override
        public void write( JsonGenerator generator ) throws IOException {
            generator.writeStartObject();
            generator.writeStringField( "type", type );
            writeField( generator, "filter", filter );
            writeField( generator, "aggregator", aggregation );
            generator.writeEndObject();
        }

    }


    /**
     * Post-Aggregator Post aggregator abstract writer
     */
    protected abstract static class JsonPostAggregation implements DruidJson {

        final String type;
        String name;


        private JsonPostAggregation( String name, String type ) {
            this.type = type;
            this.name = name;
        }


        // Expects all subclasses to write the EndObject item
        @Override
        public void write( JsonGenerator generator ) throws IOException {
            generator.writeStartObject();
            generator.writeStringField( "type", type );
            generator.writeStringField( "name", name );
        }


        public void setName( String name ) {
            this.name = name;
        }

    }


    /**
     * @return index of the timestamp ref or -1 if not present
     */
    protected int getTimestampFieldIndex() {
        return Iterables.indexOf( this.getRowType().getFieldList(), input -> druidTable.timestampFieldName.equals( input.getName() ) );
    }

}


/*
 * Copyright 2019-2022 The Polypheny Project
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
 */

package org.polypheny.db.monitoring.statistics;


import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.math.NumberUtils;
import org.polypheny.db.StatisticsManager;
import org.polypheny.db.algebra.AlgCollations;
import org.polypheny.db.algebra.AlgNode;
import org.polypheny.db.algebra.core.AggregateCall;
import org.polypheny.db.algebra.fun.AggFunction;
import org.polypheny.db.algebra.logical.LogicalAggregate;
import org.polypheny.db.algebra.logical.LogicalProject;
import org.polypheny.db.algebra.logical.LogicalSort;
import org.polypheny.db.algebra.logical.LogicalTableScan;
import org.polypheny.db.algebra.operators.OperatorName;
import org.polypheny.db.algebra.type.AlgDataType;
import org.polypheny.db.catalog.Catalog;
import org.polypheny.db.catalog.entity.CatalogTable;
import org.polypheny.db.catalog.exceptions.GenericCatalogException;
import org.polypheny.db.catalog.exceptions.UnknownDatabaseException;
import org.polypheny.db.catalog.exceptions.UnknownSchemaException;
import org.polypheny.db.catalog.exceptions.UnknownUserException;
import org.polypheny.db.config.Config;
import org.polypheny.db.config.Config.ConfigListener;
import org.polypheny.db.config.RuntimeConfig;
import org.polypheny.db.information.InformationAction;
import org.polypheny.db.information.InformationAction.Action;
import org.polypheny.db.information.InformationGroup;
import org.polypheny.db.information.InformationManager;
import org.polypheny.db.information.InformationPage;
import org.polypheny.db.information.InformationTable;
import org.polypheny.db.languages.OperatorRegistry;
import org.polypheny.db.plan.AlgOptCluster;
import org.polypheny.db.plan.AlgOptTable;
import org.polypheny.db.prepare.PolyphenyDbCatalogReader;
import org.polypheny.db.prepare.Prepare.CatalogReader;
import org.polypheny.db.rex.RexBuilder;
import org.polypheny.db.rex.RexLiteral;
import org.polypheny.db.tools.AlgBuilder;
import org.polypheny.db.transaction.Statement;
import org.polypheny.db.transaction.Transaction;
import org.polypheny.db.type.PolyType;
import org.polypheny.db.type.PolyTypeFamily;
import org.polypheny.db.util.DateTimeStringUtils;
import org.polypheny.db.util.ImmutableBitSet;
import org.polypheny.db.util.Pair;
import org.polypheny.db.util.background.BackgroundTask.TaskPriority;
import org.polypheny.db.util.background.BackgroundTask.TaskSchedulingType;
import org.polypheny.db.util.background.BackgroundTaskManager;


/**
 * Stores all available statistics and updates INSERTs dynamically
 * DELETEs and UPDATEs should wait to be reprocessed
 */
@Slf4j
public class StatisticsManagerImpl<T extends Comparable<T>> extends StatisticsManager<T> {

    private static StatisticQueryProcessor sqlQueryInterface;

    private final ExecutorService threadPool = Executors.newSingleThreadExecutor();

    protected final PropertyChangeSupport listeners = new PropertyChangeSupport( this );

    private int buffer = RuntimeConfig.STATISTIC_BUFFER.getInteger();

    @Setter
    @Getter
    private String revalId = null;

    @Getter
    private volatile ConcurrentHashMap<Long, HashMap<Long, HashMap<Long, StatisticColumn<T>>>> statisticSchemaMap;

    @Getter
    private final ConcurrentHashMap<Long, StatisticTable<T>> tableStatistic;

    //new Additions for additional Information
    private final Queue<Long> tablesToUpdate = new ConcurrentLinkedQueue<>();

    @Setter
    @Getter
    private int numberOfCommits;

    @Setter
    @Getter
    private int numberOfRollbacks;


    public StatisticsManagerImpl( StatisticQueryProcessor statisticQueryProcessor ) {
        this.setQueryInterface( statisticQueryProcessor );
        this.statisticSchemaMap = new ConcurrentHashMap<>();
        this.tableStatistic = new ConcurrentHashMap<>();
        displayInformation();
        registerTaskTracking();
        registerIsFullTracking();

        this.listeners.addPropertyChangeListener( this );
    }


    // Use relNode to update
    @Override
    public void tablesToUpdate( Long tableId ) {
        if ( !tablesToUpdate.contains( tableId ) ) {
            tablesToUpdate.add( tableId );
            listeners.firePropertyChange( "tablesToUpdate", null, tableId );
        }
    }


    // Use cache if possible
    @Override
    public void tablesToUpdate( Long tableId, HashMap<Long, List<Object>> changedValues, String type, Long schemaId ) {
        if ( type.equals( "INSERT" ) ) {
            Catalog catalog = Catalog.getInstance();
            CatalogTable catalogTable = catalog.getTable( tableId );
            List<Long> columns = catalogTable.columnIds;
            if ( this.statisticSchemaMap.get( schemaId ) != null ) {
                if ( this.statisticSchemaMap.get( schemaId ).get( tableId ) != null ) {
                    for ( int i = 0; i < columns.size(); i++ ) {
                        PolyType polyType = catalog.getColumn( columns.get( i ) ).type;
                        QueryColumn queryColumn = new QueryColumn( schemaId, catalogTable.id, columns.get( i ), polyType );
                        if ( this.statisticSchemaMap.get( schemaId ).get( tableId ).get( columns.get( i ) ) != null ) {
                            StatisticColumn<T> statisticColumn = this.statisticSchemaMap.get( schemaId ).get( tableId ).get( columns.get( i ) );

                            if ( polyType.getFamily() == PolyTypeFamily.NUMERIC ) {
                                ((NumericalStatisticColumn) statisticColumn).insert( changedValues.get( (long) i ) );
                                put( queryColumn, statisticColumn );
                            } else if ( polyType.getFamily() == PolyTypeFamily.CHARACTER ) {
                                ((AlphabeticStatisticColumn) statisticColumn).insert( changedValues.get( (long) i ) );
                                put( queryColumn, statisticColumn );
                            } else if ( PolyType.DATETIME_TYPES.contains( polyType ) ) {
                                ((TemporalStatisticColumn) statisticColumn).insert( changedValues.get( (long) i ) );
                                put( queryColumn, statisticColumn );
                            }
                        }
                    }
                } else {
                    addDataStatistics( changedValues, catalog, catalogTable, columns );
                }
            } else {
                addDataStatistics( changedValues, catalog, catalogTable, columns );
            }
        } else if ( type.equals( "TRUNCATE" ) ) {
            Catalog catalog = Catalog.getInstance();
            CatalogTable catalogTable = catalog.getTable( tableId );
            for ( int i = 0; i < catalogTable.columnIds.size(); i++ ) {
                PolyType polyType = catalog.getColumn( catalogTable.columnIds.get( i ) ).type;
                QueryColumn queryColumn = new QueryColumn( schemaId, catalogTable.id, catalogTable.columnIds.get( i ), polyType );
                if ( this.statisticSchemaMap.get( schemaId ).get( tableId ).get( catalogTable.columnIds.get( i ) ) != null ) {
                    if ( polyType.getFamily() == PolyTypeFamily.NUMERIC ) {
                        NumericalStatisticColumn numericalStatisticColumn = new NumericalStatisticColumn<>( queryColumn );
                        put( queryColumn, numericalStatisticColumn );
                    } else if ( polyType.getFamily() == PolyTypeFamily.CHARACTER ) {
                        AlphabeticStatisticColumn alphabeticStatisticColumn = new AlphabeticStatisticColumn<T>( queryColumn );
                        put( queryColumn, alphabeticStatisticColumn );
                    } else if ( PolyType.DATETIME_TYPES.contains( polyType ) ) {
                        TemporalStatisticColumn temporalStatisticColumn = new TemporalStatisticColumn<T>( queryColumn );
                        put( queryColumn, temporalStatisticColumn );
                    }
                }
            }
        }
    }


    private void addDataStatistics( HashMap<Long, List<Object>> changedValues, Catalog catalog, CatalogTable catalogTable, List<Long> columns ) {
        for ( int i = 0; i < columns.size(); i++ ) {
            PolyType polyType = catalog.getColumn( columns.get( i ) ).type;
            QueryColumn queryColumn = new QueryColumn( catalogTable.schemaId, catalogTable.id, columns.get( i ), polyType );

            if ( polyType.getFamily() == PolyTypeFamily.NUMERIC ) {
                NumericalStatisticColumn numericalStatisticColumn = new NumericalStatisticColumn<>( queryColumn );
                numericalStatisticColumn.insert( changedValues.get( (long) i ) );
                put( queryColumn, numericalStatisticColumn );
            } else if ( polyType.getFamily() == PolyTypeFamily.CHARACTER ) {
                AlphabeticStatisticColumn alphabeticStatisticColumn = new AlphabeticStatisticColumn<T>( queryColumn );
                alphabeticStatisticColumn.insert( changedValues.get( (long) i ) );
                put( queryColumn, alphabeticStatisticColumn );
            } else if ( PolyType.DATETIME_TYPES.contains( polyType ) ) {
                TemporalStatisticColumn temporalStatisticColumn = new TemporalStatisticColumn<T>( queryColumn );
                temporalStatisticColumn.insert( changedValues.get( (long) i ) );
                put( queryColumn, temporalStatisticColumn );
            }
        }
    }


    /**
     * Registers if on configChange statistics are tracked and displayed or not
     */
    private void registerTaskTracking() {
        TrackingListener listener = new TrackingListener();
        RuntimeConfig.PASSIVE_TRACKING.addObserver( listener );
        RuntimeConfig.DYNAMIC_QUERYING.addObserver( listener );
    }


    /**
     * Registers the isFull reevaluation on config change
     */
    private void registerIsFullTracking() {
        ConfigListener listener = new ConfigListener() {
            @Override
            public void onConfigChange( Config c ) {
                buffer = c.getInt();
                resetAllIsFull();
            }


            @Override
            public void restart( Config c ) {
                buffer = c.getInt();
                resetAllIsFull();
            }
        };
        RuntimeConfig.STATISTIC_BUFFER.addObserver( listener );
    }


    /**
     * Reset all statistics and reevaluate them
     */
    private void reevaluateAllStatistics() {
        if ( sqlQueryInterface == null ) {
            return;
        }
        log.debug( "Resetting StatisticManager." );
        ConcurrentHashMap<Long, HashMap<Long, HashMap<Long, StatisticColumn<T>>>> statisticSchemaMapCopy = new ConcurrentHashMap<>();

        for ( QueryColumn column : sqlQueryInterface.getAllColumns() ) {
            StatisticColumn<T> col = reevaluateColumn( column );
            if ( col != null ) {
                put( statisticSchemaMapCopy, column, col );
            }
        }
        replaceStatistics( statisticSchemaMapCopy );
        log.debug( "Finished resetting StatisticManager." );
    }


    private void resetAllIsFull() {
        this.statisticSchemaMap.values().forEach( s -> s.values().forEach( t -> t.values().forEach( c -> {
            assignUnique( c, this.getUniqueValues( new QueryColumn( c.getSchemaId(), c.getTableId(), c.getColumnId(), c.getType() ) ) );
        } ) ) );
    }


    /**
     * Gets a columns of a table and reevaluates them
     *
     * @param tableId id of table
     */
    @Override
    public void reevaluateTable( Long tableId ) {
        if ( sqlQueryInterface == null ) {
            return;
        }
        if ( Catalog.getInstance().checkIfExistsTable( tableId ) ) {
            deleteTable( Catalog.getInstance().getTable( tableId ).schemaId, tableId );

            List<QueryColumn> res = sqlQueryInterface.getAllColumns( tableId );

            for ( QueryColumn column : res ) {
                StatisticColumn<T> col = reevaluateColumn( column );
                if ( col != null ) {
                    put( column, col );
                }
            }
        }
    }


    private void deleteTable( Long schemaId, Long tableId ) {
        if ( this.statisticSchemaMap.get( schemaId ) != null ) {
            this.statisticSchemaMap.get( schemaId ).remove( tableId );
        }
    }


    /**
     * replace the the tracked statistics with other statistics
     */
    private synchronized void replaceStatistics( ConcurrentHashMap<Long, HashMap<Long, HashMap<Long, StatisticColumn<T>>>> map ) {
        this.statisticSchemaMap = new ConcurrentHashMap<>( map );
    }


    /**
     * Method to sort a column into the different kinds of column types and hands it to the specific reevaluation
     */
    private StatisticColumn<T> reevaluateColumn( QueryColumn column ) {
        /*
        if ( !this.sqlQueryInterface.hasData( column.getSchema(), column.getTable(), column.getName() ) ) {
            return null;
        }
         */
        if ( column.getType().getFamily() == PolyTypeFamily.NUMERIC ) {
            return this.reevaluateNumericalColumn( column );
        } else if ( column.getType().getFamily() == PolyTypeFamily.CHARACTER ) {
            return this.reevaluateAlphabeticalColumn( column );
        } else if ( PolyType.DATETIME_TYPES.contains( column.getType() ) ) {
            return this.reevaluateTemporalColumn( column );
        }
        return null;
    }


    /**
     * Reevaluates a numerical column, with the configured statistics
     */
    private StatisticColumn<T> reevaluateNumericalColumn( QueryColumn column ) {
        StatisticQueryColumn min = this.getAggregateColumn( column, "MIN" );
        StatisticQueryColumn max = this.getAggregateColumn( column, "MAX" );
        Integer count = this.getCount( column );
        NumericalStatisticColumn<T> statisticColumn = new NumericalStatisticColumn<>( column );
        if ( min != null ) {
            //noinspection unchecked
            statisticColumn.setMin( (T) min.getData()[0] );
        }
        if ( max != null ) {
            //noinspection unchecked
            statisticColumn.setMax( (T) max.getData()[0] );
        }

        StatisticQueryColumn unique = this.getUniqueValues( column );
        assignUnique( statisticColumn, unique );

        statisticColumn.setCount( count );

        return statisticColumn;
    }


    private StatisticColumn<T> reevaluateTemporalColumn( QueryColumn column ) {
        StatisticQueryColumn min = this.getAggregateColumn( column, "MIN" );
        StatisticQueryColumn max = this.getAggregateColumn( column, "MAX" );
        Integer count = this.getCount( column );

        TemporalStatisticColumn<T> statisticColumn = new TemporalStatisticColumn<>( column );
        if ( min != null ) {
            if ( NumberUtils.isParsable( min.getData()[0] ) ) {
                //noinspection unchecked
                statisticColumn.setMin( (T) DateTimeStringUtils.longToAdjustedString( Long.parseLong( min.getData()[0] ), column.getType() ) );
            } else {
                //noinspection unchecked
                statisticColumn.setMin( (T) min.getData()[0] );
            }
        }

        if ( max != null ) {
            if ( NumberUtils.isParsable( max.getData()[0] ) ) {
                //noinspection unchecked
                statisticColumn.setMax( (T) DateTimeStringUtils.longToAdjustedString( Long.parseLong( max.getData()[0] ), column.getType() ) );
            } else {
                //noinspection unchecked
                statisticColumn.setMax( (T) max.getData()[0] );
            }
        }

        StatisticQueryColumn unique = this.getUniqueValues( column );
        for ( int idx = 0; idx < unique.getData().length; idx++ ) {
            if ( unique.getData()[idx] != null ) {
                unique.getData()[idx] = DateTimeStringUtils.longToAdjustedString( Long.parseLong( unique.getData()[idx] ), column.getType() );
            }
        }

        assignUnique( statisticColumn, unique );

        statisticColumn.setCount( count );

        return statisticColumn;
    }


    /**
     * Helper method tho assign unique values or set isFull if too much exist
     *
     * @param column the column in which the values should be inserted
     */
    private void assignUnique( StatisticColumn<T> column, StatisticQueryColumn unique ) {
        if ( unique == null || unique.getData() == null ) {
            return;
        }
        if ( unique.getData().length <= this.buffer ) {
            column.setUniqueValues( Arrays.asList( (T[]) unique.getData() ) );
        } else {
            column.setFull( true );
        }
    }


    /**
     * Reevaluates an alphabetical column, with the configured statistics
     */
    private StatisticColumn<T> reevaluateAlphabeticalColumn( QueryColumn column ) {
        StatisticQueryColumn unique = this.getUniqueValues( column );
        Integer count = this.getCount( column );

        AlphabeticStatisticColumn<T> statisticColumn = new AlphabeticStatisticColumn<>( column );
        assignUnique( statisticColumn, unique );
        statisticColumn.setCount( count );

        return statisticColumn;

    }


    private void put( QueryColumn queryColumn, StatisticColumn<T> statisticColumn ) {
        put( this.statisticSchemaMap, queryColumn.getSchemaId(), queryColumn.getTableId(), queryColumn.getColumnId(), statisticColumn );
    }


    private void put(
            ConcurrentHashMap<Long, HashMap<Long, HashMap<Long, StatisticColumn<T>>>> statisticSchemaMapCopy,
            QueryColumn queryColumn,
            StatisticColumn<T> statisticColumn ) {
        put( statisticSchemaMapCopy, queryColumn.getSchemaId(), queryColumn.getTableId(), queryColumn.getColumnId(), statisticColumn );
    }


    /**
     * Places a column at the correct position in the schemaMap
     *
     * @param map which schemaMap should be used
     * @param statisticColumn the Column with its statistics
     */
    private void put(
            ConcurrentHashMap<Long, HashMap<Long, HashMap<Long, StatisticColumn<T>>>> map,
            Long schemaId,
            Long tableId,
            Long columnId,
            StatisticColumn<T> statisticColumn ) {
        if ( !map.containsKey( schemaId ) ) {
            map.put( schemaId, new HashMap<>() );
        }
        if ( !map.get( schemaId ).containsKey( tableId ) ) {
            map.get( schemaId ).put( tableId, new HashMap<>() );
        }
        map.get( schemaId ).get( tableId ).put( columnId, statisticColumn );

        if ( !tableStatistic.containsKey( tableId ) ) {
            tableStatistic.put( tableId, new StatisticTable( tableId ) );
        }
    }


    /**
     * Queries the database with a aggregate query
     *
     * @param aggregate the aggregate function to us
     */
    private StatisticQueryColumn getAggregateColumn( QueryColumn queryColumn, String aggregate ) {
        Transaction transaction = getTransaction();
        Statement statement = transaction.createStatement();
        PolyphenyDbCatalogReader reader = statement.getTransaction().getCatalogReader();
        AlgBuilder relBuilder = AlgBuilder.create( statement );
        final RexBuilder rexBuilder = relBuilder.getRexBuilder();
        final AlgOptCluster cluster = AlgOptCluster.create( statement.getQueryProcessor().getPlanner(), rexBuilder );

        LogicalTableScan tableScan = getLogicalTableScan( queryColumn.getSchema(), queryColumn.getTable(), reader, cluster );

        for ( int i = 0; i < tableScan.getRowType().getFieldNames().size(); i++ ) {
            if ( tableScan.getRowType().getFieldNames().get( i ).equals( queryColumn.getColumn() ) ) {
                LogicalProject logicalProject = LogicalProject.create(
                        tableScan,
                        Collections.singletonList( rexBuilder.makeInputRef( tableScan, i ) ),
                        Collections.singletonList( tableScan.getRowType().getFieldNames().get( i ) ) );

                AggFunction operator = null;
                if ( aggregate.equals( "MAX" ) ) {
                    operator = OperatorRegistry.getAgg( OperatorName.MAX );
                } else if ( aggregate.equals( "MIN" ) ) {
                    operator = OperatorRegistry.getAgg( OperatorName.MIN );
                } else {
                    throw new RuntimeException( "Unknown aggregate is used in Statistic Manager." );
                }

                AlgDataType relDataType = logicalProject.getRowType().getFieldList().get( 0 ).getType();
                AlgDataType dataType;
                if ( relDataType.getPolyType() == PolyType.DECIMAL ) {
                    dataType = cluster.getTypeFactory().createTypeWithNullability(
                            cluster.getTypeFactory().createPolyType( relDataType.getPolyType(), relDataType.getPrecision(), relDataType.getScale() ),
                            true );
                } else {
                    dataType = cluster.getTypeFactory().createTypeWithNullability(
                            cluster.getTypeFactory().createPolyType( relDataType.getPolyType() ),
                            true );
                }

                AggregateCall aggregateCall = AggregateCall.create(
                        operator,
                        false,
                        false,
                        Collections.singletonList( 0 ),
                        -1,
                        AlgCollations.EMPTY,
                        dataType,
                        "min-max" );

                AlgNode relNode = LogicalAggregate.create(
                        logicalProject,
                        ImmutableBitSet.of(),
                        Collections.singletonList( ImmutableBitSet.of() ),
                        Collections.singletonList( aggregateCall ) );

                return sqlQueryInterface.selectOneStatWithRel( relNode, transaction, statement, queryColumn );

            }
        }
        return null;
    }


    private StatisticQueryColumn getUniqueValues( QueryColumn queryColumn ) {
        Transaction transaction = getTransaction();
        Statement statement = transaction.createStatement();
        PolyphenyDbCatalogReader reader = statement.getTransaction().getCatalogReader();
        AlgBuilder relBuilder = AlgBuilder.create( statement );
        final RexBuilder rexBuilder = relBuilder.getRexBuilder();
        final AlgOptCluster cluster = AlgOptCluster.create( statement.getQueryProcessor().getPlanner(), rexBuilder );

        LogicalTableScan tableScan = getLogicalTableScan( queryColumn.getSchema(), queryColumn.getTable(), reader, cluster );

        AlgNode relNode;
        for ( int i = 0; i < tableScan.getRowType().getFieldNames().size(); i++ ) {
            if ( tableScan.getRowType().getFieldNames().get( i ).equals( queryColumn.getColumn() ) ) {
                LogicalProject logicalProject = LogicalProject.create(
                        tableScan,
                        Collections.singletonList( rexBuilder.makeInputRef( tableScan, i ) ),
                        Collections.singletonList( tableScan.getRowType().getFieldNames().get( i ) ) );

                LogicalAggregate logicalAggregate = LogicalAggregate.create(
                        logicalProject, ImmutableBitSet.of( 0 ),
                        Collections.singletonList( ImmutableBitSet.of( 0 ) ),
                        Collections.emptyList() );

                Pair<BigDecimal, PolyType> valuePair = new Pair<>( new BigDecimal( (int) 6 ), PolyType.DECIMAL );

                relNode = LogicalSort.create(
                        logicalAggregate,
                        AlgCollations.of(),
                        null,
                        new RexLiteral( valuePair.left, rexBuilder.makeInputRef( tableScan, i ).getType(), valuePair.right ) );

                return sqlQueryInterface.selectOneStatWithRel( relNode, transaction, statement, queryColumn );
            }
        }
        return null;
    }


    private Transaction getTransaction() {
        Transaction transaction = null;
        try {
            transaction = sqlQueryInterface.getTransactionManager().startTransaction( "pa", "APP", false, "Statistic Manager" );
        } catch ( GenericCatalogException | UnknownUserException | UnknownDatabaseException | UnknownSchemaException e ) {
            throw new RuntimeException( e );
        }
        return transaction;
    }


    private LogicalTableScan getLogicalTableScan( String schema, String table, CatalogReader reader, AlgOptCluster cluster ) {
        AlgOptTable relOptTable = reader.getTable( Arrays.asList( schema, table ) );
        return LogicalTableScan.create( cluster, relOptTable );
    }


    /**
     * Gets the amount of entries for a column
     */
    private Integer getCount( QueryColumn queryColumn ) {
        Transaction transaction = getTransaction();
        Statement statement = transaction.createStatement();
        PolyphenyDbCatalogReader reader = statement.getTransaction().getCatalogReader();
        AlgBuilder relBuilder = AlgBuilder.create( statement );
        final RexBuilder rexBuilder = relBuilder.getRexBuilder();
        final AlgOptCluster cluster = AlgOptCluster.create( statement.getQueryProcessor().getPlanner(), rexBuilder );

        LogicalTableScan tableScan = getLogicalTableScan( queryColumn.getSchema(), queryColumn.getTable(), reader, cluster );

        for ( int i = 0; i < tableScan.getRowType().getFieldNames().size(); i++ ) {
            if ( tableScan.getRowType().getFieldNames().get( i ).equals( queryColumn.getColumn() ) ) {
                LogicalProject logicalProject = LogicalProject.create(
                        tableScan,
                        Collections.singletonList( rexBuilder.makeInputRef( tableScan, i ) ),
                        Collections.singletonList( tableScan.getRowType().getFieldNames().get( i ) ) );

                AggregateCall aggregateCall = AggregateCall.create(
                        OperatorRegistry.getAgg( OperatorName.COUNT ),
                        false,
                        false,
                        Collections.singletonList( 0 ),
                        -1,
                        AlgCollations.EMPTY,
                        cluster.getTypeFactory().createTypeWithNullability(
                                cluster.getTypeFactory().createPolyType( PolyType.BIGINT ),
                                false ),
                        "min-max" );

                AlgNode relNode = LogicalAggregate.create(
                        logicalProject,
                        ImmutableBitSet.of(),
                        Collections.singletonList( ImmutableBitSet.of() ),
                        Collections.singletonList( aggregateCall ) );

                StatisticQueryColumn res = sqlQueryInterface.selectOneStatWithRel( relNode, transaction, statement, queryColumn );

                if ( res != null && res.getData() != null && res.getData().length != 0 ) {
                    try {
                        return Integer.parseInt( res.getData()[0] );
                    } catch ( NumberFormatException e ) {
                        log.error( "Count could not be parsed for column {}.", queryColumn.getColumn(), e );
                    }
                }
            }
        }
        return 0;
    }


    public void setQueryInterface( StatisticQueryProcessor statisticQueryProcessor ) {
        sqlQueryInterface = statisticQueryProcessor;
        if ( RuntimeConfig.STATISTICS_ON_STARTUP.getBoolean() ) {
            this.asyncReevaluateAllStatistics();
        }
    }


    /**
     * Configures and registers the statistics InformationPage for the frontend
     */
    @Override
    public void displayInformation() {
        InformationManager im = InformationManager.getInstance();

        InformationPage page = new InformationPage( "Statistics" );
        im.addPage( page );

        InformationGroup contentGroup = new InformationGroup( page, "Column Statistic Status" );
        im.addGroup( contentGroup );

        InformationTable statisticsInformation = new InformationTable( contentGroup, Arrays.asList( "Column Name", "Type", "Count" ) );

        im.registerInformation( statisticsInformation );

        page.setRefreshFunction( () -> {
            statisticsInformation.reset();
            statisticSchemaMap.values().forEach( schema -> schema.values().forEach( table -> table.forEach( ( k, v ) -> {
                statisticsInformation.addRow( v.getQualifiedColumnName(), v.getType().name(), v.getCount() );
            } ) ) );
        } );

        InformationGroup alphabeticalGroup = new InformationGroup( page, "Alphabetical Statistics" );
        im.addGroup( alphabeticalGroup );

        InformationGroup numericalGroup = new InformationGroup( page, "Numerical Statistics" );
        im.addGroup( numericalGroup );

        InformationGroup temporalGroup = new InformationGroup( page, "Temporal Statistics" );
        im.addGroup( temporalGroup );

        InformationTable temporalInformation = new InformationTable( temporalGroup, Arrays.asList( "Column Name", "Min", "Max" ) );

        InformationTable numericalInformation = new InformationTable( numericalGroup, Arrays.asList( "Column Name", "Min", "Max" ) );

        InformationTable alphabeticalInformation = new InformationTable( alphabeticalGroup, Arrays.asList( "Column Name", "Unique Values" ) );

        im.registerInformation( temporalInformation );
        im.registerInformation( numericalInformation );
        im.registerInformation( alphabeticalInformation );

        InformationGroup rowCountGroup = new InformationGroup( page, "Row Count" );
        im.addGroup( rowCountGroup );

        InformationTable rowCountInformation = new InformationTable( rowCountGroup, Arrays.asList( "Table Name", "Row Count" ) );
        im.registerInformation( rowCountInformation );

        InformationGroup tableSelectGroup = new InformationGroup( page, "Calls per Table" );
        im.addGroup( tableSelectGroup );

        InformationTable tableSelectInformation = new InformationTable( tableSelectGroup, Arrays.asList( "Table Name", "#SELECTS", "#INSERT", "#DELETE", "#UPDATE" ) );
        im.registerInformation( tableSelectInformation );

        InformationGroup cacheGroup = new InformationGroup( page, "Cache Information" );
        im.addGroup( cacheGroup );

        InformationTable cacheInformation = new InformationTable( cacheGroup, Arrays.asList( "Column Name", "Cache Values Min", "Cache Values Max" ) );
        im.registerInformation( cacheInformation );

        InformationGroup actionGroup = new InformationGroup( page, "Action" );
        im.addGroup( actionGroup );
        Action reevaluateAction = parameters -> {
            reevaluateAllStatistics();
            page.refresh();
            return "Recalculated statistics";
        };
        InformationAction reevaluateAllInfo = new InformationAction( actionGroup, "Recalculate Statistics", reevaluateAction );
        actionGroup.addInformation( reevaluateAllInfo );
        im.registerInformation( reevaluateAllInfo );
        page.setRefreshFunction( () -> {
            numericalInformation.reset();
            alphabeticalInformation.reset();
            temporalInformation.reset();
            cacheInformation.reset();
            tableSelectInformation.reset();
            rowCountInformation.reset();
            statisticSchemaMap.values().forEach( schema -> schema.values().forEach( table -> table.forEach( ( k, v ) -> {
                if ( v instanceof NumericalStatisticColumn ) {
                    if ( ((NumericalStatisticColumn<T>) v).getMin() != null && ((NumericalStatisticColumn<T>) v).getMax() != null ) {
                        numericalInformation.addRow(
                                v.getQualifiedColumnName(),
                                ((NumericalStatisticColumn<T>) v).getMin().toString(),
                                ((NumericalStatisticColumn<T>) v).getMax().toString() );
                        if ( !((NumericalStatisticColumn<T>) v).getMinCache().isEmpty() || !((NumericalStatisticColumn<T>) v).getMaxCache().isEmpty() ) {
                            cacheInformation.addRow(
                                    v.getQualifiedColumnName(),
                                    ((NumericalStatisticColumn<T>) v).getMinCache().toString(),
                                    ((NumericalStatisticColumn<T>) v).getMaxCache().toString() );
                        }
                    } else {
                        numericalInformation.addRow( v.getQualifiedColumnName(), "❌", "❌" );
                    }
                }
                if ( v instanceof TemporalStatisticColumn ) {
                    if ( ((TemporalStatisticColumn<T>) v).getMin() != null && ((TemporalStatisticColumn<T>) v).getMax() != null ) {
                        temporalInformation.addRow(
                                v.getQualifiedColumnName(),
                                ((TemporalStatisticColumn<T>) v).getMin().toString(),
                                ((TemporalStatisticColumn<T>) v).getMax().toString() );
                        if ( !((TemporalStatisticColumn<T>) v).getMinCache().isEmpty() || !((TemporalStatisticColumn<T>) v).getMaxCache().isEmpty() ) {
                            cacheInformation.addRow(
                                    v.getQualifiedColumnName(),
                                    ((TemporalStatisticColumn<T>) v).getMinCache().toString(),
                                    ((TemporalStatisticColumn<T>) v).getMaxCache().toString() );
                        }
                    } else {
                        temporalInformation.addRow( v.getQualifiedColumnName(), "❌", "❌" );
                    }
                } else {
                    String values = v.getUniqueValues().toString();
                    if ( !v.isFull ) {
                        alphabeticalInformation.addRow( v.getQualifiedColumnName(), values );
                    } else {
                        alphabeticalInformation.addRow( v.getQualifiedColumnName(), "is Full" );
                    }

                }

            } ) ) );
            tableStatistic.forEach( ( k, v ) -> {
                rowCountInformation.addRow( Catalog.getInstance().getTable( k ).name, v.getNumberOfRows() );
            } );

            tableStatistic.forEach( ( k, v ) -> {
                tableSelectInformation.addRow(
                        Catalog.getInstance().getTable( k ).name,
                        v.getCalls().getNumberOfSelects(),
                        v.getCalls().getNumberOfInserts(),
                        v.getCalls().getNumberOfDeletes(),
                        v.getCalls().getNumberOfUpdates() );
            } );
        } );
    }


    @Override
    public void asyncReevaluateAllStatistics() {
        threadPool.execute( this::reevaluateAllStatistics );
    }


    /**
     * Reevaluates all tables which received changes impacting their statistic data
     *
     * all tables which got changed in a transaction
     */
     /*
    public void apply( List<String> changedTables ) {
        threadPool.execute( () -> changedTables.forEach( this::reevaluateTable ) );
    }
    public void applyTable( String changedQualifiedTable ) {
        this.reevaluateTable( changedQualifiedTable );
    }

      */
    @Override
    public void propertyChange( PropertyChangeEvent evt ) {
        threadPool.execute( this::workQueue );
    }


    @Override
    public void deleteTableToUpdate( Long tableId, Long schemaId ) {
        statisticSchemaMap.get( schemaId ).remove( tableId );
        tableStatistic.remove( tableId );
        this.tablesToUpdate.remove( tableId );
    }


    private void workQueue() {
        while ( !this.tablesToUpdate.isEmpty() ) {
            Long tableId = this.tablesToUpdate.poll();
            if ( Catalog.getInstance().checkIfExistsTable( tableId ) ) {
                reevaluateTable( tableId );
            }
            tableStatistic.remove( tableId );
        }
    }


    @Override
    public void updateRowCountPerTable( Long tableId, Integer number, String source ) {
        StatisticTable statisticTable;
        switch ( source ) {
            case "INSERT":
                if ( tableStatistic.containsKey( tableId ) ) {
                    statisticTable = tableStatistic.get( tableId );
                    int totalRows = statisticTable.getNumberOfRows() + number;

                    statisticTable.setNumberOfRows( totalRows );
                } else {
                    statisticTable = new StatisticTable( tableId );
                    statisticTable.setNumberOfRows( number );
                }
                break;
            case "DELETE":
                if ( tableStatistic.containsKey( tableId ) ) {
                    statisticTable = tableStatistic.get( tableId );
                    int totalRows = statisticTable.getNumberOfRows() - number;

                    statisticTable.setNumberOfRows( totalRows );
                } else {
                    statisticTable = new StatisticTable( tableId );
                }
                break;
            case "TRUNCATE":
            case "SOURCE-TABLE-UI":
                if ( tableStatistic.containsKey( tableId ) ) {
                    statisticTable = tableStatistic.get( tableId );
                } else {
                    statisticTable = new StatisticTable( tableId );
                }
                statisticTable.setNumberOfRows( number );
                break;
            default:
                throw new RuntimeException( "updateRowCountPerTable is not implemented for: " + source );
        }

        tableStatistic.put( tableId, statisticTable );
    }


    @Override
    public void setIndexSize( Long tableId, int indexSize ) {
        if ( tableStatistic.containsKey( tableId ) ) {
            int numberOfRows = tableStatistic.remove( tableId ).getNumberOfRows();
            if ( numberOfRows != indexSize ) {
                // use indexSize because it should be correct
                StatisticTable statisticTable = tableStatistic.get( tableId );
                statisticTable.setNumberOfRows( indexSize );
                tableStatistic.put( tableId, statisticTable );
            }
        } else {
            StatisticTable statisticTable = new StatisticTable( tableId );
            statisticTable.setNumberOfRows( indexSize );
            tableStatistic.put( tableId, statisticTable );
        }
    }


    @Override
    public void setTableCalls( Long tableId, String kind ) {
        TableCalls calls;
        if ( tableStatistic.containsKey( tableId ) ) {
            if ( tableStatistic.get( tableId ).getCalls() != null ) {
                calls = tableStatistic.get( tableId ).getCalls();
            } else {
                calls = new TableCalls( tableId, 0, 0, 0, 0 );
            }
        } else {
            calls = new TableCalls( tableId, 0, 0, 0, 0 );
        }
        updateCalls( tableId, kind, calls );
    }


    private synchronized void updateCalls( Long tableId, String kind, TableCalls calls ) {
        StatisticTable statisticTable;
        if ( tableStatistic.containsKey( tableId ) ) {
            statisticTable = tableStatistic.remove( tableId );
        } else {
            statisticTable = new StatisticTable( tableId );
        }

        switch ( kind ) {
            case "SELECT":
                statisticTable.setCalls( new TableCalls(
                        calls.getTableId(),
                        calls.getNumberOfSelects() + 1,
                        calls.getNumberOfInserts(),
                        calls.getNumberOfDeletes(),
                        calls.getNumberOfUpdates() ) );
                tableStatistic.put( tableId, statisticTable );
                break;
            case "INSERT":
                statisticTable.setCalls( new TableCalls(
                        calls.getTableId(),
                        calls.getNumberOfSelects(),
                        calls.getNumberOfInserts() + 1,
                        calls.getNumberOfDeletes(),
                        calls.getNumberOfUpdates() ) );
                tableStatistic.put( tableId, statisticTable );
                break;
            case "DELETE":
                statisticTable.setCalls( new TableCalls(
                        calls.getTableId(),
                        calls.getNumberOfSelects(),
                        calls.getNumberOfInserts(),
                        calls.getNumberOfDeletes() + 1,
                        calls.getNumberOfUpdates() ) );
                tableStatistic.put( tableId, statisticTable );
                break;
            case "UPDATE":
                statisticTable.setCalls( new TableCalls(
                        calls.getTableId(),
                        calls.getNumberOfSelects() + 1,
                        calls.getNumberOfInserts(),
                        calls.getNumberOfDeletes(),
                        calls.getNumberOfUpdates() + 1 ) );
                tableStatistic.put( tableId, statisticTable );
                break;
            default:
                log.error( "Currently, only SELECT, INSERT, DELETE and UPDATE are available in Statistics." );
        }
    }


    @Override
    public Object getTableStatistic( Long schemaId, Long tableId ) {
        StatisticTable<T> statisticTable = tableStatistic.get( tableId );
        List<NumericalStatisticColumn<T>> numericInfo = new ArrayList<>();
        List<AlphabeticStatisticColumn<T>> alphabeticInfo = new ArrayList<>();
        List<TemporalStatisticColumn<T>> temporalInfo = new ArrayList<>();
        statisticSchemaMap.get( schemaId ).get( tableId ).forEach( ( k, v ) -> {
            if ( v.getType().getFamily() == PolyTypeFamily.NUMERIC ) {
                numericInfo.add( (NumericalStatisticColumn<T>) v );
                statisticTable.setNumericalColumn( numericInfo );
            } else if ( v.getType().getFamily() == PolyTypeFamily.CHARACTER ) {
                alphabeticInfo.add( (AlphabeticStatisticColumn<T>) v );
                statisticTable.setAlphabeticColumn( alphabeticInfo );
            } else if ( PolyType.DATETIME_TYPES.contains( v.getType().getFamily() ) ) {
                temporalInfo.add( (TemporalStatisticColumn<T>) v );
                statisticTable.setTemporalColumn( temporalInfo );
            }
        } );
        return statisticTable;
    }


    @Override
    public synchronized Integer rowCountPerTable( Long tableId ) {
        if ( tableId != null && tableStatistic.containsKey( tableId ) ) {
            log.warn("is there an issue");
            return tableStatistic.get( tableId ).getNumberOfRows();
        } else {
            return null;
        }
    }


    /**
     * This class reevaluates if background tracking should be stopped or restarted depending on the state of the ConfigManager
     */
    class TrackingListener implements Config.ConfigListener {

        @Override
        public void onConfigChange( Config c ) {
            registerTrackingToggle();
        }


        @Override
        public void restart( Config c ) {
            registerTrackingToggle();
        }


        private void registerTrackingToggle() {
            String id = getRevalId();
            if ( id == null && RuntimeConfig.DYNAMIC_QUERYING.getBoolean() && RuntimeConfig.PASSIVE_TRACKING.getBoolean() ) {
                String revalId = BackgroundTaskManager.INSTANCE.registerTask(
                        StatisticsManagerImpl.this::asyncReevaluateAllStatistics,
                        "Reevaluate StatisticsManager.",
                        TaskPriority.LOW,
                        (TaskSchedulingType) RuntimeConfig.STATISTIC_RATE.getEnum() );
                setRevalId( revalId );
            } else if ( id != null && (!RuntimeConfig.PASSIVE_TRACKING.getBoolean() || !RuntimeConfig.DYNAMIC_QUERYING.getBoolean()) ) {
                BackgroundTaskManager.INSTANCE.removeBackgroundTask( getRevalId() );
                setRevalId( null );
            }
        }

    }

}
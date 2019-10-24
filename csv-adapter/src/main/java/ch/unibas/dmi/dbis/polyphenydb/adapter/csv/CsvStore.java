package ch.unibas.dmi.dbis.polyphenydb.adapter.csv;


import ch.unibas.dmi.dbis.polyphenydb.PolyXid;
import ch.unibas.dmi.dbis.polyphenydb.Store;
import ch.unibas.dmi.dbis.polyphenydb.Transaction;
import ch.unibas.dmi.dbis.polyphenydb.adapter.csv.CsvTable.Flavor;
import ch.unibas.dmi.dbis.polyphenydb.catalog.entity.CatalogColumn;
import ch.unibas.dmi.dbis.polyphenydb.catalog.entity.combined.CatalogCombinedTable;
import ch.unibas.dmi.dbis.polyphenydb.jdbc.Context;
import ch.unibas.dmi.dbis.polyphenydb.schema.Schema;
import ch.unibas.dmi.dbis.polyphenydb.schema.SchemaPlus;
import ch.unibas.dmi.dbis.polyphenydb.schema.Table;
import java.io.File;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class CsvStore implements Store {

    private static File csvDir = new File( "testTestCsv" );
    private CsvSchema currentSchema;


    public CsvStore() {
    }


    @Override
    public void createNewSchema( SchemaPlus rootSchema, String name ) {
        currentSchema = new CsvSchema( csvDir, Flavor.FILTERABLE );
    }


    @Override
    public Table createTableSchema( CatalogCombinedTable combinedTable ) {
        return currentSchema.createCsvTable( combinedTable );
    }


    @Override
    public Schema getCurrentSchema() {
        return currentSchema;
    }


    @Override
    public void createTable( Context context, CatalogCombinedTable combinedTable ) {
        throw new RuntimeException( "CSV adapter does not support creating table" );
    }


    @Override
    public void dropTable( CatalogCombinedTable combinedTable ) {
        log.warn( "CSV adapter does not support dropping tables!" );
    }


    @Override
    public void addColumn( CatalogCombinedTable catalogTable, CatalogColumn catalogColumn ) {
        log.warn( "CSV adapter does not support adding columns!" );
    }


    @Override
    public void dropColumn( CatalogCombinedTable catalogTable, CatalogColumn catalogColumn ) {
        log.warn( "CSV adapter does not support dropping columns!" );
    }


    @Override
    public boolean prepare( PolyXid xid ) {
        log.warn( "CSV Store does not support distributed transactions." );
        return true;
    }


    @Override
    public void commit( PolyXid xid ) {
        log.warn( "CSV Store does not support distributed transactions." );
    }


    @Override
    public void truncate( Transaction transaction, CatalogCombinedTable table ) {
        log.warn( "CSV Store does not support truncate." );
    }


    @Override
    public void updateColumnType( CatalogColumn catalogColumn ) {
        throw new RuntimeException( "CSV adapter does not support updating column types!" );
    }
}
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
 */

package org.polypheny.db.sql.sql;


import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import org.polypheny.db.algebra.constant.NullCollation;
import org.polypheny.db.sql.sql.dialect.AccessSqlDialect;
import org.polypheny.db.sql.sql.dialect.AnsiSqlDialect;
import org.polypheny.db.sql.sql.dialect.BigQuerySqlDialect;
import org.polypheny.db.sql.sql.dialect.Db2SqlDialect;
import org.polypheny.db.sql.sql.dialect.DerbySqlDialect;
import org.polypheny.db.sql.sql.dialect.FirebirdSqlDialect;
import org.polypheny.db.sql.sql.dialect.H2SqlDialect;
import org.polypheny.db.sql.sql.dialect.HiveSqlDialect;
import org.polypheny.db.sql.sql.dialect.HsqldbSqlDialect;
import org.polypheny.db.sql.sql.dialect.InfobrightSqlDialect;
import org.polypheny.db.sql.sql.dialect.InformixSqlDialect;
import org.polypheny.db.sql.sql.dialect.IngresSqlDialect;
import org.polypheny.db.sql.sql.dialect.InterbaseSqlDialect;
import org.polypheny.db.sql.sql.dialect.JethroDataSqlDialect;
import org.polypheny.db.sql.sql.dialect.JethroDataSqlDialect.JethroInfoCache;
import org.polypheny.db.sql.sql.dialect.LucidDbSqlDialect;
import org.polypheny.db.sql.sql.dialect.MssqlSqlDialect;
import org.polypheny.db.sql.sql.dialect.MysqlSqlDialect;
import org.polypheny.db.sql.sql.dialect.NeoviewSqlDialect;
import org.polypheny.db.sql.sql.dialect.NetezzaSqlDialect;
import org.polypheny.db.sql.sql.dialect.OracleSqlDialect;
import org.polypheny.db.sql.sql.dialect.ParaccelSqlDialect;
import org.polypheny.db.sql.sql.dialect.PhoenixSqlDialect;
import org.polypheny.db.sql.sql.dialect.PolyphenyDbSqlDialect;
import org.polypheny.db.sql.sql.dialect.PostgresqlSqlDialect;
import org.polypheny.db.sql.sql.dialect.RedshiftSqlDialect;
import org.polypheny.db.sql.sql.dialect.SybaseSqlDialect;
import org.polypheny.db.sql.sql.dialect.TeradataSqlDialect;
import org.polypheny.db.sql.sql.dialect.VerticaSqlDialect;


/**
 * The default implementation of a <code>SqlDialectFactory</code>.
 */
public class SqlDialectFactoryImpl implements SqlDialectFactory {

    public static final SqlDialectFactoryImpl INSTANCE = new SqlDialectFactoryImpl();

    private final JethroInfoCache jethroCache = JethroDataSqlDialect.createCache();


    @Override
    public SqlDialect create( DatabaseMetaData databaseMetaData ) {
        String databaseProductName;
        int databaseMajorVersion;
        int databaseMinorVersion;
        String databaseVersion;
        try {
            databaseProductName = databaseMetaData.getDatabaseProductName();
            databaseMajorVersion = databaseMetaData.getDatabaseMajorVersion();
            databaseMinorVersion = databaseMetaData.getDatabaseMinorVersion();
            databaseVersion = databaseMetaData.getDatabaseProductVersion();
        } catch ( SQLException e ) {
            throw new RuntimeException( "while detecting database product", e );
        }
        final String upperProductName = databaseProductName.toUpperCase( Locale.ROOT ).trim();
        final String quoteString = getIdentifierQuoteString( databaseMetaData );
        final NullCollation nullCollation = getNullCollation( databaseMetaData );
        final SqlDialect.Context c = SqlDialect.EMPTY_CONTEXT
                .withDatabaseProductName( databaseProductName )
                .withDatabaseMajorVersion( databaseMajorVersion )
                .withDatabaseMinorVersion( databaseMinorVersion )
                .withDatabaseVersion( databaseVersion )
                .withIdentifierQuoteString( quoteString )
                .withNullCollation( nullCollation );
        switch ( upperProductName ) {
            case "ACCESS":
                return new AccessSqlDialect( c );
            case "APACHE DERBY":
                return new DerbySqlDialect( c );
            case "DBMS:CLOUDSCAPE":
                return new DerbySqlDialect( c );
            case "HIVE":
                return new HiveSqlDialect( c );
            case "INGRES":
                return new IngresSqlDialect( c );
            case "INTERBASE":
                return new InterbaseSqlDialect( c );
            case "JETHRODATA":
                return new JethroDataSqlDialect( c.withJethroInfo( jethroCache.get( databaseMetaData ) ) );
            case "LUCIDDB":
                return new LucidDbSqlDialect( c );
            case "ORACLE":
                return new OracleSqlDialect( c );
            case "PHOENIX":
                return new PhoenixSqlDialect( c );
            case "MYSQL (INFOBRIGHT)":
                return new InfobrightSqlDialect( c );
            case "MYSQL":
                return new MysqlSqlDialect( c );
            case "REDSHIFT":
                return new RedshiftSqlDialect( c );
        }
        // Now the fuzzy matches.
        if ( databaseProductName.startsWith( "DB2" ) ) {
            return new Db2SqlDialect( c );
        } else if ( upperProductName.contains( "FIREBIRD" ) ) {
            return new FirebirdSqlDialect( c );
        } else if ( databaseProductName.startsWith( "Informix" ) ) {
            return new InformixSqlDialect( c );
        } else if ( upperProductName.contains( "NETEZZA" ) ) {
            return new NetezzaSqlDialect( c );
        } else if ( upperProductName.contains( "PARACCEL" ) ) {
            return new ParaccelSqlDialect( c );
        } else if ( databaseProductName.startsWith( "HP Neoview" ) ) {
            return new NeoviewSqlDialect( c );
        } else if ( upperProductName.contains( "POSTGRE" ) ) {
            return new PostgresqlSqlDialect( c );
        } else if ( upperProductName.contains( "SQL SERVER" ) ) {
            return new MssqlSqlDialect( c );
        } else if ( upperProductName.contains( "SYBASE" ) ) {
            return new SybaseSqlDialect( c );
        } else if ( upperProductName.contains( "TERADATA" ) ) {
            return new TeradataSqlDialect( c );
        } else if ( upperProductName.contains( "HSQL" ) ) {
            return new HsqldbSqlDialect( c );
        } else if ( upperProductName.contains( "H2" ) ) {
            return new H2SqlDialect( c );
        } else if ( upperProductName.contains( "VERTICA" ) ) {
            return new VerticaSqlDialect( c );
        } else {
            return new AnsiSqlDialect( c );
        }
    }


    private NullCollation getNullCollation( DatabaseMetaData databaseMetaData ) {
        try {
            if ( databaseMetaData.nullsAreSortedAtEnd() ) {
                return NullCollation.LAST;
            } else if ( databaseMetaData.nullsAreSortedAtStart() ) {
                return NullCollation.FIRST;
            } else if ( databaseMetaData.nullsAreSortedLow() ) {
                return NullCollation.LOW;
            } else if ( databaseMetaData.nullsAreSortedHigh() ) {
                return NullCollation.HIGH;
            } else {
                throw new IllegalArgumentException( "cannot deduce null collation" );
            }
        } catch ( SQLException e ) {
            throw new IllegalArgumentException( "cannot deduce null collation", e );
        }
    }


    private String getIdentifierQuoteString( DatabaseMetaData databaseMetaData ) {
        try {
            return databaseMetaData.getIdentifierQuoteString();
        } catch ( SQLException e ) {
            throw new IllegalArgumentException( "cannot deduce identifier quote string", e );
        }
    }


    /**
     * Returns a basic dialect for a given product, or null if none is known.
     */
    static SqlDialect simple( SqlDialect.DatabaseProduct databaseProduct ) {
        switch ( databaseProduct ) {
            case ACCESS:
                return AccessSqlDialect.DEFAULT;
            case BIG_QUERY:
                return BigQuerySqlDialect.DEFAULT;
            case POLYPHENYDB:
                return PolyphenyDbSqlDialect.DEFAULT;
            case DB2:
                return Db2SqlDialect.DEFAULT;
            case DERBY:
                return DerbySqlDialect.DEFAULT;
            case FIREBIRD:
                return FirebirdSqlDialect.DEFAULT;
            case H2:
                return H2SqlDialect.DEFAULT;
            case HIVE:
                return HiveSqlDialect.DEFAULT;
            case HSQLDB:
                return HsqldbSqlDialect.DEFAULT;
            case INFOBRIGHT:
                return InfobrightSqlDialect.DEFAULT;
            case INFORMIX:
                return InformixSqlDialect.DEFAULT;
            case INGRES:
                return IngresSqlDialect.DEFAULT;
            case INTERBASE:
                return InterbaseSqlDialect.DEFAULT;
            case JETHRO:
                throw new RuntimeException( "Jethro does not support simple creation" );
            case LUCIDDB:
                return LucidDbSqlDialect.DEFAULT;
            case MSSQL:
                return MssqlSqlDialect.DEFAULT;
            case MYSQL:
                return MysqlSqlDialect.DEFAULT;
            case NEOVIEW:
                return NeoviewSqlDialect.DEFAULT;
            case NETEZZA:
                return NetezzaSqlDialect.DEFAULT;
            case ORACLE:
                return OracleSqlDialect.DEFAULT;
            case PARACCEL:
                return ParaccelSqlDialect.DEFAULT;
            case PHOENIX:
                return PhoenixSqlDialect.DEFAULT;
            case POSTGRESQL:
                return PostgresqlSqlDialect.DEFAULT;
            case REDSHIFT:
                return RedshiftSqlDialect.DEFAULT;
            case SYBASE:
                return SybaseSqlDialect.DEFAULT;
            case TERADATA:
                return TeradataSqlDialect.DEFAULT;
            case VERTICA:
                return VerticaSqlDialect.DEFAULT;
            case SQLSTREAM:
            case UNKNOWN:
            default:
                return null;
        }
    }

}

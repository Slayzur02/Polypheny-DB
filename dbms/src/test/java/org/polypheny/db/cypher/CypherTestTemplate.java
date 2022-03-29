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

package org.polypheny.db.cypher;

import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import lombok.Getter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.polypheny.db.TestHelper;
import org.polypheny.db.TestHelper.CypherConnection;
import org.polypheny.db.cypher.helper.TestEdge;
import org.polypheny.db.cypher.helper.TestGraphObject;
import org.polypheny.db.cypher.helper.TestLiteral;
import org.polypheny.db.cypher.helper.TestNode;
import org.polypheny.db.cypher.helper.TestObject;
import org.polypheny.db.schema.graph.GraphPropertyHolder;
import org.polypheny.db.schema.graph.PolyEdge;
import org.polypheny.db.schema.graph.PolyNode;
import org.polypheny.db.util.Pair;
import org.polypheny.db.webui.models.Result;

public class CypherTestTemplate {


    public static final Gson GSON = new GsonBuilder().enableComplexMapKeySerialization().create();
    protected static final String SINGLE_NODE_PERSON_1 = "CREATE (p:Person {name: 'Max'})";
    protected static final String SINGLE_NODE_PERSON_2 = "CREATE (p:Person {name: 'Hans'})";

    protected static final String SINGLE_NODE_PERSON_COMPLEX_1 = "CREATE (p:Person {name: 'Ann', age: 45, depno: 13})";
    protected static final String SINGLE_NODE_PERSON_COMPLEX_2 = "CREATE (p:Person {name: 'Bob', age: 31, depno: 13})";

    protected static final String SINGLE_NODE_ANIMAL = "CREATE (a:Animal {name:'Kira', age:3, type:'dog'})";
    protected static final String SINGLE_EDGE_1 = "CREATE (p:Person {name: 'Max'})-[rel:OWNER_OF]->(a:Animal {name:'Kira', age:3, type:'dog'})";
    protected static final String SINGLE_EDGE_2 = "CREATE (p:Person {name: 'Max'})-[rel:KNOWS {since: 1994}]->(a:Person {name:'Hans', age:31})";
    protected static final String MULTIPLE_HOP_EDGE = "CREATE (n:Person)-[f:FRIEND_OF {since: 1995}]->(p:Person {name: 'Max'})-[rel:OWNER_OF]->(a:Animal {name:'Kira'})";
    protected final TestNode ANN = TestNode.from(
            List.of( "Person" ),
            Pair.of( "name", "Bob" ),
            Pair.of( "age", 31 ),
            Pair.of( "depno", 13 ) );
    protected final TestNode BOB = TestNode.from(
            List.of( "Person" ),
            Pair.of( "name", "Ann" ),
            Pair.of( "age", 45 ),
            Pair.of( "depno", 13 ) );

    protected final TestNode MAX = TestNode.from( List.of( "Person" ), Pair.of( "name", "Max" ) );
    protected final TestNode HANS = TestNode.from( List.of( "Person" ), Pair.of( "name", "Hans" ) );
    protected final TestNode KIRA = TestNode.from( List.of( "Animal" ), Pair.of( "name", "Kira" ), Pair.of( "age", 3 ), Pair.of( "type", "dog" ) );


    @BeforeClass
    public static void start() {
        //noinspection ResultOfMethodCallIgnored
        TestHelper.getInstance();
        createSchema();
    }


    public static void createSchema() {
        execute( "CREATE DATABASE test" );
        execute( "USE GRAPH test" );
    }


    @AfterClass
    public static void tearDown() {
        deleteData();
    }


    private static void deleteData() {
        execute( "DROP DATABASE test IF EXISTS" );
    }


    public static Result execute( String query ) {
        Result res = CypherConnection.executeGetResponse( query );
        if ( res.getError() != null ) {
            fail( res.getError() );
        }
        return res;
    }


    protected boolean containsNodes( Result res, boolean exclusive, TestObject... nodes ) {
        if ( res.getHeader().length == 1 && res.getHeader()[0].dataType.toLowerCase( Locale.ROOT ).contains( "node" ) ) {
            return contains( res.getData(), exclusive, 0, PolyNode.class, nodes );
        }
        throw new UnsupportedOperationException();
    }


    protected boolean containsEdges( Result res, boolean exclusive, TestEdge... edges ) {
        if ( res.getHeader().length == 1 && res.getHeader()[0].dataType.toLowerCase( Locale.ROOT ).contains( "edge" ) ) {
            return contains( res.getData(), exclusive, 0, PolyEdge.class, edges );
        }
        throw new UnsupportedOperationException();
    }


    public boolean containsIn( Result res, boolean exclusive, int index, TestGraphObject... expected ) {
        boolean successful = true;
        Class<? extends GraphPropertyHolder> clazz = null;
        if ( expected.length > 0 ) {
            Type type = Type.from( expected[0] );
            successful = is( res, type, index );
            clazz = (Class<? extends GraphPropertyHolder>) type.polyClass;
        }
        if ( !successful ) {
            return false;
        }

        return contains( res.getData(), exclusive, index, clazz, expected );
    }


    public boolean containsIn( Result actual, boolean exclusive, int index, @Nullable String name, TestLiteral... expected ) {
        // simple object match
        List<String> cols = new ArrayList<>();

        for ( String[] entry : actual.getData() ) {
            cols.add( entry[index] );
        }
        assert !exclusive || cols.size() == expected.length;

        boolean correct = true;
        if ( name != null ) {
            correct = actual.getHeader()[index].name.equals( name );
        }

        boolean contains = correct;
        for ( TestObject object : expected ) {
            contains &= cols.stream().anyMatch( n -> object.matches( n, exclusive ) );
        }

        return contains;

    }


    protected boolean containsRows( Result actual, boolean exclusive, boolean ordered, Row... rows ) {
        List<List<Object>> parsed = new ArrayList<>();

        int i = 0;
        for ( Row row : rows ) {
            parsed.add( row.asList( actual.getData()[i] ) );
            i++;
        }

        assert !exclusive || actual.getData().length >= rows.length;

        if ( ordered ) {
            return matchesExactRows( parsed, rows );
        } else {
            return matchesUnorderedRows( parsed, rows );
        }
    }


    private boolean matchesUnorderedRows( List<List<Object>> parsed, Row[] rows ) {

        List<Integer> used = new ArrayList<>();
        for ( Row row : rows ) {

            int i = 0;
            boolean matches = false;
            for ( List<Object> objects : parsed ) {

                if ( !matches && !used.contains( i ) ) {
                    if ( row.matches( objects ) ) {
                        used.add( i );
                        matches = true;
                    }
                }

                i++;
            }
            if ( !matches ) {
                return false;
            }
        }
        return true;

    }


    private boolean matchesExactRows( List<List<Object>> parsed, Row[] rows ) {
        boolean matches = true;
        int j = 0;
        for ( Row row : rows ) {
            matches &= row.matches( parsed.get( j ) );
            j++;
        }
        return matches;
    }


    private <T extends GraphPropertyHolder> boolean contains( String[][] actual, boolean exclusive, int index, Class<T> clazz, TestObject[] expected ) {
        List<T> parsed = new ArrayList<>();

        for ( String[] entry : actual ) {
            parsed.add( GSON.fromJson( entry[index], clazz ) );
        }

        assert !exclusive || parsed.size() == expected.length;

        boolean contains = true;
        for ( TestObject node : expected ) {
            contains &= parsed.stream().anyMatch( n -> node.matches( n, exclusive ) );
        }

        return contains;
    }


    public Result matchAndReturnAllNodes() {
        return execute( "MATCH (n) RETURN n" );
    }


    protected void assertNode( Result res, int index ) {
        assert is( res, Type.NODE, index );
    }


    protected void assertEdge( Result res, int index ) {
        assert is( res, Type.EDGE, index );
    }


    protected boolean is( Result res, Type type, int index ) {
        assert res.getHeader().length >= index;

        return res.getHeader()[index].dataType.toLowerCase( Locale.ROOT ).contains( type.getTypeName() );
    }


    protected void assertEmpty( Result res ) {
        assert res.getData().length == 0;
    }


    @Getter
    public enum Type {
        NODE( "node", TestNode.class, PolyNode.class ),
        EDGE( "edge", TestNode.class, PolyNode.class ),
        STRING( "varchar", TestNode.class, PolyNode.class );


        private final String typeName;
        private final Class<? extends TestObject> testClass;
        private final Class<?> polyClass;


        Type( String name, Class<? extends TestObject> testClass, Class<?> polyClass ) {
            this.typeName = name;
            this.testClass = testClass;
            this.polyClass = polyClass;
        }


        public static Type from( TestObject object ) {
            if ( object instanceof TestLiteral ) {
                return STRING;
            } else if ( object instanceof TestNode ) {
                return NODE;
            } else if ( object instanceof TestEdge ) {
                return EDGE;
            }
            throw new UnsupportedOperationException();
        }
    }


    public static class Row {

        final TestObject[] values;


        public Row( TestObject[] values ) {
            this.values = values;
        }


        static Row of( TestObject... values ) {
            return new Row( values );
        }


        public List<Object> asList( String[] actual ) {
            List<Object> res = new ArrayList<>();
            assert this.values.length == actual.length;

            int i = 0;
            for ( String val : actual ) {
                res.add( this.values[i].toPoly( val ) );
                i++;
            }
            return res;
        }


        public boolean matches( List<Object> objects ) {
            int i = 0;
            boolean matches = true;

            for ( Object object : objects ) {
                matches &= values[i].matches( object, true );
                i++;
            }
            return matches;
        }

    }

}
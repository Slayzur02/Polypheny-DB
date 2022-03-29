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

package org.polypheny.db.runtime;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.function.Deterministic;
import org.polypheny.db.runtime.PolyCollections.PolyDirectory;
import org.polypheny.db.runtime.PolyCollections.PolyMap;
import org.polypheny.db.schema.graph.GraphObject;
import org.polypheny.db.schema.graph.GraphPropertyHolder;
import org.polypheny.db.schema.graph.PolyEdge;
import org.polypheny.db.schema.graph.PolyEdge.RelationshipDirection;
import org.polypheny.db.schema.graph.PolyGraph;
import org.polypheny.db.schema.graph.PolyNode;
import org.polypheny.db.schema.graph.PolyPath;

@Deterministic
@Slf4j
public class CypherFunctions {

    public static void print( Object obj ) {
        System.out.println( obj.toString() );
    }


    @SuppressWarnings("unused")
    public static Enumerable<PolyPath> pathMatch( PolyGraph graph, PolyPath comp ) {
        return Linq4j.asEnumerable( graph.extract( comp ) );
    }


    @SuppressWarnings("unused")
    public static Enumerable<PolyNode> nodeMatch( PolyGraph graph, PolyNode node ) {
        return Linq4j.asEnumerable( graph.extract( node ) );
    }


    public static Enumerable<PolyNode> nodeExtract( PolyGraph graph ) {
        return Linq4j.asEnumerable( graph.getNodes().values() );
    }


    public static Enumerable<?> toGraph( Enumerable<PolyNode> nodes, Enumerable<PolyEdge> edges ) {

        PolyMap<String, PolyNode> ns = new PolyMap<>();
        for ( PolyNode node : nodes ) {
            ns.put( node.id, node );
        }

        PolyMap<String, PolyEdge> es = new PolyMap<>();
        for ( PolyEdge edge : edges ) {
            es.put( edge.id, edge );
        }

        return Linq4j.asEnumerable( List.of( new PolyGraph( ns, es ) ) );

    }


    public static Enumerable<PolyEdge> toEdge( Enumerable<?> edge ) {
        List<PolyEdge> edges = new ArrayList<>();

        String oldId = null;
        String oldSourceId = null;
        String oldTargetId = null;
        Set<String> oldLabels = new HashSet<>();
        Map<String, Comparable<?>> oldProps = new HashMap<>();

        for ( Object value : edge ) {
            Object[] o = (Object[]) value;
            String id = (String) o[0];
            String label = (String) o[1];
            String sourceId = (String) o[2];
            String targetId = (String) o[3];
            // id is 4
            String key = (String) o[5];
            String val = (String) o[6];

            if ( !id.equals( oldId ) ) {
                if ( oldId != null ) {
                    edges.add( new PolyEdge( oldId, new PolyDirectory( oldProps ), List.copyOf( oldLabels ), oldSourceId, oldTargetId, RelationshipDirection.LEFT_TO_RIGHT ) );
                }
                oldId = id;
                oldLabels = new HashSet<>();
                oldSourceId = sourceId;
                oldTargetId = targetId;
                oldProps = new HashMap<>();
            }
            oldLabels.add( label );

            if ( key != null ) {
                // id | key | value | source | target
                // 13 | null| null | 12      | 10 ( no key value present )
                oldProps.put( key, val );
            }
        }

        if ( oldId != null ) {
            edges.add( new PolyEdge( oldId, new PolyDirectory( oldProps ), List.copyOf( oldLabels ), oldSourceId, oldTargetId, RelationshipDirection.LEFT_TO_RIGHT ) );
        }

        return Linq4j.asEnumerable( edges );
    }


    public static Enumerable<PolyNode> toNode( Enumerable<?> node ) {
        List<PolyNode> nodes = new ArrayList<>();

        String oldId = null;
        Set<String> oldLabels = new HashSet<>();
        Map<String, Comparable<?>> oldProps = new HashMap<>();

        for ( Object value : node ) {
            Object[] o = (Object[]) value;
            String id = (String) o[0];
            String label = (String) o[1];
            // id is 2
            String key = (String) o[3];
            String val = (String) o[4];

            if ( !id.equals( oldId ) ) {
                if ( oldId != null ) {
                    nodes.add( new PolyNode( oldId, new PolyDirectory( oldProps ), List.copyOf( oldLabels ) ) );
                }
                oldId = id;
                oldLabels = new HashSet<>();
                oldProps = new HashMap<>();
            }
            if ( label != null ) {
                // eventually no labels
                oldLabels.add( label );
            }
            if ( key != null ) {
                // eventually no properties present
                oldProps.put( key, val );
            }
        }

        if ( oldId != null ) {
            nodes.add( new PolyNode( oldId, new PolyDirectory( oldProps ), List.copyOf( oldLabels ) ) );
        }

        return Linq4j.asEnumerable( nodes );

    }


    @SuppressWarnings("unused")
    public static boolean hasProperty( PolyNode node, String property ) {
        return node.properties.containsKey( property );
    }


    @SuppressWarnings("unused")
    public static boolean hasLabel( PolyNode node, String label ) {
        return node.labels.contains( label );
    }


    @SuppressWarnings("unused")
    public static GraphObject extractFrom( PolyPath path, int index ) {
        return path.get( index );
    }


    @SuppressWarnings("unused")
    public static String extractProperty( GraphPropertyHolder holder, String key ) {
        if ( holder.getProperties().containsKey( key ) ) {
            return holder.getProperties().get( key ).toString();
        }
        return null;
    }


    @SuppressWarnings("unused")
    public static String extractLabel( GraphPropertyHolder holder ) {
        return holder.getLabels().get( 0 );
    }


    @SuppressWarnings("unused")
    public static List<String> extractLabels( GraphPropertyHolder holder ) {
        return holder.getLabels();
    }


    @SuppressWarnings("unused")
    public static List<?> toList( Object obj ) {
        if ( obj == null ) {
            return List.of();
        }
        if ( obj instanceof List ) {
            return (List<?>) obj;
        }
        return List.of( obj );
    }


    @SuppressWarnings("unused")
    public static PolyEdge adjustEdge( PolyEdge edge, String left, String right ) {
        return edge.from( left, right );
    }


    @SuppressWarnings("unused")
    public static GraphPropertyHolder setProperty( GraphPropertyHolder target, String key, Object value ) {
        target.properties.put( key, value );
        return target;
    }


    @SuppressWarnings("unused")
    public static GraphPropertyHolder setLabels( GraphPropertyHolder target, List<String> labels, boolean replace ) {
        if ( replace ) {
            target.labels.clear();
        }
        target.setLabels( labels );
        return target;
    }


    @SuppressWarnings("unused")
    public static GraphPropertyHolder setProperties( GraphPropertyHolder target, List<String> keys, List<Object> values, boolean replace ) {
        if ( replace ) {
            target.properties.clear();
        }

        int i = 0;
        for ( String key : keys ) {
            target.properties.put( key, values.get( i ) );
            i++;
        }
        return target;
    }


    @SuppressWarnings("unused")
    public static GraphPropertyHolder removeLabels( GraphPropertyHolder target, List<String> labels ) {
        target.labels.removeAll( labels );
        return target;
    }


    @SuppressWarnings("unused")
    public static GraphPropertyHolder removeProperty( GraphPropertyHolder target, String key ) {
        target.properties.remove( key );
        return target;
    }

}
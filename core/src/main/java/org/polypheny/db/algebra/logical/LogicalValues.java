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

package org.polypheny.db.algebra.logical;


import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.polypheny.db.algebra.AlgCollationTraitDef;
import org.polypheny.db.algebra.AlgInput;
import org.polypheny.db.algebra.AlgNode;
import org.polypheny.db.algebra.AlgShuttle;
import org.polypheny.db.algebra.SerializableAlgNode;
import org.polypheny.db.algebra.core.Values;
import org.polypheny.db.algebra.metadata.AlgMdCollation;
import org.polypheny.db.algebra.metadata.AlgMetadataQuery;
import org.polypheny.db.algebra.type.AlgDataType;
import org.polypheny.db.algebra.type.AlgRecordType;
import org.polypheny.db.plan.AlgOptCluster;
import org.polypheny.db.plan.AlgTraitSet;
import org.polypheny.db.plan.Convention;
import org.polypheny.db.rex.RexLiteral;
import org.polypheny.db.type.PolyType;


/**
 * Sub-class of {@link org.polypheny.db.algebra.core.Values} not targeted at any particular engine or calling convention.
 */
public class LogicalValues extends Values {

    /**
     * Creates a LogicalValues.
     *
     * Use {@link #create} unless you know what you're doing.
     *
     * @param cluster Cluster that this relational expression belongs to
     * @param rowType Row type for tuples produced by this rel
     * @param tuples 2-dimensional array of tuple values to be produced; outer list contains tuples; each inner list is one tuple; all tuples must be of same length, conforming to rowType
     */
    public LogicalValues( AlgOptCluster cluster, AlgTraitSet traitSet, AlgDataType rowType, ImmutableList<ImmutableList<RexLiteral>> tuples ) {
        super( cluster, rowType, tuples, traitSet );
    }


    /**
     * Creates a LogicalValues by parsing serialized output.
     */
    public LogicalValues( AlgInput input ) {
        super( input );
    }


    /**
     * Creates a LogicalValues.
     */
    public static LogicalValues create( AlgOptCluster cluster, final AlgDataType rowType, final ImmutableList<ImmutableList<RexLiteral>> tuples ) {
        final AlgMetadataQuery mq = cluster.getMetadataQuery();
        final AlgTraitSet traitSet = cluster.traitSetOf( Convention.NONE )
                .replaceIfs( AlgCollationTraitDef.INSTANCE, () -> AlgMdCollation.values( mq, rowType, tuples ) );
        return new LogicalValues( cluster, traitSet, rowType, tuples );
    }


    @Override
    public AlgNode copy( AlgTraitSet traitSet, List<AlgNode> inputs ) {
        assert traitSet.containsIfApplicable( Convention.NONE );
        assert inputs.isEmpty();
        return new LogicalValues( getCluster(), traitSet, rowType, tuples );
    }


    /**
     * Creates a LogicalValues that outputs no rows of a given row type.
     */
    public static LogicalValues createEmpty( AlgOptCluster cluster, AlgDataType rowType ) {
        return create( cluster, rowType, ImmutableList.of() );
    }


    /**
     * Creates a LogicalValues that outputs one row and one column.
     */
    public static LogicalValues createOneRow( AlgOptCluster cluster ) {
        final AlgDataType rowType =
                cluster.getTypeFactory()
                        .builder()
                        .add( "ZERO", null, PolyType.INTEGER )
                        .nullable( false )
                        .build();
        final ImmutableList<ImmutableList<RexLiteral>> tuples =
                ImmutableList.of(
                        ImmutableList.of(
                                cluster.getRexBuilder().makeExactLiteral(
                                        BigDecimal.ZERO,
                                        rowType.getFieldList().get( 0 ).getType() ) ) );
        return create( cluster, rowType, tuples );
    }


    @Override
    public AlgNode accept( AlgShuttle shuttle ) {
        return shuttle.visit( this );
    }


    @Getter
    @NoArgsConstructor
    public static class SerializableValues extends SerializableAlgNode {

        private AlgRecordType rowType;
        private ImmutableList<ImmutableList<RexLiteral>> tuples;


        public SerializableValues( AlgRecordType rowType, ImmutableList<ImmutableList<RexLiteral>> tuples ) {
            this.rowType = rowType;
            this.tuples = tuples;
        }


        @Override
        public void writeExternal( ObjectOutput out ) throws IOException {
            out.writeObject( rowType );
            out.writeObject( tuples );
        }


        @SuppressWarnings("unchecked")
        @Override
        public void readExternal( ObjectInput in ) throws IOException, ClassNotFoundException {
            this.rowType = (AlgRecordType) in.readObject();
            this.tuples = (ImmutableList<ImmutableList<RexLiteral>>) in.readObject();
        }


        @Override
        public void accept( SerializableActivator activator ) {
            activator.visit( this );
        }

    }

}


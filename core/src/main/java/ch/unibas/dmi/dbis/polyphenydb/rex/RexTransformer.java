/*
 * This file is based on code taken from the Apache Calcite project, which was released under the Apache License.
 * The changes are released under the MIT license.
 *
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
 *
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Databases and Information Systems Research Group, University of Basel, Switzerland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package ch.unibas.dmi.dbis.polyphenydb.rex;


import ch.unibas.dmi.dbis.polyphenydb.rel.type.RelDataType;
import ch.unibas.dmi.dbis.polyphenydb.sql.SqlKind;
import ch.unibas.dmi.dbis.polyphenydb.sql.SqlOperator;
import ch.unibas.dmi.dbis.polyphenydb.sql.fun.SqlStdOperatorTable;
import ch.unibas.dmi.dbis.polyphenydb.sql.type.SqlTypeUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;


/**
 * Takes a tree of {@link RexNode} objects and transforms it into another in one sense equivalent tree. Nodes in tree will be modified and hence tree will not remain unchanged.
 *
 * NOTE: You must validate the tree of RexNodes before using this class.
 */
public class RexTransformer {

    private RexNode root;
    private final RexBuilder rexBuilder;
    private int isParentsCount;
    private final Set<SqlOperator> transformableOperators = new HashSet<>();


    public RexTransformer( RexNode root, RexBuilder rexBuilder ) {
        this.root = root;
        this.rexBuilder = rexBuilder;
        isParentsCount = 0;

        transformableOperators.add( SqlStdOperatorTable.AND );

        // NOTE the OR operator is NOT missing. see {@link ch.unibas.dmi.dbis.polyphenydb.test.RexTransformerTest}
        transformableOperators.add( SqlStdOperatorTable.EQUALS );
        transformableOperators.add( SqlStdOperatorTable.NOT_EQUALS );
        transformableOperators.add( SqlStdOperatorTable.GREATER_THAN );
        transformableOperators.add( SqlStdOperatorTable.GREATER_THAN_OR_EQUAL );
        transformableOperators.add( SqlStdOperatorTable.LESS_THAN );
        transformableOperators.add( SqlStdOperatorTable.LESS_THAN_OR_EQUAL );
    }


    private boolean isBoolean( RexNode node ) {
        RelDataType type = node.getType();
        return SqlTypeUtil.inBooleanFamily( type );
    }


    private boolean isNullable( RexNode node ) {
        return node.getType().isNullable();
    }


    private boolean isTransformable( RexNode node ) {
        if ( 0 == isParentsCount ) {
            return false;
        }

        if ( node instanceof RexCall ) {
            RexCall call = (RexCall) node;
            return !transformableOperators.contains( call.getOperator() ) && isNullable( node );
        }
        return isNullable( node );
    }


    public RexNode transformNullSemantics() {
        root = transformNullSemantics( root );
        return root;
    }


    private RexNode transformNullSemantics( RexNode node ) {
        assert isParentsCount >= 0 : "Cannot be negative";
        if ( !isBoolean( node ) ) {
            return node;
        }

        Boolean directlyUnderIs = null;
        if ( node.isA( SqlKind.IS_TRUE ) ) {
            directlyUnderIs = Boolean.TRUE;
            isParentsCount++;
        } else if ( node.isA( SqlKind.IS_FALSE ) ) {
            directlyUnderIs = Boolean.FALSE;
            isParentsCount++;
        }

        // Special case when we have a Literal, Parameter or Identifier directly as an operand to IS TRUE or IS FALSE.
        if ( null != directlyUnderIs ) {
            RexCall call = (RexCall) node;
            assert isParentsCount > 0 : "Stack should not be empty";
            assert 1 == call.operands.size();
            RexNode operand = call.operands.get( 0 );
            if ( operand instanceof RexLiteral || operand instanceof RexInputRef || operand instanceof RexDynamicParam ) {
                if ( isNullable( node ) ) {
                    RexNode notNullNode =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.IS_NOT_NULL,
                                    operand );
                    RexNode boolNode =
                            rexBuilder.makeLiteral(
                                    directlyUnderIs.booleanValue() );
                    RexNode eqNode =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.EQUALS,
                                    operand,
                                    boolNode );
                    RexNode andBoolNode =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.AND,
                                    notNullNode,
                                    eqNode );

                    return andBoolNode;
                } else {
                    RexNode boolNode =
                            rexBuilder.makeLiteral(
                                    directlyUnderIs.booleanValue() );
                    RexNode andBoolNode =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.EQUALS,
                                    node,
                                    boolNode );
                    return andBoolNode;
                }
            }

            // else continue as normal
        }

        if ( node instanceof RexCall ) {
            RexCall call = (RexCall) node;

            // Transform children (if any) before transforming node itself.
            final ArrayList<RexNode> operands = new ArrayList<>();
            for ( RexNode operand : call.operands ) {
                operands.add( transformNullSemantics( operand ) );
            }

            if ( null != directlyUnderIs ) {
                isParentsCount--;
                directlyUnderIs = null;
                return operands.get( 0 );
            }

            if ( transformableOperators.contains( call.getOperator() ) ) {
                assert 2 == operands.size();

                final RexNode isNotNullOne;
                if ( isTransformable( operands.get( 0 ) ) ) {
                    isNotNullOne =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.IS_NOT_NULL,
                                    operands.get( 0 ) );
                } else {
                    isNotNullOne = null;
                }

                final RexNode isNotNullTwo;
                if ( isTransformable( operands.get( 1 ) ) ) {
                    isNotNullTwo =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.IS_NOT_NULL,
                                    operands.get( 1 ) );
                } else {
                    isNotNullTwo = null;
                }

                RexNode intoFinalAnd = null;
                if ( (null != isNotNullOne) && (null != isNotNullTwo) ) {
                    intoFinalAnd =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.AND,
                                    isNotNullOne,
                                    isNotNullTwo );
                } else if ( null != isNotNullOne ) {
                    intoFinalAnd = isNotNullOne;
                } else if ( null != isNotNullTwo ) {
                    intoFinalAnd = isNotNullTwo;
                }

                if ( null != intoFinalAnd ) {
                    RexNode andNullAndCheckNode =
                            rexBuilder.makeCall(
                                    SqlStdOperatorTable.AND,
                                    intoFinalAnd,
                                    call.clone( call.getType(), operands ) );
                    return andNullAndCheckNode;
                }

                // if come here no need to do anything
            }

            if ( !operands.equals( call.operands ) ) {
                return call.clone( call.getType(), operands );
            }
        }

        return node;
    }
}

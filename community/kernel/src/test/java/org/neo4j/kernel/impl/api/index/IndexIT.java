/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.index;

import java.util.Set;

import org.junit.Test;

import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.kernel.api.SchemaStatement;
import org.neo4j.kernel.api.Transactor;
import org.neo4j.kernel.api.exceptions.schema.SchemaKernelException;
import org.neo4j.kernel.impl.api.constraints.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.integrationtest.KernelIntegrationTest;
import org.neo4j.kernel.impl.transaction.TxManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.neo4j.helpers.collection.IteratorUtil.asSet;
import static org.neo4j.helpers.collection.IteratorUtil.emptySetOf;

public class IndexIT extends KernelIntegrationTest
{
    long labelId = 5, propertyKey = 8;

    @Test
    public void addIndexRuleInATransaction() throws Exception
    {
        // GIVEN
        IndexDescriptor expectedRule;
        {
            SchemaStatement statement = schemaStatementInNewTransaction();

            // WHEN
            expectedRule = statement.indexCreate( labelId, propertyKey );
            commit();
        }

        // THEN
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            assertEquals( asSet( expectedRule ),
                          asSet( statement.indexesGetForLabel( labelId ) ) );
            assertEquals( expectedRule, statement.indexesGetForLabelAndPropertyKey( labelId, propertyKey ) );
            commit();
        }
    }

    @Test
    public void committedAndTransactionalIndexRulesShouldBeMerged() throws Exception
    {
        // GIVEN
        IndexDescriptor existingRule;
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            existingRule = statement.indexCreate( labelId, propertyKey );
            commit();
        }

        // WHEN
        IndexDescriptor addedRule;
        Set<IndexDescriptor> indexRulesInTx;
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            long propertyKey2 = 10;
            addedRule = statement.indexCreate( labelId, propertyKey2 );
            indexRulesInTx = asSet( statement.indexesGetForLabel( labelId ) );
            commit();
        }

        // THEN
        assertEquals( asSet( existingRule, addedRule ), indexRulesInTx );
    }

    @Test
    public void rollBackIndexRuleShouldNotBeCommitted() throws Exception
    {
        // GIVEN
        {
            SchemaStatement statement = schemaStatementInNewTransaction();

            // WHEN
            statement.indexCreate( labelId, propertyKey );
            // don't mark as success
            rollback();
        }

        // THEN
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            assertEquals( emptySetOf( IndexDescriptor.class ), asSet( statement.indexesGetForLabel( labelId ) ) );
            commit();
        }
    }

    @Test
    public void shouldRemoveAConstraintIndexWithoutOwnerInRecovery() throws Exception
    {
        // given
        Transactor transactor = new Transactor( db.getDependencyResolver().resolveDependency( TxManager.class ) );
        transactor.execute( ConstraintIndexCreator.createConstraintIndex( labelId, propertyKey ) );

        // when
        restartDb();

        // then
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            assertEquals( emptySetOf( IndexDescriptor.class ), asSet( statement.indexesGetForLabel( labelId ) ) );
            commit();
        }
    }

    @Test
    public void shouldDisallowDroppingIndexThatDoesNotExist() throws Exception
    {
        // given
        IndexDescriptor index;
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            index = statement.indexCreate( labelId, propertyKey );
            commit();
        }
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            statement.indexDrop( index );
            commit();
        }

        // when
        try
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            statement.indexDrop( index );
            commit();
        }
        // then
        catch ( SchemaKernelException e )
        {
            assertEquals( "Unable to drop index on :label[5](property[8]): No such INDEX ON :label[5](property[8]).",
                    e.getMessage() );
        }
    }

    @Test
    public void shouldFailToCreateIndexWhereAConstraintAlreadyExists() throws Exception
    {
        // given
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            statement.uniquenessConstraintCreate( labelId, propertyKey );
            commit();
        }

        // when
        try
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            statement.indexCreate( labelId, propertyKey );
            commit();

            fail( "expected exception" );
        }
        // then
        catch ( SchemaKernelException e )
        {
            assertEquals( "Unable to add index :label[5](property[8]) : " +
                    "Already constrained CONSTRAINT ON ( n:label[5] ) " +
                    "ASSERT n.property[8] IS UNIQUE.", e.getMessage() );
        }
    }

    @Test
    public void shouldListConstraintIndexesInTheBeansAPI() throws Exception
    {
        // given
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            statement.uniquenessConstraintCreate( statement.labelGetOrCreateForName( "Label1" ),
                                         statement.propertyKeyGetOrCreateForName( "property1" ) );
            commit();
        }

        // when
        {
            schemaStatementInNewTransaction();
            Set<IndexDefinition> indexes = asSet( db.schema().getIndexes() );

            // then
            assertEquals( 1, indexes.size() );
            IndexDefinition index = indexes.iterator().next();
            assertEquals( "Label1", index.getLabel().name() );
            assertEquals( asSet( "property1" ), asSet( index.getPropertyKeys() ) );
            assertTrue( "index should be a constraint index", index.isConstraintIndex() );

            // when
            try
            {
                index.drop();

                fail( "expected exception" );
            }
            // then
            catch ( IllegalStateException e )
            {
                assertEquals( "Constraint indexes cannot be dropped directly, " +
                        "instead drop the owning uniqueness constraint.", e.getMessage() );
            }
            commit();
        }
    }

    @Test
    public void shouldNotListConstraintIndexesAmongIndexes() throws Exception
    {
        // given
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            statement.uniquenessConstraintCreate( labelId, propertyKey );
            commit();
        }

        // then/when
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            assertFalse( statement.indexesGetAll().hasNext() );
            assertFalse( statement.indexesGetForLabel( labelId ).hasNext() );
        }
    }

    @Test
    public void shouldNotListIndexesAmongConstraintIndexes() throws Exception
    {
        // given
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            statement.indexCreate( labelId, propertyKey );
            commit();
        }

        // then/when
        {
            SchemaStatement statement = schemaStatementInNewTransaction();
            assertFalse( statement.uniqueIndexesGetAll().hasNext() );
            assertFalse( statement.uniqueIndexesGetForLabel( labelId ).hasNext() );
        }
    }
}

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

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import org.neo4j.helpers.Predicate;
import org.neo4j.helpers.collection.Visitor;
import org.neo4j.kernel.api.exceptions.index.IndexPopulationFailedKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexProxyAlreadyClosedKernelException;
import org.neo4j.kernel.api.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.impl.api.UpdateableSchemaState;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.logging.Logging;

import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static org.neo4j.helpers.FutureAdapter.latchGuardedValue;
import static org.neo4j.helpers.ValueGetter.NO_VALUE;
import static org.neo4j.helpers.collection.Iterables.filter;
import static org.neo4j.kernel.impl.api.index.IndexPopulationFailure.failure;

/**
 * Represents one job of initially populating an index over existing data in the database.
 * Scans the store directly.
 * 
 * @author Mattias Persson
 */
public class IndexPopulationJob implements Runnable
{
    private final IndexStoreView storeView;
    private final String indexUserDescription;

    // NOTE: unbounded queue expected here
    private final Queue<NodePropertyUpdate> queue = new ConcurrentLinkedQueue<>();

    private final IndexDescriptor descriptor;
    private final IndexPopulator populator;
    private final FlippableIndexProxy flipper;
    private final UpdateableSchemaState updateableSchemaState;
    private final StringLogger log;
    private final CountDownLatch doneSignal = new CountDownLatch( 1 );

    private volatile StoreScan<IndexPopulationFailedKernelException> storeScan;
    private volatile boolean cancelled;
    private final SchemaIndexProvider.Descriptor providerDescriptor;

    public IndexPopulationJob( IndexDescriptor descriptor, SchemaIndexProvider.Descriptor providerDescriptor,
                               IndexPopulator populator, FlippableIndexProxy flipper,
                               IndexStoreView storeView, String indexUserDescription,
                               UpdateableSchemaState updateableSchemaState, Logging logging )
    {
        this.descriptor = descriptor;
        this.providerDescriptor = providerDescriptor;
        this.populator = populator;
        this.flipper = flipper;
        this.storeView = storeView;
        this.updateableSchemaState = updateableSchemaState;
        this.indexUserDescription = indexUserDescription;
        this.log = logging.getMessagesLog( getClass() );
    }
    
    @Override
    public void run()
    {
        String oldThreadName = currentThread().getName();
        currentThread().setName( format( "Index populator on %s [runs on: %s]", indexUserDescription, oldThreadName ) );
        boolean success = false;
        Throwable failureCause = null;
        try
        {
            try
            {
                log.info( format("Index population started: [%s]", indexUserDescription) );
                log.flush();
                populator.create();

                indexAllNodes();
                if ( cancelled )
                    // We remain in POPULATING state
                    return;

                Callable<Void> duringFlip = new Callable<Void>()
                {
                    @Override
                    public Void call() throws Exception
                    {
                        populateFromQueueIfAvailable( Long.MAX_VALUE );
                        populator.close( true );
                        updateableSchemaState.clear();
                        return null;
                    }
                };

                flipper.flip( duringFlip, new FailedIndexProxyFactory()
                {
                    @Override
                    public IndexProxy create( Throwable failure )
                    {
                        return new FailedIndexProxy( descriptor, providerDescriptor, populator, failure( failure ) );
                    }
                } );
                success = true;
                log.info( format("Index population completed. Index is now online: [%s]", indexUserDescription) );
                log.flush();
            }
            catch ( Throwable t )
            {
                // These can happen spuriously during shutdown and shouldn't be printed to standard error
                if ( !(t instanceof IndexProxyAlreadyClosedKernelException ) )
                {
                    t.printStackTrace(System.err);
                }

                // Ensure we only log population failure if its cause is not an index entry conflict
                if ( t instanceof IndexPopulationFailedKernelException )
                {
                    Throwable cause = t.getCause();
                    if ( cause instanceof IndexEntryConflictException )
                    {
                        t = cause;
                    }
                }

                // Index conflicts are expected (for unique indexes) so we don't need to log them.
                if ( !(t instanceof IndexEntryConflictException) /*TODO: && this is a unique index...*/ )
                {
                    log.error( format("Failed to populate index: [%s]", indexUserDescription), t );
                    log.flush();
                }


                // Set failure cause to be stored persistently
                failureCause = t;

                // The flipper will have already flipped to a failed index context here, but
                // it will not include the cause of failure, so we do another flip to a failed
                // context that does.

                // The reason for having the flipper transition to the failed index context in the first
                // place is that we would otherwise introduce a race condition where updates could come
                // in to the old context, if something failed in the job we send to the flipper.
                flipper.flipTo( new FailedIndexProxy( descriptor, providerDescriptor, populator, failure( t ) ) );
            }
            finally
            {
                try
                {
                    if ( !success )
                    {
                        if ( failureCause != null )
                        {
                            populator.markAsFailed( failure( failureCause ).asString() );
                        }

                        populator.close( false );
                    }
                }
                catch ( Throwable e )
                {
                    log.error( format("Unable to close failed populator for index: [%s]", indexUserDescription), e );
                    log.flush();
                }
            }
        }
        finally
        {
            doneSignal.countDown();
            currentThread().setName( oldThreadName );
        }
    }

    private void indexAllNodes() throws IndexPopulationFailedKernelException
    {
        storeScan = storeView.visitNodesWithPropertyAndLabel( descriptor, new Visitor<NodePropertyUpdate,
                IndexPopulationFailedKernelException>()
        {
            @Override
            public boolean visit( NodePropertyUpdate update ) throws IndexPopulationFailedKernelException
            {
                try
                {
                    populator.add( update.getNodeId(), update.getValueAfter() );
                    populateFromQueueIfAvailable( update.getNodeId() );
                }
                catch ( Exception conflict )
                {
                    throw new IndexPopulationFailedKernelException( descriptor, conflict );
                }
                return false;
            }
        });
        storeScan.run();
    }

    private void populateFromQueueIfAvailable( final long highestIndexedNodeId )
            throws IndexEntryConflictException, IOException
    {
        if ( !queue.isEmpty() )
        {
            Predicate<NodePropertyUpdate> hasBeenIndexed = new Predicate<NodePropertyUpdate>()
            {
                @Override
                public boolean accept( NodePropertyUpdate item )
                {
                    return item.getNodeId() <= highestIndexedNodeId;
                }
            };

            populator.update( filter( hasBeenIndexed, queue ) );
        }
    }

    public Future<Void> cancel()
    {
        // Stop the population
        if ( storeScan != null )
        {
            cancelled = true;
            storeScan.stop();
        }
        
        return latchGuardedValue( NO_VALUE, doneSignal );
    }

    /**
     * A transaction happened that produced the given updates. Let this job incorporate its data
     * into, feeding it to the {@link IndexPopulator}.
     */
    public void update( Iterable<NodePropertyUpdate> updates )
    {
        for ( NodePropertyUpdate update : updates )
            queue.add( update );
    }
    
    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "[populator:" + populator + ", descriptor:" + descriptor + "]";
    }

    public void awaitCompletion() throws InterruptedException
    {
        doneSignal.await();
    }
}

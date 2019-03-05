/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.graphdb.facade;

import java.io.File;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.bolt.BoltServer;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.factory.module.DatabaseModule;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.graphdb.factory.module.edition.AbstractEditionModule;
import org.neo4j.graphdb.spatial.Geometry;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.internal.collector.DataCollector;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.impl.fulltext.FulltextAdapter;
import org.neo4j.kernel.api.procedure.Context;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.api.security.provider.SecurityProvider;
import org.neo4j.kernel.availability.StartupWaiter;
import org.neo4j.kernel.impl.api.dbms.NonTransactionalDbmsOperations;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.pagecache.PublishPageCacheTracerMetricsAfterStart;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.internal.Version;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.Log;
import org.neo4j.logging.Logger;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.ProcedureTransaction;
import org.neo4j.procedure.builtin.SpecialBuiltInProcedures;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;
import org.neo4j.procedure.impl.ProcedureConfig;
import org.neo4j.procedure.impl.ProcedureTransactionProvider;
import org.neo4j.procedure.impl.TerminationGuardProvider;
import org.neo4j.values.storable.PointValue;
import org.neo4j.values.virtual.NodeValue;
import org.neo4j.values.virtual.PathValue;
import org.neo4j.values.virtual.RelationshipValue;

import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTGeometry;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTNode;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTPath;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTPoint;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTRelationship;

/**
 * This is the main factory for creating database instances. It delegates creation to three different modules
 * ({@link GlobalModule}, {@link AbstractEditionModule}, and {@link DatabaseModule}),
 * which create all the specific services needed to run a graph database.
 * <p>
 * To create test versions of databases, override an edition factory (e.g. {@link org.neo4j.kernel.impl.factory
 * .CommunityFacadeFactory}), and replace modules
 * with custom versions that instantiate alternative services.
 */
public class GraphDatabaseFacadeFactory
{

    protected final DatabaseInfo databaseInfo;
    private final Function<GlobalModule,AbstractEditionModule> editionFactory;

    public GraphDatabaseFacadeFactory( DatabaseInfo databaseInfo,
            Function<GlobalModule,AbstractEditionModule> editionFactory )
    {
        this.databaseInfo = databaseInfo;
        this.editionFactory = editionFactory;
    }

    /**
     * Instantiate a graph database given configuration and dependencies.
     *
     * @param storeDir the directory where the Neo4j data store is located
     * @param config configuration
     * @param dependencies the dependencies required to construct the {@link GraphDatabaseFacade}
     * @return the newly constructed {@link GraphDatabaseFacade}
     */
    public GraphDatabaseFacade newFacade( File storeDir, Config config, final ExternalDependencies dependencies )
    {
        return initFacade( storeDir, config, dependencies, new GraphDatabaseFacade() );
    }

    /**
     * Instantiate a graph database given configuration, dependencies, and a custom implementation of {@link org
     * .neo4j.kernel.impl.factory.GraphDatabaseFacade}.
     *
     * @param storeDir the directory where the Neo4j data store is located
     * @param params configuration parameters
     * @param dependencies the dependencies required to construct the {@link GraphDatabaseFacade}
     * @param graphDatabaseFacade the already created facade which needs initialisation
     * @return the initialised {@link GraphDatabaseFacade}
     */
    public GraphDatabaseFacade initFacade( File storeDir, Map<String,String> params, final ExternalDependencies dependencies,
            final GraphDatabaseFacade graphDatabaseFacade )
    {
        return initFacade( storeDir, Config.defaults( params ), dependencies, graphDatabaseFacade );
    }

    /**
     * Instantiate a graph database given configuration, dependencies, and a custom implementation of {@link org
     * .neo4j.kernel.impl.factory.GraphDatabaseFacade}.
     *
     * @param storeDir the directory where the Neo4j data store is located
     * @param config configuration
     * @param dependencies the dependencies required to construct the {@link GraphDatabaseFacade}
     * @param graphDatabaseFacade the already created facade which needs initialisation
     * @return the initialised {@link GraphDatabaseFacade}
     */
    public GraphDatabaseFacade initFacade( File storeDir, Config config, final ExternalDependencies dependencies,
            final GraphDatabaseFacade graphDatabaseFacade )
    {
        GlobalModule globalModule = createGlobalModule( storeDir, config, dependencies );
        AbstractEditionModule edition = editionFactory.apply( globalModule );
        Dependencies globalDependencies = globalModule.getGlobalDependencies();
        LifeSupport globalLife = globalModule.getGlobalLife();

        GlobalProcedures globalProcedures = setupProcedures( globalModule, edition );
        globalDependencies.satisfyDependency( new NonTransactionalDbmsOperations( globalProcedures ) );

        LogService logService = globalModule.getLogService();
        Logger logger = logService.getInternalLog( getClass() ).infoLogger();
        DatabaseManager databaseManager = createAndInitializeDatabaseManager( globalModule, edition, graphDatabaseFacade, globalProcedures, logger );

        edition.createSecurityModule( globalModule, globalProcedures );
        SecurityProvider securityProvider = edition.getSecurityProvider();
        globalDependencies.satisfyDependencies( securityProvider.authManager() );
        globalDependencies.satisfyDependencies( securityProvider.userManagerSupplier() );

        globalLife.add( globalModule.getGlobalExtensions() );
        globalLife.add( createBoltServer( globalModule, edition, databaseManager ) );
        globalDependencies.satisfyDependency( edition.globalTransactionCounter() );
        globalLife.add( new StartupWaiter( globalModule.getGlobalAvailabilityGuard(), edition.getTransactionStartTimeout() ) );
        globalDependencies.satisfyDependency( edition.getSchemaWriteGuard() );
        globalLife.add( new PublishPageCacheTracerMetricsAfterStart( globalModule.getTracers().getPageCursorTracerSupplier() ) );

        RuntimeException error = null;
        GraphDatabaseFacade databaseFacade = null;
        try
        {
            edition.createDatabases( databaseManager, config );
            globalLife.start();
            String defaultDatabase = config.get( GraphDatabaseSettings.default_database );
            databaseFacade = databaseManager.getDatabaseContext( defaultDatabase ).orElseThrow( () -> new IllegalStateException(
                    String.format( "Database %s not found. Please check the logs for startup errors.", defaultDatabase ) ) ).getDatabaseFacade();

        }
        catch ( final Throwable throwable )
        {
            error = new RuntimeException( "Error starting " + getClass().getName() + ", " +
                    globalModule.getStoreLayout().storeDirectory(), throwable );
        }
        finally
        {
            if ( error != null )
            {
                try
                {
                    graphDatabaseFacade.shutdown();
                }
                catch ( Throwable shutdownError )
                {
                    error.addSuppressed( shutdownError );
                }
            }
        }

        if ( error != null )
        {
            logger.log( "Failed to start database", error );
            throw error;
        }

        return databaseFacade;
    }

    /**
     * Create the platform module. Override to replace with custom module.
     */
    protected GlobalModule createGlobalModule( File storeDir, Config config, final ExternalDependencies dependencies )
    {
        return new GlobalModule( storeDir, config, databaseInfo, dependencies );
    }

    private static GlobalProcedures setupProcedures( GlobalModule platform, AbstractEditionModule editionModule )
    {
        Config globalConfig = platform.getGlobalConfig();
        File proceduresDirectory = globalConfig.get( GraphDatabaseSettings.plugin_dir );
        LogService logService = platform.getLogService();
        Log internalLog = logService.getInternalLog( GlobalProcedures.class );
        Log proceduresLog = logService.getUserLog( GlobalProcedures.class );

        ProcedureConfig procedureConfig = new ProcedureConfig( globalConfig );
        SpecialBuiltInProcedures builtInProcedures = new SpecialBuiltInProcedures( Version.getNeo4jVersion(), platform.getDatabaseInfo().edition.toString() );
        GlobalProceduresRegistry globalProcedures = new GlobalProceduresRegistry( builtInProcedures, proceduresDirectory, internalLog, procedureConfig );

        globalProcedures.registerType( Node.class, NTNode );
        globalProcedures.registerType( NodeValue.class, NTNode );
        globalProcedures.registerType( Relationship.class, NTRelationship );
        globalProcedures.registerType( RelationshipValue.class, NTRelationship );
        globalProcedures.registerType( Path.class, NTPath );
        globalProcedures.registerType( PathValue.class, NTPath );
        globalProcedures.registerType( Geometry.class, NTGeometry );
        globalProcedures.registerType( Point.class, NTPoint );
        globalProcedures.registerType( PointValue.class, NTPoint );

        // Below components are not public API, but are made available for internal
        // procedures to call, and to provide temporary workarounds for the following
        // patterns:
        //  - Batch-transaction imports (GDAPI, needs to be real and passed to background processing threads)
        //  - Group-transaction writes (same pattern as above, but rather than splitting large transactions,
        //                              combine lots of small ones)
        //  - Bleeding-edge performance (KernelTransaction, to bypass overhead of working with Core API)
        globalProcedures.registerComponent( DependencyResolver.class, Context::dependencyResolver, false );
        globalProcedures.registerComponent( KernelTransaction.class, Context::kernelTransaction, false );
        globalProcedures.registerComponent( GraphDatabaseAPI.class, Context::graphDatabaseAPI, false );

        // Register injected public API components
        globalProcedures.registerComponent( Log.class, ctx -> proceduresLog, true );
        globalProcedures.registerComponent( ProcedureTransaction.class, new ProcedureTransactionProvider(), true );
        globalProcedures.registerComponent( org.neo4j.procedure.TerminationGuard.class, new TerminationGuardProvider(), true );
        globalProcedures.registerComponent( SecurityContext.class, Context::securityContext, true );
        globalProcedures.registerComponent( FulltextAdapter.class, ctx -> ctx.dependencyResolver().resolveDependency( FulltextAdapter.class ), true );
        globalProcedures.registerComponent( DataCollector.class, ctx -> ctx.dependencyResolver().resolveDependency( DataCollector.class ), false );

        // Edition procedures
        try
        {
            editionModule.registerProcedures( globalProcedures, procedureConfig );
        }
        catch ( KernelException e )
        {
            internalLog.error( "Failed to register built-in edition procedures at start up: " + e.getMessage() );
        }

        platform.getGlobalLife().add( globalProcedures );
        platform.getGlobalDependencies().satisfyDependency( globalProcedures );
        return globalProcedures;
    }

    private static BoltServer createBoltServer( GlobalModule platform, AbstractEditionModule edition, DatabaseManager databaseManager )
    {
        return new BoltServer( databaseManager, platform.getJobScheduler(), platform.getConnectorPortRegister(), edition.getConnectionTracker(),
                platform.getUsageData(), platform.getGlobalConfig(), platform.getGlobalClock(), platform.getGlobalMonitors(), platform.getLogService(),
                platform.getGlobalDependencies() );
    }

    private static DatabaseManager createAndInitializeDatabaseManager( GlobalModule platform, AbstractEditionModule edition,
            GraphDatabaseFacade facade, GlobalProcedures globalProcedures, Logger logger )
    {
        DatabaseManager databaseManager = edition.createDatabaseManager( facade, platform, edition, globalProcedures, logger );
        if ( !edition.handlesDatabaseManagerLifecycle() )
        {
            // only add database manager to the lifecycle when edition doesn't manage it already
            platform.getGlobalLife().add( databaseManager );
        }
        platform.getGlobalDependencies().satisfyDependency( databaseManager );
        return databaseManager;
    }
}
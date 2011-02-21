/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import org.apache.log4j.Logger;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

import org.voltdb.catalog.*;
import org.voltdb.client.ClientResponse;
import org.voltdb.exceptions.MispredictionException;
import org.voltdb.exceptions.SerializableException;
import org.voltdb.exceptions.EEException;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.types.TimestampType;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hashing.AbstractHasher;
import edu.brown.markov.EstimationThresholds;
import edu.brown.markov.TransactionEstimator;
import edu.brown.utils.EventObservable;
import edu.brown.utils.EventObserver;
import edu.brown.utils.LoggerUtil;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.Poolable;
import edu.brown.utils.ProfileMeasurement;
import edu.brown.utils.StringUtil;
import edu.brown.utils.LoggerUtil.LoggerBoolean;
import edu.mit.hstore.HStoreSite;
import edu.mit.hstore.dtxn.LocalTransactionState;
import edu.mit.hstore.dtxn.TransactionState;

import java.io.StringWriter;
import java.io.PrintWriter;

/**
 * Wraps the stored procedure object created by the user
 * with metadata available at runtime. This is used to call
 * the procedure.
 *
 * VoltProcedure is extended by all running stored procedures.
 * Consider this when specifying access privileges.
 *
 */
public abstract class VoltProcedure implements Poolable {
    private static final Logger LOG = Logger.getLogger(VoltProcedure.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    // Used to get around the "abstract" for StmtProcedures.
    // Path of least resistance?
    static class StmtProcedure extends VoltProcedure {}

    // This must match MAX_BATCH_COUNT in src/ee/execution/VoltDBEngine.h
    final static int MAX_BATCH_SIZE = 1000;

    final static Double DOUBLE_NULL = new Double(-1.7976931348623157E+308);
    public static final String ANON_STMT_NAME = "sql";

    protected HsqlBackend hsql;

    // package scoped members used by VoltSystemProcedure
    Cluster m_cluster;
    //SiteProcedureConnection m_site;
    protected ExecutionSite m_site;
     
    private boolean m_initialized;

    // private members reserved exclusively to VoltProcedure
    private Method procMethod;
    private Class<?>[] paramTypes;
    private boolean paramTypeIsPrimitive[];
    private boolean paramTypeIsArray[];
    private Class<?> paramTypeComponentType[];
    private int paramTypesLength;
    private boolean isNative = true;
    protected Object procParams[];
    //final HashMap<Object, Statement> stmts = new HashMap<Object, Statement>( 16, (float).1);

    // cached fake SQLStmt array for single statement non-java procs
    SQLStmt[] m_cachedSingleStmt = { null };
    
    // Workload Trace Handles
    private Object m_workloadXactHandle = null;
    private Integer m_workloadBatchId = null;
    private Set<Object> m_workloadQueryHandles;

    // data copied from EE proc wrapper
    private Long txn_id;
    private Long client_handle;
    private boolean predict_singlepartition;
    private TransactionState m_currentTxnState;  // assigned in call()
    private final SQLStmt batchQueryStmts[] = new SQLStmt[1000];
    private int batchQueryStmtIndex = 0;
    private final Object[] batchQueryArgs[] = new Object[1000][];
    private int batchQueryArgsIndex = 0;
     
    // Used to figure out what partitions a query needs to go to
    protected Catalog catalog;
    protected Procedure catProc;
    protected String procedure_name;
    protected AbstractHasher hasher;
    protected PartitionEstimator p_estimator;
    protected EstimationThresholds thresholds;
    
    protected Integer local_partition;
    
    // Callback for when the VoltProcedure finishes and we need to send a ClientResponse somewhere
    private final EventObservable observable = new EventObservable();

    /**
     * Status code that can be set by stored procedure upon invocation that will be returned with the response.
     */
    private byte m_statusCode = Byte.MIN_VALUE;
    private String m_statusString = null;
    
    // data from hsql wrapper
    private final ArrayList<VoltTable> queryResults = new ArrayList<VoltTable>();

    // cached txnid-seeded RNG so all calls to getSeededRandomNumberGenerator() for
    // a given call don't re-seed and generate the same number over and over
    private Random m_cachedRNG = null;
    
    /**
     * Execution runnable for handling transactions
     */
    // protected final Semaphore executor_lock = new Semaphore(1);
    protected final Object executor_lock = new Object();
    protected class VoltProcedureExecutor implements Runnable {
        
        private final TransactionState txnState;
        private final Object paramList[];
        
        public VoltProcedureExecutor(TransactionState txnState, Object paramList[]) {
            this.txnState = txnState;
            this.paramList = paramList;
        }
        
        @Override
        public void run() {
            synchronized (executor_lock) {
                final boolean t = trace.get();
                final boolean d = debug.get();
                
                if (d) Thread.currentThread().setName(VoltProcedure.this.m_site.getThreadName() + "-" + VoltProcedure.this.procedure_name);
    
                long current_txn_id = txnState.getTransactionId();
                long client_handle = txnState.getClientHandle();
                assert(VoltProcedure.this.txn_id == null) : "Old Transaction Id: " + VoltProcedure.this.txn_id + " -> New Transaction Id: " + current_txn_id;
                VoltProcedure.this.m_currentTxnState = (LocalTransactionState)txnState;
                VoltProcedure.this.txn_id = current_txn_id;
                VoltProcedure.this.client_handle = client_handle;
                VoltProcedure.this.procParams = paramList;
                VoltProcedure.this.predict_singlepartition = ((LocalTransactionState)VoltProcedure.this.m_currentTxnState).isPredictSinglePartition();
                
                if (d) LOG.debug("Starting execution of txn #" + current_txn_id);
                
                try {
                    // Execute the txn (this blocks until we return)
                    if (t) LOG.trace("Invoking VoltProcedure.call for txn #" + current_txn_id);
                    ClientResponse response = VoltProcedure.this.call();
                    assert(response != null);
    
                    // Send the response back immediately!
                    if (t) LOG.trace("Sending ClientResponse back for txn #" + current_txn_id + " [status=" + response.getStatusName() + "]");
                    VoltProcedure.this.m_site.sendClientResponse((ClientResponseImpl)response);
                    
                    // Notify anybody who cares that we're finished (used in testing)
                    if (t) LOG.trace("Notifying observers that txn #" + current_txn_id + " is finished");
                    VoltProcedure.this.observable.notifyObservers(response);
                    
                } catch (AssertionError ex) {
                    LOG.fatal("Unexpected error while executing txn #" + current_txn_id, ex);
                    VoltProcedure.this.m_site.crash(ex.getCause());
                } catch (Exception ex) {
                    LOG.fatal("Unexpected error while executing txn #" + current_txn_id, ex);
                    VoltProcedure.this.m_site.crash(ex);
                } finally {
                    assert(VoltProcedure.this.txn_id == current_txn_id) : VoltProcedure.this.txn_id + " != " + current_txn_id;
                    
                    // Clear out our private data
                    if (t) LOG.trace("Releasing lock for txn #" + current_txn_id);
                    VoltProcedure.this.m_currentTxnState = null;
                    VoltProcedure.this.txn_id = null;
                }
            }
        }
    };

    /**
     * End users should not instantiate VoltProcedure instances.
     * Constructor does nothing. All actual initialization is done in the
     * {@link VoltProcedure init} method.
     */
    public VoltProcedure() {}

    /**
     * Allow VoltProcedures access to their transaction id.
     * @return transaction id
     */
    public Long getTransactionId() {
        return this.txn_id; // m_currentTxnState.txnId;
    }

    /**
     * Allow VoltProcedures access to their transaction id.
     * @return transaction id
     */
    public void setTransactionId(long txn_id) {
        this.txn_id = txn_id;
    }

    /**
     * Allow sysprocs to update m_currentTxnState manually. User procedures are
     * passed this state in call(); sysprocs have other entry points on
     * non-coordinator sites.
     */
    public void setTransactionState(TransactionState txnState) {
        m_currentTxnState = txnState;
    }

    public TransactionState getTransactionState() {
        return m_currentTxnState;
    }
    
    /**
     * Main initialization method
     * @param site
     * @param catProc
     * @param eeType
     * @param hsql
     * @param cluster
     * @param p_estimator
     * @param local_partition
     */
    public void init(ExecutionSite site, Procedure catProc, BackendTarget eeType, HsqlBackend hsql, Cluster cluster, PartitionEstimator p_estimator, Integer local_partition) {
        if (m_initialized) {
            throw new IllegalStateException("VoltProcedure has already been initialized");
        } else {
            m_initialized = true;
        }
        assert(site != null);

        this.m_site = site;
        this.catProc = catProc;
        this.procedure_name = this.catProc.getName();
        this.catalog = this.catProc.getCatalog();
        this.isNative = (eeType != BackendTarget.HSQLDB_BACKEND);
        this.hsql = hsql;
        this.m_cluster = cluster;

        this.local_partition = this.m_site.getPartitionId();
        
        this.p_estimator = p_estimator;
        HStoreSite hstore_site = this.m_site.getHStoreSite();
        if (hstore_site != null) this.thresholds = hstore_site.getThresholds();
        this.hasher = this.p_estimator.getHasher();
        // LOG.debug("Initialized VoltProcedure for " + catProc + " [local=" + local_partition + ",total=" + this.hasher.getNumPartitions() + "]");
        
        if (catProc.getHasjava()) {
            int tempParamTypesLength = 0;
            Method tempProcMethod = null;
            Method[] methods = getClass().getMethods();
            Class<?> tempParamTypes[] = null;
            boolean tempParamTypeIsPrimitive[] = null;
            boolean tempParamTypeIsArray[] = null;
            Class<?> tempParamTypeComponentType[] = null;
            for (final Method m : methods) {
                String name = m.getName();
                if (name.equals("run")) {
                    //inspect(m);
                    tempProcMethod = m;
                    tempParamTypes = tempProcMethod.getParameterTypes();
                    tempParamTypesLength = tempParamTypes.length;
                    tempParamTypeIsPrimitive = new boolean[tempParamTypesLength];
                    tempParamTypeIsArray = new boolean[tempParamTypesLength];
                    tempParamTypeComponentType = new Class<?>[tempParamTypesLength];
                    for (int ii = 0; ii < tempParamTypesLength; ii++) {
                        tempParamTypeIsPrimitive[ii] = tempParamTypes[ii].isPrimitive();
                        tempParamTypeIsArray[ii] = tempParamTypes[ii].isArray();
                        tempParamTypeComponentType[ii] = tempParamTypes[ii].getComponentType();
                    }
                }
            }
            paramTypesLength = tempParamTypesLength;
            procMethod = tempProcMethod;
            paramTypes = tempParamTypes;
            paramTypeIsPrimitive = tempParamTypeIsPrimitive;
            paramTypeIsArray = tempParamTypeIsArray;
            paramTypeComponentType = tempParamTypeComponentType;

            if (procMethod == null) {
                LOG.fatal("No good method found in: " + getClass().getName());
            }

            Field[] fields = getClass().getFields();
            for (final Field f : fields) {
                if (f.getType() == SQLStmt.class) {
                    String name = f.getName();
                    Statement s = catProc.getStatements().get(name);
                    if (s != null) {
                        try {
                            /*
                             * Cache all the information we need about the statements in this stored
                             * procedure locally instead of pulling them from the catalog on
                             * a regular basis.
                             */
                            SQLStmt stmt = (SQLStmt) f.get(this);

                            stmt.catStmt = s;

                            stmt.numFragGUIDs = s.getFragments().size();
                            PlanFragment fragments[] = s.getFragments().values();
                            stmt.fragGUIDs = new long[stmt.numFragGUIDs];
                            for (int ii = 0; ii < stmt.numFragGUIDs; ii++) {
                                stmt.fragGUIDs[ii] = Long.parseLong(fragments[ii].getName());
                            }
    
                            stmt.numStatementParamJavaTypes = s.getParameters().size();
                            stmt.statementParamJavaTypes = new byte[stmt.numStatementParamJavaTypes];
                            StmtParameter parameters[] = s.getParameters().values();
                            for (int ii = 0; ii < stmt.numStatementParamJavaTypes; ii++) {
                                stmt.statementParamJavaTypes[ii] = (byte)parameters[ii].getJavatype();
                            }
                            stmt.computeHashCode();
                        //stmts.put((Object) (f.get(null)), s);
                        } catch (IllegalArgumentException e) {
                            e.printStackTrace();
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                    //LOG.debug("Found statement " + name);
                    }
                }
            }
        }
        // has no java
        else {
            Statement catStmt = catProc.getStatements().get(ANON_STMT_NAME);
            SQLStmt stmt = new SQLStmt(catStmt.getSqltext());
            stmt.catStmt = catStmt;
            initSQLStmt(stmt);
            m_cachedSingleStmt[0] = stmt;

            procMethod = null;

            paramTypesLength = catProc.getParameters().size();

            paramTypes = new Class<?>[paramTypesLength];
            paramTypeIsPrimitive = new boolean[paramTypesLength];
            paramTypeIsArray = new boolean[paramTypesLength];
            paramTypeComponentType = new Class<?>[paramTypesLength];
            for (ProcParameter param : catProc.getParameters()) {
                VoltType type = VoltType.get((byte) param.getType());
                if (type == VoltType.INTEGER) type = VoltType.BIGINT;
                if (type == VoltType.SMALLINT) type = VoltType.BIGINT;
                if (type == VoltType.TINYINT) type = VoltType.BIGINT;
                paramTypes[param.getIndex()] = type.classFromType();
                paramTypeIsPrimitive[param.getIndex()] = true;
                paramTypeIsArray[param.getIndex()] = param.getIsarray();
                assert(paramTypeIsArray[param.getIndex()] == false);
                paramTypeComponentType[param.getIndex()] = null;
            }
        }
    }
    
    @Override
    public void finish() {
        // Nothing...
    }
        
    final void initSQLStmt(SQLStmt stmt) {
        stmt.numFragGUIDs = stmt.catStmt.getFragments().size();
        PlanFragment fragments[] = new PlanFragment[stmt.numFragGUIDs];
        stmt.fragGUIDs = new long[stmt.numFragGUIDs];
        int i = 0;
        for (PlanFragment frag : stmt.catStmt.getFragments()) {
            fragments[i] = frag;
            stmt.fragGUIDs[i] = CatalogUtil.getUniqueIdForFragment(frag);
            i++;
        }
    
        stmt.numStatementParamJavaTypes = stmt.catStmt.getParameters().size();
        //StmtParameter parameters[] = new StmtParameter[stmt.numStatementParamJavaTypes];
        stmt.statementParamJavaTypes = new byte[stmt.numStatementParamJavaTypes];
        for (StmtParameter param : stmt.catStmt.getParameters()) {
            //parameters[i] = param;
            stmt.statementParamJavaTypes[param.getIndex()] = (byte)param.getJavatype();
            i++;
        }
    }
    
    /**
     * Get a Java RNG seeded with the current transaction id. This will ensure that
     * two procedures for the same transaction, but running on different replicas,
     * can generate an identical stream of random numbers. This is required to endure
     * procedures have deterministic behavior.
     *
     * @return A deterministically-seeded java.util.Random instance.
     */
    public Random getSeededRandomNumberGenerator() {
        // this value is memoized here and reset at the beginning of call(...).
        if (m_cachedRNG == null) {
            m_cachedRNG = new Random(this.getTransactionId());
        }
        return m_cachedRNG;
    }
    
    /**
     * End users should not call this method.
     * Used by the VoltDB runtime to initialize stored procedures for execution.
     */
//    public void init(ExecutionEngine engine, Procedure catProc, Cluster cluster, PartitionEstimator p_estimator, int local_partition) {
//        assert this.engine == null;
//        assert engine != null;
//        this.engine = engine;
//        init(null, catProc, BackendTarget.NATIVE_EE_JNI, null, cluster, p_estimator, local_partition);
//    }
    
    public boolean isInitialized() {
        return (this.m_initialized);
    }
    
    public String getProcedureName() {
        return (this.procedure_name);
    }
    
    /**
     * Return a hash code that uniquely identifies this list of SQLStmts
     * Derived from AbstractList.hashCode()
     * @param batchStmts
     * @param numBatchStmts
     * @return
     */
    public static int getBatchHashCode(SQLStmt[] batchStmts, int numBatchStmts) {
        int hashCode = 1;
        for (int i = 0; i < numBatchStmts; i++) {
            hashCode = 31*hashCode + (batchStmts[i] == null ? 0 : batchStmts[i].hashCode());
        } // FOR
        return hashCode;
    }

    public void registerCallback(EventObserver observer) {
        this.observable.addObserver(observer);
    }

    public void unregisterCallback(EventObserver observer) {
        this.observable.deleteObserver(observer);
    }
    
    /**
     * Convenience method to call and block a VoltProcedure
     * @param paramList
     * @return
     */
    public final ClientResponse callAndBlock(TransactionState txnState, Object... paramList) {
        final LinkedBlockingDeque<ClientResponse> lock = new LinkedBlockingDeque<ClientResponse>(1);
        EventObserver observer = new EventObserver() {
            @Override
            public void update(Observable o, Object arg) {
                assert(arg != null);
                lock.offer((ClientResponse)arg);
            }
        };
        this.registerCallback(observer);
        this.call(txnState, paramList);

        ClientResponse response = null;
        try {
            response = lock.take();
        } catch (Exception ex) {
            LOG.error("Failed to retrieve response from VoltProcedure blocking callback", ex);
        }
        this.unregisterCallback(observer);
        assert(response != null);
        return (response);
    }

    /**
     * Non-Blocking call.
     * @param paramList
     * @return
     */
    public final void call(TransactionState txnState, Object... paramList) {
//        LOG.debug("started");
//        if (ProcedureProfiler.profilingLevel != ProcedureProfiler.Level.DISABLED)
//            profiler.startCounter(catProc);
//        statsCollector.beginProcedure();

        // Wait to make sure that the other transaction is finished before we plow through
//        if (trace && this.txn_id != null) LOG.trace("Txn #" + txnState.getTransactionId() + " is going to wait for txn #" + this.txn_id);
        
        if (trace.get()) LOG.trace("Setting up internal state for txn #" + txnState.getTransactionId());
//        assert(this.txn_id == null) : "Conflict with txn #" + this.txn_id; // This should never happen!
        
        // Bombs away!
         this.m_site.thread_pool.execute(new VoltProcedureExecutor(txnState, paramList));
    }

    /**
     * The thing that actually executes the VoltProcedure.run() method 
     * @return
     */
    private final ClientResponse call() {
        // in case someone queues sql but never calls execute, clear the queue here.
        batchQueryStmtIndex = 0;
        batchQueryArgsIndex = 0;

        // Select a local_partition to use if we're on the coordinator, otherwise
        // just use the real partition. We shouldn't have to do this once Evan gets it
        // so that we can have a coordinator on each node
        assert(this.local_partition != null);

        //lastBatchNeedsRollback = false;

        final LocalTransactionState local_ts = (LocalTransactionState)this.m_currentTxnState;
        VoltTable[] results = new VoltTable[0];
        byte status = ClientResponseImpl.SUCCESS;
        SerializableException se = null;
        String extra = "";

        if (this.procParams.length != paramTypesLength) {
            String msg = "PROCEDURE " + catProc.getName() + " EXPECTS " + String.valueOf(paramTypesLength) +
                " PARAMS, BUT RECEIVED " + String.valueOf(this.procParams.length);
            LOG.fatal(msg);
            status = ClientResponseImpl.GRACEFUL_FAILURE;
            extra = msg;
            return new ClientResponseImpl(this.getTransactionId(), status, results, extra, this.client_handle);
        }

        for (int i = 0; i < paramTypesLength; i++) {
            try {
                this.procParams[i] = tryToMakeCompatible( i, this.procParams[i]);
            } catch (Exception e) {
                String msg = "PROCEDURE " + catProc.getName() + " TYPE ERROR FOR PARAMETER " + i +
                        ": " + e.getMessage();
                LOG.fatal(msg);
                e.printStackTrace();
                status = ClientResponseImpl.GRACEFUL_FAILURE;
                extra = msg;
                return new ClientResponseImpl(this.getTransactionId(), status, results, extra, this.client_handle);
            }
        }

        // Workload Trace
        // Create a new transaction record in the trace manager. This will give us back
        // a handle that we need to pass to the trace manager when we want to register a new query
        if ((ProcedureProfiler.profilingLevel == ProcedureProfiler.Level.INTRUSIVE) &&
                (ProcedureProfiler.workloadTrace != null)) {
            m_workloadQueryHandles = new HashSet<Object>();
            m_workloadXactHandle = ProcedureProfiler.workloadTrace.startTransaction(this, catProc, this.procParams);
        }

        if (this.m_site.enable_profiling) local_ts.java_time.startThinkMarker();
        try {
            if (trace.get()) LOG.trace("Invoking txn #" + this.txn_id + " [" +
                                       "procMethod=" + procMethod.getName() + ", " +
                                       "class=" + getClass().getSimpleName() + ", " +
                                       "partition=" + this.local_partition + 
                                       "]");
            try {
                Object rawResult = procMethod.invoke(this, this.procParams);
                results = getResultsFromRawResults(rawResult);
                if (results == null)
                    results = new VoltTable[0];
            } catch (IllegalAccessException e) {
                // If reflection fails, invoke the same error handling that other exceptions do
                throw new InvocationTargetException(e);
            } catch (AssertionError e) {
                LOG.fatal(e);
                System.exit(1);
            }
            if (debug.get()) LOG.debug(this.catProc + " is finished for txn #" + this.txn_id);
            
        // -------------------------------
        // Exceptions that we can process+handle
        // -------------------------------
        } catch (InvocationTargetException itex) {
            Throwable ex = itex.getCause();
            Class<?> ex_class = ex.getClass();
            
            // Pass the exception back to the client if it is serializable
            if (ex instanceof SerializableException) {
                se = (SerializableException)ex;
            } else if (ex instanceof AssertionError) {
                throw (AssertionError)ex;
            }
            
            // -------------------------------
            // VoltAbortException
            // -------------------------------
            if (ex_class.equals(VoltAbortException.class)) {
                //LOG.fatal("PROCEDURE "+ catProc.getName() + " USER ABORTED", ex);
                status = ClientResponseImpl.USER_ABORT;
                extra = "USER ABORT: " + ex.getMessage();
                
                if ((ProcedureProfiler.profilingLevel == ProcedureProfiler.Level.INTRUSIVE) &&
                    (ProcedureProfiler.workloadTrace != null && m_workloadXactHandle != null)) {
                    ProcedureProfiler.workloadTrace.abortTransaction(m_workloadXactHandle);
                }
            // -------------------------------
            // MispredictionException
            // -------------------------------
            } else if (ex_class.equals(MispredictionException.class)) {
                if (debug.get()) LOG.warn("Caught MispredictionException for txn #" + ((MispredictionException)ex).getTransactionId());
                status = ClientResponse.MISPREDICTION;

            // -------------------------------
            // ConstraintFailureException
            // -------------------------------
            } else if (ex_class.equals(org.voltdb.exceptions.ConstraintFailureException.class)) {
                status = ClientResponseImpl.UNEXPECTED_FAILURE;
                extra = "CONSTRAINT FAILURE: " + ex.getMessage();
                
            // -------------------------------
            // Everthing Else
            // -------------------------------
            } else {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                String msg = sw.toString();
                if (msg == null) msg = ex.toString();
                LOG.fatal("PROCEDURE "+ catProc.getName() + " UNEXPECTED ABORT: " + msg + ex);
                status = ClientResponseImpl.UNEXPECTED_FAILURE;
                extra = "UNEXPECTED ABORT: " + msg;
            }
        // -------------------------------
        // Something really bad happened. Just bomb out!
        // -------------------------------
        } catch (AssertionError e) {
            LOG.fatal(e);
            System.exit(1);
        } finally {
            if (this.m_site.enable_profiling) {
                long time = ProfileMeasurement.getTime();
                if (local_ts.java_time.isStarted()) local_ts.java_time.stopThinkMarker(time);
                if (local_ts.coord_time.isStarted()) local_ts.coord_time.stopThinkMarker(time);
            }
        }

//        if (ProcedureProfiler.profilingLevel != ProcedureProfiler.Level.DISABLED)
//            profiler.stopCounter();
//        statsCollector.endProcedure();

        // Workload Trace - Stop the transaction trace record.
        if (status == ClientResponseImpl.SUCCESS && 
            ProcedureProfiler.profilingLevel == ProcedureProfiler.Level.INTRUSIVE && (ProcedureProfiler.workloadTrace != null && m_workloadXactHandle != null)) {
            ProcedureProfiler.workloadTrace.stopTransaction(m_workloadXactHandle);
        }
        
        if (results == null) {
            LOG.fatal("We got back a null result from txn #" + this.txn_id + " [proc=" + this.procedure_name + "]");
            System.exit(1);
        }
        
        ClientResponseImpl ret = null;
        try {
            ret = new ClientResponseImpl(this.getTransactionId(), status, results, extra, se);
            ret.setClientHandle(this.client_handle);
        } catch (AssertionError ex) {
            LOG.fatal("Failed to create ClientResponse for txn #" + this.txn_id + " [proc=" + this.procedure_name + "]", ex);
            System.exit(1);
        } catch (Exception ex) {
            LOG.fatal("Failed to create ClientResponse for txn #" + this.txn_id + " [proc=" + this.procedure_name + "]", ex);
            System.exit(1);
        }
        
        return (ret);
    }

    
    protected long getClientHandle() {
        return (this.client_handle);
    }

    protected int getLocalPartition() {
        return (this.local_partition);
    }

    protected Catalog getCatalog() {
        return (this.catalog);
        }

    public Procedure getProcedure() {
        return (this.catProc);
    }

    protected PartitionEstimator getPartitionEstimator() {
        return (this.p_estimator);
    }
    
    /**
     * Given the results of a procedure, convert it into a sensible array of VoltTables.
     */
    final private VoltTable[] getResultsFromRawResults(Object result) {
        if (result == null)
            return new VoltTable[0];
        if (result instanceof VoltTable[])
            return (VoltTable[]) result;
        if (result instanceof VoltTable)
            return new VoltTable[] { (VoltTable) result };
        if (result instanceof Long) {
            VoltTable t = new VoltTable(new VoltTable.ColumnInfo("", VoltType.BIGINT));
            t.addRow(result);
            return new VoltTable[] { t };
        }
        throw new RuntimeException("Procedure didn't return acceptable type.");
    }

    /** @throws Exception with a message describing why the types are incompatible. */
    final private Object tryToMakeCompatible(int paramTypeIndex, Object param) throws Exception {
        if (param == null || param == VoltType.NULL_STRING ||
            param == VoltType.NULL_DECIMAL)
        {
            // Passing a null where we expect a primitive is a Java compile time error.
            if (paramTypeIsPrimitive[paramTypeIndex]) {
                throw new Exception("Primitive type " + paramTypes[paramTypeIndex] + " cannot be null");
            }

            // Pass null reference to the procedure run() method. These null values will be
            // converted to a serialize-able NULL representation for the EE in getCleanParams()
            // when the parameters are serialized for the plan fragment.
            return null;
        }

        if (param instanceof ExecutionSite.SystemProcedureExecutionContext) {
            return param;
        }

        Class<?> pclass = param.getClass();
        boolean slotIsArray = paramTypeIsArray[paramTypeIndex];
        if (slotIsArray != pclass.isArray())
            throw new Exception("Array / Scalar parameter mismatch");

        if (slotIsArray) {
            Class<?> pSubCls = pclass.getComponentType();
            Class<?> sSubCls = paramTypeComponentType[paramTypeIndex];
            if (pSubCls == sSubCls) {
                return param;
            } else {
                /*
                 * Arrays can be quite large so it doesn't make sense to silently do the conversion
                 * and incur the performance hit. The client should serialize the correct invocation
                 * parameters
                 */
                new Exception(
                        "tryScalarMakeCompatible: Unable to match parameter array:"
                        + sSubCls.getName() + " to provided " + pSubCls.getName());
            }
        }

        /*
         * inline tryScalarMakeCompatible so we can save on reflection
         */
        final Class<?> slot = paramTypes[paramTypeIndex];
        if ((slot == long.class) && (pclass == Long.class || pclass == Integer.class || pclass == Short.class || pclass == Byte.class)) return param;
        if ((slot == int.class) && (pclass == Integer.class || pclass == Short.class || pclass == Byte.class)) return param;
        if ((slot == short.class) && (pclass == Short.class || pclass == Byte.class)) return param;
        if ((slot == byte.class) && (pclass == Byte.class)) return param;
        if ((slot == double.class) && (pclass == Double.class)) return param;
        if ((slot == String.class) && (pclass == String.class)) return param;
        if (slot == TimestampType.class) {
            if (pclass == Long.class) return new TimestampType((Long)param);
            if (pclass == TimestampType.class) return param;
        }
        if (slot == BigDecimal.class) {
            if (pclass == Long.class) {
                BigInteger bi = new BigInteger(param.toString());
                BigDecimal bd = new BigDecimal(bi);
                bd.setScale(4, BigDecimal.ROUND_HALF_EVEN);
                return bd;
            }
            if (pclass == BigDecimal.class) {
                BigDecimal bd = (BigDecimal) param;
                bd.setScale(4, BigDecimal.ROUND_HALF_EVEN);
                return bd;
            }
        }
        if (slot == VoltTable.class && pclass == VoltTable.class) {
            return param;
        }
        throw new Exception(
                "tryToMakeCompatible: Unable to match parameters:"
                + slot.getName() + " to provided " + pclass.getName());
    }

    /**
     * Thrown from a stored procedure to indicate to VoltDB
     * that the procedure should be aborted and rolled back.
     */
    public static class VoltAbortException extends RuntimeException {
        private static final long serialVersionUID = -1L;
        private String message = "No message specified.";

        /**
         * Constructs a new <code>AbortException</code>
         */
        public VoltAbortException() {}

        /**
         * Constructs a new <code>AbortException</code> with the specified detail message.
         */
        public VoltAbortException(String msg) {
            message = msg;
        }
        /**
         * Returns the detail message string of this <tt>AbortException</tt>
         *
         * @return The detail message.
         */
        @Override
        public String getMessage() {
            return message;
        }
    }

    
    /**
     * Currently unsupported in VoltDB.
     * Batch load method for populating a table with a large number of records.
     *
     * Faster then calling {@link #voltQueueSQL(SQLStmt, Object...)} and {@link #voltExecuteSQL()} to
     * insert one row at a time.
     * @param clusterName Name of the cluster containing the database, containing the table
     *                    that the records will be loaded in.
     * @param databaseName Name of the database containing the table to be loaded.
     * @param tableName Name of the table records should be loaded in.
     * @param data {@link org.voltdb.VoltTable VoltTable} containing the records to be loaded.
     *             {@link org.voltdb.VoltTable.ColumnInfo VoltTable.ColumnInfo} schema must match the schema of the table being
     *             loaded.
     * @throws VoltAbortException
     */
    public void voltLoadTable(String clusterName, String databaseName,
                              String tableName, VoltTable data, int allowELT)
    throws VoltAbortException
    {
        if (data == null || data.getRowCount() == 0) {
            return;
        }
        try {
            assert(m_site != null);
            assert(m_currentTxnState != null);
            m_site.loadTable(m_currentTxnState.getTransactionId(),
                             clusterName, databaseName,
                             tableName, data, allowELT);
        }
        catch (EEException e) {
            throw new VoltAbortException("Failed to load table: " + tableName);
        }
    }

    /**
     * Get the time that this procedure was accepted into the VoltDB cluster. This is the
     * effective, but not always actual, moment in time this procedure executes. Use this
     * method to get the current time instead of non-deterministic methods. Note that the
     * value will not be unique across transactions as it is only millisecond granularity.
     *
     * @return A java.util.Date instance with deterministic time for all replicas using
     * UTC (Universal Coordinated Time is like GMT).
     */
    public Date getTransactionTime() {
        long ts = TransactionIdManager.getTimestampFromTransactionId(m_currentTxnState.getTransactionId());
        return new Date(ts);
    }

    /**
     * Queue the SQL {@link org.voltdb.SQLStmt statement} for execution with the specified argument list.
     *
     * @param stmt {@link org.voltdb.SQLStmt Statement} to queue for execution.
     * @param args List of arguments to be bound as parameters for the {@link org.voltdb.SQLStmt statement}
     * @see <a href="#allowable_params">List of allowable parameter types</a>
     */
    public void voltQueueSQL(final SQLStmt stmt, Object... args) {
        if (!isNative) {
            //HSQLProcedureWrapper does nothing smart. it just implements this interface with runStatement()
            VoltTable table = hsql.runSQLWithSubstitutions(stmt, args);
            queryResults.add(table);
            return;
        }

        if (batchQueryStmtIndex == batchQueryStmts.length) {
            throw new RuntimeException("Procedure attempted to queue more than " + batchQueryStmts.length +
                    " statements in a batch.");
        } else {
            batchQueryStmts[batchQueryStmtIndex++] = stmt;
            batchQueryArgs[batchQueryArgsIndex++] = args;
        }
    }

    /**
     * Execute the currently queued SQL {@link org.voltdb.SQLStmt statements} and return
     * the result tables.
     *
     * @return Result {@link org.voltdb.VoltTable tables} generated by executing the queued
     * query {@link org.voltdb.SQLStmt statements}
     */
    public VoltTable[] voltExecuteSQL() {
        return voltExecuteSQL(false);
    }

    /**
     * Execute the currently queued SQL {@link org.voltdb.SQLStmt statements} and return
     * the result tables. Boolean option allows caller to indicate if this is the final
     * batch for a procedure. If it's final, then additional optimizatons can be enabled.
     *
     * @param isFinalSQL Is this the final batch for a procedure?
     * @return Result {@link org.voltdb.VoltTable tables} generated by executing the queued
     * query {@link org.voltdb.SQLStmt statements}
     */
    public VoltTable[] voltExecuteSQL(boolean isFinalSQL) {
        if (!isNative) {
            VoltTable[] batch_results = queryResults.toArray(new VoltTable[queryResults.size()]);
            queryResults.clear();
            return batch_results;
        }
        
        LocalTransactionState local_ts = (LocalTransactionState)this.m_currentTxnState;
        if (this.m_site.enable_profiling) {
            long timestamp = ProfileMeasurement.getTime();
            local_ts.java_time.stopThinkMarker(timestamp);
            local_ts.coord_time.startThinkMarker(timestamp);
        }

        assert (batchQueryStmtIndex == batchQueryArgsIndex);

        // if profiling is turned on, record the sql statements being run
        if (ProcedureProfiler.profilingLevel == ProcedureProfiler.Level.INTRUSIVE) {
            // Workload Trace - Start Query
            if (ProcedureProfiler.workloadTrace != null && m_workloadXactHandle != null) {
                m_workloadBatchId = ProcedureProfiler.workloadTrace.getNextBatchId(m_workloadXactHandle);
                for (int i = 0; i < batchQueryStmtIndex; i++) {
                    Object queryHandle = ProcedureProfiler.workloadTrace.startQuery(
                            m_workloadXactHandle, batchQueryStmts[i].catStmt, batchQueryArgs[i], m_workloadBatchId);
                    m_workloadQueryHandles.add(queryHandle);
                }
            }
        }

        VoltTable[] retval = null;

        if (ProcedureProfiler.profilingLevel == ProcedureProfiler.Level.INTRUSIVE) {
            retval = executeQueriesInIndividualBatches(batchQueryStmtIndex, batchQueryStmts, batchQueryArgs, isFinalSQL);
        } else {
            retval = executeQueriesInABatch(batchQueryStmtIndex, batchQueryStmts, batchQueryArgs, isFinalSQL);
        }

        // Workload Trace - Stop Query
        if (ProcedureProfiler.profilingLevel == ProcedureProfiler.Level.INTRUSIVE) {
            if (ProcedureProfiler.workloadTrace != null) {
                for (Object handle : m_workloadQueryHandles) {
                    if (handle != null) ProcedureProfiler.workloadTrace.stopQuery(handle);
                }
                // Make sure that we clear out our query handles so that the next
                // time they queue a query they will get a new batch id
                m_workloadQueryHandles.clear();
            }
        }

        batchQueryStmtIndex = 0;
        batchQueryArgsIndex = 0;
        
        if (this.m_site.enable_profiling) {
            long timestamp = ProfileMeasurement.getTime();
            local_ts.coord_time.stopThinkMarker(timestamp);
            local_ts.java_time.startThinkMarker(timestamp);
        }
        
        return retval;
    }

    private VoltTable[] executeQueriesInIndividualBatches(int stmtCount, SQLStmt[] batchStmts, Object[][] batchArgs, boolean finalTask) {
        assert(batchStmts != null) : "Got a null batch statements from " + this.procedure_name + " when we're suppose to have " + stmtCount;
        assert(batchArgs != null);

        VoltTable[] retval = new VoltTable[stmtCount];

        for (int i = 0; i < stmtCount; i++) {
            assert(batchStmts[i] != null);
            assert(batchArgs[i] != null);

            SQLStmt[] subBatchStmts = new SQLStmt[1];
            Object[][] subBatchArgs = new Object[1][];

            subBatchStmts[0] = batchStmts[i];
            subBatchArgs[0] = batchArgs[i];

            boolean isThisLoopFinalTask = finalTask && (i == (stmtCount - 1));
            VoltTable[] results = executeQueriesInABatch(1, subBatchStmts, subBatchArgs, isThisLoopFinalTask);
            assert(results != null);
            assert(results.length == 1);
            retval[i] = results[0];
        }

        return retval;
    }

    private VoltTable[] executeQueriesInABatch(int stmtCount, SQLStmt[] batchStmts, Object[][] batchArgs, boolean finalTask) {
        assert(batchStmts != null);
        assert(batchArgs != null);
        assert(batchStmts.length > 0);
        assert(batchArgs.length > 0);

        if (stmtCount == 0)
            return new VoltTable[] {};

//        if (ProcedureProfiler.profilingLevel == ProcedureProfiler.Level.INTRUSIVE) {
//            assert(batchStmts.length == 1);
//            assert(batchStmts[0].numFragGUIDs == 1);
//            ProcedureProfiler.startStatementCounter(batchStmts[0].fragGUIDs[0]);
//        }
//        else ProcedureProfiler.startStatementCounter(-1);

        /*if (lastBatchNeedsRollback) {
            lastBatchNeedsRollback = false;
            m_site.ee.undoUndoToken(m_site.undoWindowEnd);
        }*/

        // Create a list of clean parameters
        final int batchSize = stmtCount;
        final ParameterSet params[] = new ParameterSet[batchSize];
        for (int i = 0; i < batchSize; i++) {
            params[i] = getCleanParams(batchStmts[i], batchArgs[i]);
        } // FOR
        
        // Calculate the hash code for this batch to see whether we already have a planner
        final Integer batchHashCode = VoltProcedure.getBatchHashCode(batchStmts, batchSize);
        BatchPlanner planner = ExecutionSite.batch_planners.get(batchHashCode);
        if (planner == null) { // Assume fast case
            synchronized (ExecutionSite.batch_planners) {
                planner = ExecutionSite.batch_planners.get(batchHashCode);
                if (planner == null) {
                    planner = new BatchPlanner(batchStmts, batchSize, this.catProc, this.p_estimator);
                    ExecutionSite.batch_planners.put(batchHashCode, planner);
                }
            } // SYNCHRONIZED
        }
        assert(planner != null);

        // At this point we have to calculate exactly what we need to do on each partition
        // for this batch. So somehow right now we need to fire this off to either our
        // local executor or to Evan's magical distributed transaction manager
        BatchPlanner.BatchPlan plan = null;
        try {
            plan = planner.plan(this.txn_id, this.client_handle, this.local_partition, params, this.predict_singlepartition);
        } catch (RuntimeException ex) {
            if (debug.get()) {
                String msg = StringUtil.SINGLE_LINE;
                msg += "Caught " + ex.getClass().getSimpleName() + "!\n";
                
                msg += "CURRENT BATCH\n";
                for (int i = 0; i < batchSize; i++) {
                    msg += String.format("[%02d] %s <==> %s\n     %s\n     %s\n", i, batchStmts[i].catStmt.fullName(), planner.catalog_stmts[i].fullName(), batchStmts[i].catStmt.getSqltext(), Arrays.toString(params[i].toArray()));
                }
                
                msg += "\nPLANNER\n";
                for (int i = 0; i < batchSize; i++) {
                    Statement stmt0 = planner.catalog_stmts[i];
                    Statement stmt1 = batchStmts[i].catStmt;
                    assert(stmt0.fullName().equals(stmt1.fullName())) : stmt0.fullName() + " != " + stmt1.fullName(); 
                    msg += String.format("[%02d] %s\n     %s\n", i, stmt0.fullName(), stmt1.fullName());
                } // FOR
                LOG.fatal("\n" + msg);
            }
            throw ex;
        }
        
        // Tell the TransactionEstimator that we're about to execute these mofos
        TransactionEstimator t_estimator = this.m_site.getTransactionEstimator();
        TransactionEstimator.State t_state = ((LocalTransactionState)this.m_currentTxnState).getEstimatorState();
        if (t_state != null) {
            t_estimator.executeQueries(t_state, planner.getStatements(), plan.getStatementPartitions());
        }
        
        if (debug.get()) LOG.debug("BatchPlan for txn #" + this.txn_id + ":\n" + plan.toString());
        
        // IMPORTANT: Regardless of whether the BatchPlan is local or remote, we are always
        // going to use the ExecutionSite to execute our tasks, rather than use the short-cut
        // to access the EE directly here in executeLocalBatch(). This is because we want
        // to maintain state information in TransactionState. We can probably add in the code
        // to update this txn's state object later on. For now this is just easier.
        return (this.executeBatch(plan));
    }

    /**
     * 
     * @param plan
     * @return
     */
    protected VoltTable[] executeBatch(BatchPlanner.BatchPlan plan) {
        // It is at this point that we just need to make a call into Evan's stuff (through the ExecutionSite)
        // I'm not sure how this is suppose to exactly work, but what the hell let's give it a shot!
        List<FragmentTaskMessage> tasks = plan.getFragmentTaskMessages();
        if (trace.get()) LOG.trace("Got back a set of tasks for " + tasks.size() + " partitions for txn #" + this.txn_id);

        // Block until we get all of our responses.
        // We can do this because our ExecutionSite is multi-threaded
        final VoltTable results[] = this.m_site.waitForResponses(this.txn_id, tasks, plan.getBatchSize());
        assert(results != null) : "Got back a null results array for txn #" + this.txn_id + "\n" + plan.toString();

        // important not to forget
        ProcedureProfiler.stopStatementCounter();

        // Tell our TransactionState that we are done with this BatchPlan
        // It will dispose of it when the transaction commits/aborts
        this.m_currentTxnState.addFinishedBatchPlan(plan);
        
        return results;
    }
    
    // ----------------------------------------------------------------------------
    // UTILITY CRAP
    // ----------------------------------------------------------------------------

    final private Object tryScalarMakeCompatible(Class<?> slot, Object param) throws Exception {
        Class<?> pclass = param.getClass();
        if ((slot == long.class) && (pclass == Long.class || pclass == Integer.class || pclass == Short.class || pclass == Byte.class)) return param;
        if ((slot == int.class) && (pclass == Integer.class || pclass == Short.class || pclass == Byte.class)) return param;
        if ((slot == short.class) && (pclass == Short.class || pclass == Byte.class)) return param;
        if ((slot == byte.class) && (pclass == Byte.class)) return param;
        if ((slot == double.class) && (pclass == Double.class)) return param;
        if ((slot == String.class) && (pclass == String.class)) return param;
        if (slot == Date.class) {
            if (pclass == Long.class) return new Date((Long)param);
            if (pclass == Date.class) return param;
        }
        if (slot == BigDecimal.class) {
            if (pclass == Long.class) {
                BigInteger bi = new BigInteger(param.toString());
                BigDecimal bd = new BigDecimal(bi);
                bd.setScale(4, BigDecimal.ROUND_HALF_EVEN);
                return bd;
            }
            if (pclass == BigDecimal.class) {
                BigDecimal bd = (BigDecimal) param;
                bd.setScale(4, BigDecimal.ROUND_HALF_EVEN);
                return bd;
            }
        }
        if (slot == VoltTable.class && pclass == VoltTable.class) {
            return param;
        }
        throw new Exception("tryScalarMakeCompatible: Unable to matoh parameters:" + slot.getName());
    }

    public static ParameterSet getCleanParams(SQLStmt stmt, Object[] args) {
        final int numParamTypes = stmt.numStatementParamJavaTypes;
        final byte stmtParamTypes[] = stmt.statementParamJavaTypes;
        if (args.length != numParamTypes) {
            throw new ExpectedProcedureException(
                    "Number of arguments provided was " + args.length  +
                    " where " + numParamTypes + " was expected for statement " + stmt.getText());
        }
        for (int ii = 0; ii < numParamTypes; ii++) {
            // this only handles null values
            if (args[ii] != null) continue;
            VoltType type = VoltType.get(stmtParamTypes[ii]);
            if (type == VoltType.TINYINT)
                args[ii] = Byte.MIN_VALUE;
            else if (type == VoltType.SMALLINT)
                args[ii] = Short.MIN_VALUE;
            else if (type == VoltType.INTEGER)
                args[ii] = Integer.MIN_VALUE;
            else if (type == VoltType.BIGINT)
                args[ii] = Long.MIN_VALUE;
            else if (type == VoltType.FLOAT)
                args[ii] = DOUBLE_NULL;
            else if (type == VoltType.TIMESTAMP)
                args[ii] = new TimestampType(Long.MIN_VALUE);
            else if (type == VoltType.STRING)
                args[ii] = VoltType.NULL_STRING;
            else if (type == VoltType.DECIMAL)
                args[ii] = VoltType.NULL_DECIMAL;
            else
                throw new ExpectedProcedureException("Unknown type " + type +
                 " can not be converted to NULL representation for arg " + ii + " for SQL stmt " + stmt.getText());
        }

        final ParameterSet params = new ParameterSet(true);
        params.setParameters(args);
        return params;
    }

    /**
     * Derivation of StatsSource to expose timing information of procedure invocations.
     *
     */
    private final class ProcedureStatsCollector extends SiteStatsSource {

        /**
         * Record procedure execution time ever N invocations
         */
        final int timeCollectionInterval = 20;

        /**
         * Number of times this procedure has been invoked.
         */
        private long m_invocations = 0;
        private long m_lastInvocations = 0;

        /**
         * Number of timed invocations
         */
        private long m_timedInvocations = 0;
        private long m_lastTimedInvocations = 0;

        /**
         * Total amount of timed execution time
         */
        private long m_totalTimedExecutionTime = 0;
        private long m_lastTotalTimedExecutionTime = 0;

        /**
         * Shortest amount of time this procedure has executed in
         */
        private long m_minExecutionTime = Long.MAX_VALUE;
        private long m_lastMinExecutionTime = Long.MAX_VALUE;

        /**
         * Longest amount of time this procedure has executed in
         */
        private long m_maxExecutionTime = Long.MIN_VALUE;
        private long m_lastMaxExecutionTime = Long.MIN_VALUE;

        /**
         * Time the procedure was last started
         */
        private long m_currentStartTime = -1;

        /**
         * Count of the number of aborts (user initiated or DB initiated)
         */
        private long m_abortCount = 0;
        private long m_lastAbortCount = 0;

        /**
         * Count of the number of errors that occured during procedure execution
         */
        private long m_failureCount = 0;
        private long m_lastFailureCount = 0;

        /**
         * Whether to return results in intervals since polling or since the beginning
         */
        private boolean m_interval = false;
        /**
         * Constructor requires no args because it has access to the enclosing classes members.
         */
        public ProcedureStatsCollector() {
            super("XXX", 1);
//            super(m_site.getCorrespondingSiteId() + " " + catProc.getClassname(),
//                  m_site.getCorrespondingSiteId());
        }

        /**
         * Called when a procedure begins executing. Caches the time the procedure starts.
         */
        public final void beginProcedure() {
            if (m_invocations % timeCollectionInterval == 0) {
                m_currentStartTime = System.nanoTime();
            }
        }

        /**
         * Called after a procedure is finished executing. Compares the start and end time and calculates
         * the statistics.
         */
        public final void endProcedure(boolean aborted, boolean failed) {
            if (m_currentStartTime > 0) {
                final long endTime = System.nanoTime();
                final int delta = (int)(endTime - m_currentStartTime);
                m_totalTimedExecutionTime += delta;
                m_timedInvocations++;
                m_minExecutionTime = Math.min( delta, m_minExecutionTime);
                m_maxExecutionTime = Math.max( delta, m_maxExecutionTime);
                m_lastMinExecutionTime = Math.min( delta, m_lastMinExecutionTime);
                m_lastMaxExecutionTime = Math.max( delta, m_lastMaxExecutionTime);
                m_currentStartTime = -1;
            }
            if (aborted) {
                m_abortCount++;
            }
            if (failed) {
                m_failureCount++;
            }
            m_invocations++;
        }

        /**
         * Update the rowValues array with the latest statistical information.
         * This method is overrides the super class version
         * which must also be called so that it can update its columns.
         * @param values Values of each column of the row of stats. Used as output.
         */
        @Override
        protected void updateStatsRow(Object rowKey, Object rowValues[]) {
            super.updateStatsRow(rowKey, rowValues);
            rowValues[columnNameToIndex.get("PARTITION_ID")] = m_site.getPartitionId();
            rowValues[columnNameToIndex.get("PROCEDURE")] = catProc.getClassname();
            long invocations = m_invocations;
            long totalTimedExecutionTime = m_totalTimedExecutionTime;
            long timedInvocations = m_timedInvocations;
            long minExecutionTime = m_minExecutionTime;
            long maxExecutionTime = m_maxExecutionTime;
            long abortCount = m_abortCount;
            long failureCount = m_failureCount;

            if (m_interval) {
                invocations = m_invocations - m_lastInvocations;
                m_lastInvocations = m_invocations;

                totalTimedExecutionTime = m_totalTimedExecutionTime - m_lastTotalTimedExecutionTime;
                m_lastTotalTimedExecutionTime = m_totalTimedExecutionTime;

                timedInvocations = m_timedInvocations - m_lastTimedInvocations;
                m_lastTimedInvocations = m_timedInvocations;

                abortCount = m_abortCount - m_lastAbortCount;
                m_lastAbortCount = m_abortCount;

                failureCount = m_failureCount - m_lastFailureCount;
                m_lastFailureCount = m_failureCount;

                minExecutionTime = m_lastMinExecutionTime;
                maxExecutionTime = m_lastMaxExecutionTime;
                m_lastMinExecutionTime = Long.MAX_VALUE;
                m_lastMaxExecutionTime = Long.MIN_VALUE;
            }

            rowValues[columnNameToIndex.get("INVOCATIONS")] = invocations;
            rowValues[columnNameToIndex.get("TIMED_INVOCATIONS")] = timedInvocations;
            rowValues[columnNameToIndex.get("MIN_EXECUTION_TIME")] = minExecutionTime;
            rowValues[columnNameToIndex.get("MAX_EXECUTION_TIME")] = maxExecutionTime;
            if (timedInvocations != 0) {
                rowValues[columnNameToIndex.get("AVG_EXECUTION_TIME")] =
                     (totalTimedExecutionTime / timedInvocations);
            } else {
                rowValues[columnNameToIndex.get("AVG_EXECUTION_TIME")] = 0L;
            }
            rowValues[columnNameToIndex.get("ABORTS")] = abortCount;
            rowValues[columnNameToIndex.get("FAILURES")] = failureCount;
        }

        /**
         * Specifies the columns of statistics that are added by this class to the schema of a statistical results.
         * @param columns List of columns that are in a stats row.
         */
        @Override
        protected void populateColumnSchema(ArrayList<VoltTable.ColumnInfo> columns) {
            super.populateColumnSchema(columns);
            columns.add(new VoltTable.ColumnInfo("PARTITION_ID", VoltType.INTEGER));
            columns.add(new VoltTable.ColumnInfo("PROCEDURE", VoltType.STRING));
            columns.add(new VoltTable.ColumnInfo("INVOCATIONS", VoltType.BIGINT));
            columns.add(new VoltTable.ColumnInfo("TIMED_INVOCATIONS", VoltType.BIGINT));
            columns.add(new VoltTable.ColumnInfo("MIN_EXECUTION_TIME", VoltType.BIGINT));
            columns.add(new VoltTable.ColumnInfo("MAX_EXECUTION_TIME", VoltType.BIGINT));
            columns.add(new VoltTable.ColumnInfo("AVG_EXECUTION_TIME", VoltType.BIGINT));
            columns.add(new VoltTable.ColumnInfo("ABORTS", VoltType.BIGINT));
            columns.add(new VoltTable.ColumnInfo("FAILURES", VoltType.BIGINT));
        }

        @Override
        protected Iterator<Object> getStatsRowKeyIterator(boolean interval) {
            m_interval = interval;
            return new Iterator<Object>() {
                boolean givenNext = false;
                @Override
                public boolean hasNext() {
                    if (!m_interval) {
                        if (m_invocations == 0) {
                            return false;
                        }
                    } else if (m_invocations - m_lastInvocations == 0){
                        return false;
                    }
                    return !givenNext;
                }

                @Override
                public Object next() {
                    if (!givenNext) {
                        givenNext = true;
                        return new Object();
                    }
                    return null;
                }

                @Override
                public void remove() {}

            };
        }

        @Override
        public String toString() {
            return catProc.getTypeName();
        }
    }

    /**
     * Set the status code that will be returned to the client. This is not the same as the status
     * code returned by the server. If a procedure sets the status code and then rolls back or causes an error
     * the status code will still be propagated back to the client so it is always necessary to check
     * the server status code first.
     * @param statusCode
     */
    public void setAppStatusCode(byte statusCode) {
        m_statusCode = statusCode;
    }

    public void setAppStatusString(String statusString) {
        m_statusString = statusString;
    }

//    private VoltTable[] slowPath(int batchSize, SQLStmt[] batchStmts, Object[][] batchArgs, boolean finalTask) {
//        VoltTable[] results = new VoltTable[batchSize];
//        FastSerializer fs = new FastSerializer();
//
//        // the set of dependency ids for the expected results of the batch
//        // one per sql statment
//        int[] depsToResume = new int[batchSize];
//
//        // these dependencies need to be received before the local stuff can run
//        int[] depsForLocalTask = new int[batchSize];
//
//        // the list of frag ids to run locally
//        long[] localFragIds = new long[batchSize];
//
//        // the list of frag ids to run remotely
//        ArrayList<Long> distributedFragIds = new ArrayList<Long>();
//        ArrayList<Integer> distributedOutputDepIds = new ArrayList<Integer>();
//
//        // the set of parameters for the local tasks
//        ByteBuffer[] localParams = new ByteBuffer[batchSize];
//
//        // the set of parameters for the distributed tasks
//        ArrayList<ByteBuffer> distributedParams = new ArrayList<ByteBuffer>();
//
//        // check if all local fragment work is non-transactional
//        boolean localFragsAreNonTransactional = true;
//
//        // iterate over all sql in the batch, filling out the above data structures
//        for (int i = 0; i < batchSize; ++i) {
//            SQLStmt stmt = batchStmts[i];
//
//            // check if the statement has been oked by the compiler/loader
//            if (stmt.catStmt == null) {
//                String msg = "SQLStmt objects cannot be instantiated after";
//                msg += " VoltDB initialization. User may have instantiated a SQLStmt";
//                msg += " inside a stored procedure's run method.";
//                throw new RuntimeException(msg);
//            }
//
//            // Figure out what is needed to resume the proc
//            int collectorOutputDepId = m_currentTxnState.getNextDependencyId();
//            depsToResume[i] = collectorOutputDepId;
//
//            // Build the set of params for the frags
//            ParameterSet paramSet = getCleanParams(stmt, batchArgs[i]);
//            fs.clear();
//            try {
//                fs.writeObject(paramSet);
//            } catch (IOException e) {
//                e.printStackTrace();
//                assert(false);
//            }
//            ByteBuffer params = fs.getBuffer();
//            assert(params != null);
//
//            // populate the actual lists of fragments and params
//            int numFrags = stmt.catStmt.getFragments().size();
//            assert(numFrags > 0);
//            assert(numFrags <= 2);
//
//            if (numFrags == 1) {
//                for (PlanFragment frag : stmt.catStmt.getFragments()) {
//                    assert(frag != null);
//                    assert(frag.getHasdependencies() == false);
//
//                    localFragIds[i] = CatalogUtil.getUniqueIdForFragment(frag);
//                    localParams[i] = params;
//
//                    // if any frag is transactional, update this check
//                    if (frag.getNontransactional() == false)
//                        localFragsAreNonTransactional = true;
//                }
//                depsForLocalTask[i] = -1;
//            }
//            else {
//                for (PlanFragment frag : stmt.catStmt.getFragments()) {
//                    assert(frag != null);
//
//                    // frags with no deps are usually collector frags that go to all partitions
//                    if (frag.getHasdependencies() == false) {
//                        distributedFragIds.add(CatalogUtil.getUniqueIdForFragment(frag));
//                        distributedParams.add(params);
//                    }
//                    // frags with deps are usually aggregator frags
//                    else {
//                        localFragIds[i] = CatalogUtil.getUniqueIdForFragment(frag);
//                        localParams[i] = params;
//                        assert(frag.getHasdependencies());
//                        int outputDepId =
//                            m_currentTxnState.getNextDependencyId() | DtxnConstants.MULTIPARTITION_DEPENDENCY;
//                        depsForLocalTask[i] = outputDepId;
//                        distributedOutputDepIds.add(outputDepId);
//
//                        // if any frag is transactional, update this check
//                        if (frag.getNontransactional() == false)
//                            localFragsAreNonTransactional = true;
//                    }
//                }
//            }
//        }
//
//        // convert a bunch of arraylists into arrays
//        // this should be easier, but we also want little-i ints rather than Integers
//        long[] distributedFragIdArray = new long[distributedFragIds.size()];
//        int[] distributedOutputDepIdArray = new int[distributedFragIds.size()];
//        ByteBuffer[] distributedParamsArray = new ByteBuffer[distributedFragIds.size()];
//
//        assert(distributedFragIds.size() == distributedParams.size());
//
//        for (int i = 0; i < distributedFragIds.size(); i++) {
//            distributedFragIdArray[i] = distributedFragIds.get(i);
//            distributedOutputDepIdArray[i] = distributedOutputDepIds.get(i);
//            distributedParamsArray[i] = distributedParams.get(i);
//        }
//
//        // instruct the dtxn what's needed to resume the proc
//        m_currentTxnState.setupProcedureResume(finalTask, depsToResume);
//
//        // create all the local work for the transaction
//        FragmentTaskMessage localTask = new FragmentTaskMessage(m_currentTxnState.initiatorSiteId,
//                                                  m_site.getCorrespondingSiteId(),
//                                                  m_currentTxnState.txnId,
//                                                  m_currentTxnState.isReadOnly,
//                                                  localFragIds,
//                                                  depsToResume,
//                                                  localParams,
//                                                  false);
//        for (int i = 0; i < depsForLocalTask.length; i++) {
//            if (depsForLocalTask[i] < 0) continue;
//            localTask.addInputDepId(i, depsForLocalTask[i]);
//        }
//
//        // note: non-transactional work only helps us if it's final work
//        m_currentTxnState.createLocalFragmentWork(localTask, localFragsAreNonTransactional && finalTask);
//
//        // create and distribute work for all sites in the transaction
//        FragmentTaskMessage distributedTask = new FragmentTaskMessage(m_currentTxnState.initiatorSiteId,
//                                                        m_site.getCorrespondingSiteId(),
//                                                        m_currentTxnState.txnId,
//                                                        m_currentTxnState.isReadOnly,
//                                                        distributedFragIdArray,
//                                                        distributedOutputDepIdArray,
//                                                        distributedParamsArray,
//                                                        finalTask);
//
//        m_currentTxnState.createAllParticipatingFragmentWork(distributedTask);
//
//        // recursively call recurableRun and don't allow it to shutdown
//        Map<Integer,List<VoltTable>> mapResults =
//            m_site.recursableRun(m_currentTxnState);
//
//        assert(mapResults != null);
//        assert(depsToResume != null);
//        assert(depsToResume.length == batchSize);
//
//        // build an array of answers, assuming one result per expected id
//        for (int i = 0; i < batchSize; i++) {
//            List<VoltTable> matchingTablesForId = mapResults.get(depsToResume[i]);
//            assert(matchingTablesForId != null);
//            assert(matchingTablesForId.size() == 1);
//            results[i] = matchingTablesForId.get(0);
//
//            if (batchStmts[i].catStmt.getReplicatedtabledml()) {
//                long newVal = results[i].asScalarLong() / numberOfPartitions;
//                results[i] = new VoltTable(new VoltTable.ColumnInfo("", VoltType.BIGINT));
//                results[i].addRow(newVal);
//            }
//        }
//
//        return results;
//    }

    /**
     *
     * @param e
     * @return A ClientResponse containing error information
     */
    private ClientResponseImpl getErrorResponse(Throwable e) {
        StackTraceElement[] stack = e.getStackTrace();
        ArrayList<StackTraceElement> matches = new ArrayList<StackTraceElement>();
        for (StackTraceElement ste : stack) {
            if (ste.getClassName() == getClass().getName())
                matches.add(ste);
        }

        byte status = ClientResponseImpl.UNEXPECTED_FAILURE;
        StringBuilder msg = new StringBuilder();

        if (e.getClass() == VoltAbortException.class) {
            status = ClientResponseImpl.USER_ABORT;
            msg.append("USER ABORT\n");
        }
        else if (e.getClass() == org.voltdb.exceptions.ConstraintFailureException.class) {
            status = ClientResponseImpl.GRACEFUL_FAILURE;
            msg.append("CONSTRAINT VIOLATION\n");
        }
        else if (e.getClass() == org.voltdb.exceptions.SQLException.class) {
            status = ClientResponseImpl.GRACEFUL_FAILURE;
            msg.append("SQL ERROR\n");
        }
        else if (e.getClass() == org.voltdb.ExpectedProcedureException.class) {
            msg.append("HSQL-BACKEND ERROR\n");
            if (e.getCause() != null)
                e = e.getCause();
        }
        else {
            msg.append("UNEXPECTED FAILURE:\n");
        }

        String exMsg = e.getMessage();
        if (exMsg == null)
            if (e.getClass() == NullPointerException.class) {
                exMsg = "Null Pointer Exception";
            }
            else {
                exMsg = "Possible Null Pointer Exception (";
                exMsg += e.getClass().getSimpleName() + ")";
                e.printStackTrace();
            }

        msg.append("  ").append(exMsg);

        for (StackTraceElement ste : matches) {
            msg.append("\n    at ");
            msg.append(ste.getClassName()).append(".").append(ste.getMethodName());
            msg.append("(").append(ste.getFileName()).append(":");
            msg.append(ste.getLineNumber()).append(")");
        }

        return getErrorResponse(
                status, msg.toString(),
                e instanceof SerializableException ? (SerializableException)e : null);
    }

    private ClientResponseImpl getErrorResponse(byte status, String msg, SerializableException e) {

        StringBuilder msgOut = new StringBuilder();
        msgOut.append("\n===============================================================================\n");
        msgOut.append("VOLTDB ERROR: ");
        msgOut.append(msg);
        msgOut.append("\n===============================================================================\n");

        LOG.trace(msgOut);

        return new ClientResponseImpl(
                this.getTransactionId(),
                status,
                m_statusCode,
                m_statusString,
                new VoltTable[0],
                msgOut.toString(), e);
    }
}

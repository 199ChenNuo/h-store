package org.voltdb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.voltdb.BatchPlanner.PlanVertex;
import org.voltdb.catalog.PlanFragment;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.StmtParameter;

import edu.brown.BaseTestCase;
import edu.brown.benchmark.seats.procedures.DeleteReservation;
import edu.brown.benchmark.seats.procedures.LoadConfig;
import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.Hstore.TransactionWorkRequest.InputDependency;
import edu.brown.hstore.Hstore.TransactionWorkRequest.WorkFragment;
import edu.brown.statistics.Histogram;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.ProjectType;
import edu.brown.utils.StringUtil;
import edu.mit.hstore.HStoreConstants;

public class TestBatchPlannerComplex extends BaseTestCase {

    private static final Class<? extends VoltProcedure> TARGET_PROCEDURE = LoadConfig.class;
    private static final int NUM_PARTITIONS = 4;
    private static final int BASE_PARTITION = 0;
    private static final long TXN_ID = 123l;
    private static final long CLIENT_HANDLE = Long.MAX_VALUE;

    private SQLStmt batch[];
    private ParameterSet args[];
    
    private MockExecutionSite executor;
    private Histogram<Integer> touched_partitions;
    private Procedure catalog_proc;
    private BatchPlanner planner;
    private BatchPlanner.BatchPlan plan;

    @Override
    protected void setUp() throws Exception {
        super.setUp(ProjectType.SEATS);
        this.addPartitions(NUM_PARTITIONS);
        this.touched_partitions = new Histogram<Integer>();
        this.catalog_proc = this.getProcedure(TARGET_PROCEDURE);
        
        this.batch = new SQLStmt[this.catalog_proc.getStatements().size()];
        this.args = new ParameterSet[this.batch.length];
        int i = 0;
        for (Statement catalog_stmt : this.catalog_proc.getStatements()) {
            this.batch[i] = new SQLStmt(catalog_stmt);
            this.args[i] = ParameterSet.EMPTY;
            i++;
        } // FOR

        VoltProcedure volt_proc = ClassUtil.newInstance(TARGET_PROCEDURE, new Object[0], new Class<?>[0]);
        assert(volt_proc != null);
        this.executor = new MockExecutionSite(BASE_PARTITION, catalog, p_estimator);
        volt_proc.globalInit(this.executor, catalog_proc, BackendTarget.NONE, null, p_estimator);
        
        this.planner = new BatchPlanner(this.batch, this.catalog_proc, p_estimator);
        this.plan = planner.plan(TXN_ID,
                                 CLIENT_HANDLE,
                                 0,
                                 CatalogUtil.getAllPartitionIds(catalog_db),
                                 false,
                                 this.touched_partitions,
                                 this.args);
        assertNotNull(plan);
        assertFalse(plan.hasMisprediction());
    }

    /**
     * testGetPlanGraph
     */
    public void testGetPlanGraph() throws Exception {
        BatchPlanner.PlanGraph graph = plan.getPlanGraph();
        assertNotNull(graph);
        
        // Make sure that only PlanVertexs with input dependencies have a child in the graph
        for (PlanVertex v : graph.getVertices()) {
            assertNotNull(v);
            if (v.input_dependency_id == HStoreConstants.NULL_DEPENDENCY_ID) {
                assertEquals(0, graph.getSuccessorCount(v));
            } else {
                assertEquals(1, graph.getSuccessorCount(v));
            }
        } // FOR
        
//        GraphVisualizationPanel.createFrame(graph, GraphVisualizationPanel.makeVertexObserver(graph)).setVisible(true);
//        ThreadUtil.sleep(1000000);
    }
    
    /**
     * testFragmentIds
     */
    public void testFragmentIds() throws Exception {
        catalog_proc = this.getProcedure(DeleteReservation.class);
        
        // Make sure that PlanFragment ids in each WorkFragment only
        // belong to the Procedure
        for (Statement catalog_stmt : catalog_proc.getStatements()) {
            batch = new SQLStmt[] { new SQLStmt(catalog_stmt) };
            args = new ParameterSet[] {
                    new ParameterSet(this.makeRandomStatementParameters(catalog_stmt))
            };
            this.planner = new BatchPlanner(this.batch, this.catalog_proc, p_estimator);
            this.touched_partitions.clear();
            this.plan = planner.plan(TXN_ID,
                                     CLIENT_HANDLE,
                                     0,
                                     CatalogUtil.getAllPartitionIds(catalog_db),
                                     false,
                                     this.touched_partitions,
                                     this.args);
            assertNotNull(plan);
            assertFalse(plan.hasMisprediction());
        
            List<WorkFragment> fragments = new ArrayList<WorkFragment>();
            plan.getWorkFragments(fragments);
            assertFalse(fragments.isEmpty());
        
            for (WorkFragment pf : fragments) {
                assertNotNull(pf);
                for (int frag_id : pf.getFragmentIdList()) {
                    PlanFragment catalog_frag = CatalogUtil.getPlanFragment(catalog_proc, frag_id);
                    assertNotNull(catalog_frag);
                    assertEquals(catalog_frag.fullName(), catalog_stmt, catalog_frag.getParent());
                } // FOR
//                System.err.println(pf);
            } // FOR
        } // FOR
    }
    
    
    /**
     * testBuildWorkFragments
     */
    public void testBuildWorkFragments() throws Exception {
        List<WorkFragment> fragments = new ArrayList<WorkFragment>();
        plan.getWorkFragments(fragments);
        assertFalse(fragments.isEmpty());
        
        for (WorkFragment pf : fragments) {
            assertNotNull(pf);
//            System.err.println(pf);
            
            // If this WorkFragment is not for the base partition, then
            // we should make sure that it only has distributed queries...
            if (pf.getPartitionId() != BASE_PARTITION) {
                for (int frag_id : pf.getFragmentIdList()) {
                    PlanFragment catalog_frag = CatalogUtil.getPlanFragment(catalog, frag_id);
                    assertNotNull(catalog_frag);
                    Statement catalog_stmt = catalog_frag.getParent();
                    assertNotNull(catalog_stmt);
                    assert(catalog_stmt.getMs_fragments().contains(catalog_frag));
                } // FOR
            }
            
            // The InputDepId for all WorkFragments should always be the same
            Set<Integer> all_ids = new HashSet<Integer>();
            for (InputDependency input_dep_ids : pf.getInputDepIdList()) {
                all_ids.addAll(input_dep_ids.getIdsList());
            } // FOR
            assertEquals(pf.toString(), 1, all_ids.size());
            
//            System.err.println(StringUtil.SINGLE_LINE);
        } // FOR
    } // FOR
}

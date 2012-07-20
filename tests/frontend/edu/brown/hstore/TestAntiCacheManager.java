package edu.brown.hstore;

import java.io.File;
import java.util.concurrent.Semaphore;

import org.junit.Before;
import org.junit.Test;
import org.voltdb.SysProcSelector;
import org.voltdb.VoltTable;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;
import org.voltdb.exceptions.UnknownBlockAccessException;
import org.voltdb.jni.ExecutionEngine;
import org.voltdb.utils.VoltTableUtil;

import edu.brown.BaseTestCase;
import edu.brown.benchmark.AbstractProjectBuilder;
import edu.brown.benchmark.voter.VoterConstants;
import edu.brown.benchmark.voter.VoterProjectBuilder;
import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.EventObservable;
import edu.brown.utils.EventObserver;
import edu.brown.utils.FileUtil;
import edu.brown.utils.ThreadUtil;

public class TestAntiCacheManager extends BaseTestCase {
    
    private static final int NUM_PARTITIONS = 1;
    private static final int NUM_TUPLES = 100;
    // private static final String TARGET_TABLE = TPCCConstants.TABLENAME_WAREHOUSE;
    private static final String TARGET_TABLE = VoterConstants.TABLENAME_VOTES;
    
    private HStoreSite hstore_site;
    private HStoreConf hstore_conf;
    private Thread thread;
    private File anticache_dir;
    private Semaphore readyLock;
    
    private PartitionExecutor executor;
    private ExecutionEngine ee;
    private Table catalog_tbl;

    private EventObserver<HStoreSite> ready = new EventObserver<HStoreSite>() {
        @Override
        public void update(EventObservable<HStoreSite> o, HStoreSite arg) {
            readyLock.release();
        }
    };
    
    private final AbstractProjectBuilder builder = new VoterProjectBuilder() {
        {
            this.markTableEvictable(TARGET_TABLE);
            this.addAllDefaults();
        }
    };
    
    @Before
    public void setUp() throws Exception {
        super.setUp(builder, false);
        initializeCluster(1, 1, NUM_PARTITIONS);
        
        // Just make sure that the Table has the evictable flag set to true
        this.catalog_tbl = getTable(TARGET_TABLE);
        assertTrue(catalog_tbl.getEvictable());
        
        this.anticache_dir = FileUtil.getTempDirectory();
        this.readyLock = new Semaphore(0);
        
        Site catalog_site = CollectionUtil.first(CatalogUtil.getCluster(catalog).getSites());
        this.hstore_conf = HStoreConf.singleton();
        this.hstore_conf.site.status_enable = false;
        this.hstore_conf.site.anticache_enable = true;
        this.hstore_conf.site.anticache_dir = this.anticache_dir.getAbsolutePath();
        
        this.hstore_site = HStore.initialize(catalog_site, hstore_conf);
        this.hstore_site.getReadyObservable().addObserver(this.ready);
        this.thread = new Thread(this.hstore_site);
        this.thread.start();
        
        // Wait until we know that our HStoreSite has started
        this.readyLock.acquire();
        // I added an extra little sleep just to be sure...
        ThreadUtil.sleep(3000);
        
        this.executor = hstore_site.getPartitionExecutor(0);
        assertNotNull(this.executor);
        this.ee = executor.getExecutionEngine();
        assertNotNull(this.executor);
    }
    
    @Override
    protected void tearDown() throws Exception {
        this.hstore_site.shutdown();
        FileUtil.deleteDirectory(this.anticache_dir);
    }
    
    /**
     * testEvictTuples
     */
    @Test
    public void testEvictTuples() throws Exception {
        String statsFields[] = {
            "TUPLES_EVICTED",
            "BLOCKS_EVICTED",
            "BYTES_EVICTED"
        };
        
        // Load in a bunch of dummy data for this table
        VoltTable vt = CatalogUtil.getVoltTable(catalog_tbl);
        assertNotNull(vt);
        for (int i = 0; i < NUM_TUPLES; i++) {
            Object row[] = VoltTableUtil.getRandomRow(catalog_tbl);
            row[0] = Integer.valueOf(i);
            vt.addRow(row);
        } // FOR
        this.executor.loadTable(1000l, catalog_tbl, vt, false);

        int locators[] = new int[] { catalog_tbl.getRelativeIndex() };
        VoltTable results[] = this.ee.getStats(SysProcSelector.TABLE, locators, false, 0L);
        assertEquals(1, results.length);
        System.err.println(VoltTableUtil.format(results));
        for (String col : statsFields) {
            int idx = results[0].getColumnIndex(col);
            assertEquals(0, results[0].getLong(idx));    
        } // FOR
        
        // Now force the EE to evict our boys out
        // We'll tell it to remove 1MB, which is guaranteed to include all of our tuples
        VoltTable evictResult = this.ee.antiCacheEvictBlock(catalog_tbl, 1024 * 1024);
        System.err.println("-------------------------------");
        System.err.println(VoltTableUtil.format(evictResult));
        assertNotNull(evictResult);
        assertEquals(1, evictResult.getRowCount());
        assertNotSame(results[0].getColumnCount(), evictResult.getColumnCount());
        evictResult.resetRowPosition();
        boolean adv = evictResult.advanceRow();
        assertTrue(adv);

        // Our stats should now come back with at least one block evicted
        results = this.ee.getStats(SysProcSelector.TABLE, locators, false, 0L);
        assertEquals(1, results.length);
        System.err.println("-------------------------------");
        System.err.println(VoltTableUtil.format(results));
        for (String col : statsFields) {
            assertEquals(col, evictResult.getLong(col), results[0].getLong(col));
            if (col == "BLOCKS_EVICTED") {
                assertEquals(col, 1, results[0].getLong(col));
            } else {
                assertNotSame(col, 0, results[0].getLong(col));
            }
        } // FOR
    }

    /**
     * testReadNonExistentBlock
     */
    @Test
    public void testReadNonExistentBlock() throws Exception {
        short block_ids[] = new short[]{ 1111 };
        boolean failed = false;
        try {
            ee.antiCacheReadBlocks(catalog_tbl, block_ids);   
        } catch (UnknownBlockAccessException ex) {
            // This is what we want!
            assertEquals(catalog_tbl, ex.getTableId(catalog_db));
            assertEquals(block_ids[0], ex.getBlockId());
            failed = true;
            System.err.println(ex);
        }
        assertTrue(failed);
    }
    
}

package org.voltdb.sysprocs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.voltdb.BackendTarget;
import org.voltdb.DependencySet;
import org.voltdb.HsqlBackend;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.catalog.Procedure;
import org.voltdb.exceptions.ServerFaultException;
import org.voltdb.types.TimestampType;

import edu.brown.hstore.PartitionExecutor;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.PartitionEstimator;

/** 
 * Set HStoreConf parameters throughout the cluster
 * Note that this is not persistent.
 */
@ProcInfo(singlePartition = false)
public class SetConfiguration extends VoltSystemProcedure {
    private static final Logger LOG = Logger.getLogger(SetConfiguration.class);

    public static final ColumnInfo nodeResultsColumns[] = {
        new ColumnInfo("SITE", VoltType.INTEGER),
        new ColumnInfo("NAME", VoltType.STRING),
        new ColumnInfo("VALUE", VoltType.STRING),
    };
    
    public static final ColumnInfo aggregateResultsColumns[] = {
        new ColumnInfo("NAME", VoltType.STRING),
        new ColumnInfo("VALUE", VoltType.STRING),
        new ColumnInfo("UPDATED", VoltType.TIMESTAMP),
    };
    
    private static final int DISTRIBUTE_ID = SysProcFragmentId.PF_setConfDistribute;
    private static final int AGGREGATE_ID = SysProcFragmentId.PF_setConfAggregate;
    

    @Override
    public void globalInit(PartitionExecutor site, Procedure catalog_proc,
            BackendTarget eeType, HsqlBackend hsql, PartitionEstimator p_estimator) {
        super.globalInit(site, catalog_proc, eeType, hsql, p_estimator);
        site.registerPlanFragment(AGGREGATE_ID, this);
        site.registerPlanFragment(DISTRIBUTE_ID, this);
    }

    @Override
    public DependencySet executePlanFragment(long txn_id,
                                             Map<Integer, List<VoltTable>> dependencies,
                                             int fragmentId,
                                             ParameterSet params,
                                             PartitionExecutor.SystemProcedureExecutionContext context) {
        DependencySet result = null;
        assert(params.size() == 2);
        String confNames[] = (String[])params.toArray()[0];
        String confValues[] = (String[])params.toArray()[1];
        
        switch (fragmentId) {
            case DISTRIBUTE_ID: {
                HStoreConf hstore_conf = executor.getHStoreConf();
                assert(hstore_conf != null);
                Map<String, String> m = new HashMap<String, String>();
                for (int i = 0; i < confNames.length; i++) {
                    m.put(confNames[i], confValues[i]);
                } // FOR
                hstore_conf.loadFromArgs(m);
                if (LOG.isDebugEnabled())
                    LOG.debug(String.format("Updating %d conf parameters on %s",
                              m.size(), executor.getHStoreSite().getSiteName()));
                
                VoltTable vt = new VoltTable(nodeResultsColumns);
                for (int i = 0; i < confNames.length; i++) {
                    Object row[] = {
                        executor.getSiteId(),
                        confNames[i],
                        hstore_conf.get(confNames[i]).toString(),
                    };
                    vt.addRow(row);
                } // FOR
                result = new DependencySet(DISTRIBUTE_ID, vt);
                break;
            }
            // Aggregate Results
            case AGGREGATE_ID:
                List<VoltTable> siteResults = dependencies.get(DISTRIBUTE_ID);
                if (siteResults == null || siteResults.isEmpty()) {
                    String msg = "Missing site results";
                    throw new ServerFaultException(msg, txn_id);
                }
                
                // Make sure that everyone is the same value
                VoltTable vt = new VoltTable(aggregateResultsColumns);
                TimestampType timestamp = new TimestampType();
                Set<String> values = new HashSet<String>();
                for (int i = 0; i < confNames.length; i++) {
                    if (i > 0) values.clear();
                    for (VoltTable site_vt : siteResults) {
                        if (i > 0) site_vt.resetRowPosition();
                        while (site_vt.advanceRow()) {
                            if (site_vt.getString(1).equalsIgnoreCase(confNames[i])) {
                                values.add(site_vt.getString(2));
                                break;
                            }
                        } // WHILE
                    } // FOR (site results)
                    if (values.isEmpty()) {
                        String msg = "Failed to find updated configuration values for '" + confNames[i] + "'";
                        throw new VoltAbortException(msg);
                    }
                    else if (values.size() > 1) {
                        String msg = String.format("Unexpected multiple values for '%s': %s",
                                                   confNames[i], values);
                        throw new VoltAbortException(msg);
                    }
                    vt.addRow(confNames[i], CollectionUtil.first(values).toString(), timestamp);
                } // FOR
                result = new DependencySet(AGGREGATE_ID, vt);
                break;
            default:
                String msg = "Unexpected sysproc fragmentId '" + fragmentId + "'";
                throw new ServerFaultException(msg, txn_id);
        } // SWITCH
        // Invalid!
        return (result);
    }
    
    public VoltTable[] run(String confNames[], String confValues[]) {
        if (confNames.length != confValues.length) {
            String msg = String.format("The number of conf names and values are not equal [%d != %d]",
                                       confNames.length, confValues.length);
            throw new VoltAbortException(msg);
        }
        
        HStoreConf hstore_conf = executor.getHStoreConf();
        for (int i = 0; i < confNames.length; i++) {
            if (hstore_conf.hasParameter(confNames[i]) == false) {
                String msg = String.format("Invalid configuration parameter '%s'", confNames[i]);
                throw new VoltAbortException(msg);
            }
        } // FOR
        
        ParameterSet params = new ParameterSet(confNames, confValues);
        return this.executeOncePerSite(DISTRIBUTE_ID, AGGREGATE_ID, params);
    }
}

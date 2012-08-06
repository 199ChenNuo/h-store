package edu.brown.api.results;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.Database;

import edu.brown.statistics.FastIntHistogram;
import edu.brown.statistics.Histogram;
import edu.brown.utils.JSONSerializable;
import edu.brown.utils.JSONUtil;

/**
 * Raw results collected for each BenchmarkComponent instance
 * @author pavlo
 */
public class BenchmarkComponentResults implements JSONSerializable {

    public FastIntHistogram transactions;
    
    private boolean enableLatencies = false;
    public final Map<Integer, Histogram<Integer>> latencies = new HashMap<Integer, Histogram<Integer>>();
    
    private boolean enableBasePartitions = false;
    public Histogram<Integer> basePartitions = new Histogram<Integer>(true);
    
    private boolean enableResponseStatuses = false;
    public Histogram<String> responseStatuses = new Histogram<String>(true);

    public BenchmarkComponentResults() {
        // Needed for deserialization
    }
    
    public BenchmarkComponentResults(int numTxns) {
        this.transactions = new FastIntHistogram(numTxns);
        this.transactions.setKeepZeroEntries(true);
    }
    
    public BenchmarkComponentResults copy() {
        final BenchmarkComponentResults copy = new BenchmarkComponentResults(this.transactions.fastSize());
        copy.transactions.setDebugLabels(this.transactions.getDebugLabels());
        copy.transactions.put(this.transactions);
        copy.enableLatencies = this.enableLatencies;
        copy.latencies.clear();
        for (Entry<Integer, Histogram<Integer>> e : this.latencies.entrySet()) {
            copy.latencies.put(e.getKey(), new Histogram<Integer>(e.getValue()));
        } // FOR
        
        copy.enableBasePartitions = this.enableBasePartitions;
        copy.basePartitions.put(this.basePartitions);
        
        copy.enableResponseStatuses = this.enableResponseStatuses;
        copy.responseStatuses.put(this.responseStatuses);
        
        return (copy);
    }
    
    public boolean isLatenciesEnabled() {
        return (this.enableLatencies);
    }
    public void setEnableLatencies(boolean val) {
        this.enableLatencies = val;
    }
    
    public boolean isBasePartitionsEnabled() {
        return (this.enableBasePartitions);
    }
    public void setEnableBasePartitions(boolean val) {
        this.enableBasePartitions = val;
    }

    public boolean isResponsesStatusesEnabled() {
        return (this.enableResponseStatuses);
    }
    public void setEnableResponsesStatuses(boolean val) {
        this.enableResponseStatuses = val;
    }
    
    public void clear(boolean includeTxns) {
        if (includeTxns && this.transactions != null) {
            this.transactions.clearValues();
        }
        this.latencies.clear();
        this.basePartitions.clearValues();
        this.responseStatuses.clearValues();
    }
    
    // ----------------------------------------------------------------------------
    // SERIALIZATION METHODS
    // ----------------------------------------------------------------------------
    @Override
    public void load(File input_path, Database catalog_db) throws IOException {
        JSONUtil.load(this, catalog_db, input_path);
    }
    @Override
    public void save(File output_path) throws IOException {
        JSONUtil.save(this, output_path);
    }
    @Override
    public String toJSONString() {
        return (JSONUtil.toJSONString(this));
    }
    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        String exclude[] = {
            (this.enableLatencies == false ? "latencies" : ""),
            (this.enableBasePartitions == false ? "basePartitions" : ""),
            (this.enableResponseStatuses == false ? "responseStatuses" : ""),
        };
        Field fields[] = JSONUtil.getSerializableFields(this.getClass(), exclude);
        JSONUtil.fieldsToJSON(stringer, this, BenchmarkComponentResults.class, fields);
    }
    @Override
    public void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException {
        this.latencies.clear();
        JSONUtil.fieldsFromJSON(json_object, catalog_db, this, BenchmarkComponentResults.class, true,
                JSONUtil.getSerializableFields(this.getClass()));
        assert(this.transactions != null);
    }
} // END CLASS
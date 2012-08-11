package edu.brown.hstore.estimators;

import java.util.Map;

import org.voltdb.CatalogContext;
import org.voltdb.catalog.Procedure;

import edu.brown.hashing.AbstractHasher;
import edu.brown.utils.ParameterMangler;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.PartitionSet;

public class TM1Estimator extends AbstractEstimator {

    public TM1Estimator(CatalogContext catalogContext, PartitionEstimator p_estimator) {
        super(catalogContext);
    }
    
    @Override
    protected PartitionSet initializeTransactionImpl(Procedure catalog_proc, Object[] args, Object[] mangled) {
        String procName = catalog_proc.getName();
        PartitionSet ret = null;
        
        if (procName.equalsIgnoreCase("UpdateLocation")) {
            
        }
        
        return (ret);
    }

}

package edu.brown.hstore.dispatchers;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.log4j.Logger;

import edu.brown.hstore.HStoreCoordinator;
import edu.brown.hstore.util.AbstractProcessingThread;
import edu.brown.utils.ClassUtil;

/**
 * A dispatcher is a asynchronous processor for a specific type of message from
 * the HStoreCoordinator 
 * @author pavlo
 * @param <E>
 */
public abstract class AbstractDispatcher<E> extends AbstractProcessingThread<E> {
    private static final Logger LOG = Logger.getLogger(AbstractDispatcher.class);
    
    protected final HStoreCoordinator hstore_coordinator;

    
    /**
     * @param hstore_coordinator
     */
    public AbstractDispatcher(HStoreCoordinator hstore_coordinator) {
        super(hstore_coordinator.getHStoreSite(),
              "dispatcher",
              new LinkedBlockingDeque<E>(),
              hstore_coordinator.getHStoreConf().site.exec_profiling);
        this.hstore_coordinator = hstore_coordinator;
    }
    
    protected final void processingCallback(E e) {
        try {
            this.runImpl(e);
        } catch (Throwable ex) {
            String dump = null;
            if (ClassUtil.isArray(e)) {
                dump = Arrays.toString((Object[])e);
            } else if (e != null) {
                dump = e.toString();
            }
            LOG.warn("Failed to process queued element: " + dump, ex);
        }
    }
    public final void queue(E e) {
        this.queue.offer(e);
    }
    
    public abstract void runImpl(E e);
    
}
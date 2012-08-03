package edu.brown.profilers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractProfiler {
    
    /**
     * Lazily construct this cache list of ProfileMeasurements only
     * when somebody asks for it. We will construct the lists in a thread-safe manner
     */
    private ProfileMeasurement[] pm_cache = null;
    
    /**
     * Return a list of all of the ProfileMeasurement handles for this profiler
     * @return
     */
    public final ProfileMeasurement[] getProfileMeasurements() {
        if (pm_cache == null) {
            synchronized (this) {
                if (pm_cache == null) {
                    final List<ProfileMeasurement> temp = new ArrayList<ProfileMeasurement>();
                    for (Field f : this.getClass().getDeclaredFields()) {
                        int modifiers = f.getModifiers();
                        if (Modifier.isTransient(modifiers) == false &&
                            Modifier.isPrivate(modifiers) == false &&
                            Modifier.isStatic(modifiers) == false) {
                            
                            Object obj = null;
                            try {
                                obj = f.get(this);
                            } catch (Exception ex) {
                                throw new RuntimeException("Failed to get value for field '" + f.getName() + "'", ex);
                            }
                            if (obj instanceof ProfileMeasurement) temp.add((ProfileMeasurement)obj);
                        }
                    } // FOR
                    pm_cache = temp.toArray(new ProfileMeasurement[temp.size()]);
                }
            } // SYNCH
        }
        return (pm_cache);
    }

    /**
     * Reset all of the ProfileMeasurements within this profiler
     */
    public void reset() {
        for (ProfileMeasurement pm : this.getProfileMeasurements()) {
            pm.reset();
        } // FOR
    }
    
    /**
     * Returns a tuple of the values for this profiler
     * There will be two entries in the tuple for each ProfileMeasurement:
     *  (1) The total think time in nanoseconds
     *  (2) The number of invocations 
     */
    public final long[] getTuple() {
        ProfileMeasurement pms[] = this.getProfileMeasurements();
        long tuple[] = new long[pms.length*2];
        int offset = 0;
        for (ProfileMeasurement pm : pms) {
            tuple[offset++] = pm.getTotalThinkTime();
            if (offset == 1) assert(tuple[0] > 0) : "Missing data " + pm.debug(true);
            tuple[offset++] = pm.getInvocations();
        } // FOR
        return (tuple);
    }
    
    public Map<String, Object> debugMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        
        // HEADER
        m.put(this.getClass().getSimpleName(), null);
        
        // FIELDS
        for (ProfileMeasurement pm : this.getProfileMeasurements()) {
            String label = pm.getType();
            String debug = pm.debug(true).replace(label, "");
            m.put(label, debug);
        } // FOR
        
        return (m);
    }
}

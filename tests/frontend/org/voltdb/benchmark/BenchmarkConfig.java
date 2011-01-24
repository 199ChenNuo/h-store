/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.benchmark;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BenchmarkConfig {

    public final String benchmarkClient;
    public final String backend;
    public String[] hosts;
    public final int sitesPerHost;
    public final int k_factor;
    public final String[] clients;
    public final int processesPerClient;
    public final long interval;
    public final long duration;
    public final String remotePath;
    public final String remoteUser;
    public final boolean listenForDebugger;
    public final int serverHeapSize;
    public final int clientHeapSize;
    public final boolean localmode;
    public final String useProfile;
    public final float checkTransaction;
    public final boolean checkTables;
    public final String snapshotPath;
    public final String snapshotPrefix;
    public final String snapshotFrequency;
    public final int snapshotRetain;
    public final String statsDatabaseURL;
    public final String resultsDatabaseURL;
    public final String statsTag;//Identifies the result set
    public final String applicationName;
    public final String subApplicationName;
    
    public final String coordinatorHost;
    public final boolean noCoordinator;
    public final boolean compileBenchmark;
    public final boolean compileOnly;
    public final boolean useCatalogHosts;
    public final boolean noDataLoad;
    public final String workloadTrace;
    public final Set<Integer> profileSiteIds;

    public final Map<String, String> parameters = new HashMap<String, String>();

    public BenchmarkConfig(
            String benchmarkClient,
            String backend,
            String coordinatorHost,
            boolean noCoordinator,
            String[] hosts,
            int sitesPerHost,
            int kFactor,
            String[] clients,
            int processesPerClient,
            long interval,
            long duration,
            String remotePath,
            String remoteUser,
            boolean listenForDebugger,
            int serverHeapSize,
            int clientHeapSize,
            boolean localmode,
            String useProfile,
            float checkTransaction,
            boolean checkTables,
            String snapshotPath,
            String snapshotPrefix,
            String snapshotFrequency,
            int snapshotRetain,
            String statsDatabaseURL,
            String resultsDatabaseURL,
            String statsTag,
            String applicationName,
            String subApplicationName,
            boolean compileBenchmark,
            boolean compileOnly,
            boolean useCatalogHosts,
            boolean noDataLoad,
            String workloadTrace,
            Set<Integer> profileSiteIds
        ) {

        this.benchmarkClient = benchmarkClient;
        this.backend = backend;
        this.coordinatorHost = coordinatorHost;
        this.noCoordinator = noCoordinator;
        this.hosts = new String[hosts.length];
        for (int i = 0; i < hosts.length; i++)
            this.hosts[i] = hosts[i];
        this.sitesPerHost = sitesPerHost;
        this.k_factor = kFactor;
        this.clients = new String[clients.length];
        for (int i = 0; i < clients.length; i++)
            this.clients[i] = clients[i];
        this.processesPerClient = processesPerClient;
        this.interval = interval;
        this.duration = duration;
        this.remotePath = remotePath;
        this.remoteUser = remoteUser;
        this.listenForDebugger = listenForDebugger;
        this.serverHeapSize = serverHeapSize;
        this.clientHeapSize = clientHeapSize;
        this.localmode = localmode;
        this.useProfile = useProfile;
        this.checkTransaction = checkTransaction;
        this.checkTables = checkTables;
        this.snapshotPath = snapshotPath;
        this.snapshotPrefix = snapshotPrefix;
        this.snapshotFrequency = snapshotFrequency;
        this.snapshotRetain = snapshotRetain;
        this.resultsDatabaseURL = resultsDatabaseURL;
        this.statsDatabaseURL = statsDatabaseURL;
        this.statsTag = statsTag;
        this.applicationName = applicationName;
        this.subApplicationName = subApplicationName;
        
        this.compileBenchmark = compileBenchmark;
        this.compileOnly = compileOnly;
        this.useCatalogHosts = useCatalogHosts;
        this.noDataLoad = noDataLoad;
        this.workloadTrace = workloadTrace;
        this.profileSiteIds = profileSiteIds;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HOSTS:");
        for (String host : hosts)
            sb.append(" ").append(host);
        sb.append("\n");
        sb.append("SITES PER HOST: ").append(sitesPerHost).append("\n");
        sb.append("K-FACTOR: ").append(k_factor).append("\n");
        sb.append("CLIENTS:");
        for (String client : clients)
            sb.append(" ").append(client);
        sb.append("\n");

        return sb.toString();
    }
}

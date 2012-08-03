#!/usr/bin/env python
# -*- coding: utf-8 -*-
# -----------------------------------------------------------------------
# Copyright (C) 2012 by H-Store Project
# Brown University
# Massachusetts Institute of Technology
# Yale University
# 
# http://hstore.cs.brown.edu/ 
#
# Permission is hereby granted, free of charge, to any person obtaining
# a copy of this software and associated documentation files (the
# "Software"), to deal in the Software without restriction, including
# without limitation the rights to use, copy, modify, merge, publish,
# distribute, sublicense, and/or sell copies of the Software, and to
# permit persons to whom the Software is furnished to do so, subject to
# the following conditions:
#
# The above copyright notice and this permission notice shall be
# included in all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
# IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
# OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
# ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
# OTHER DEALINGS IN THE SOFTWARE.
# -----------------------------------------------------------------------
from __future__ import with_statement

import os
import sys
import glob
import re
import json
import logging
import getopt
import string
import math
import types
from datetime import datetime
from pprint import pprint, pformat
from types import *

## H-Store Third-Party Libraries
realpath = os.path.realpath(__file__)
basedir = os.path.dirname(realpath)
if not os.path.exists(realpath):
    cwd = os.getcwd()
    basename = os.path.basename(realpath)
    if os.path.exists(os.path.join(cwd, basename)):
        basedir = cwd
sys.path.append(os.path.realpath(os.path.join(basedir, "../../tools")))
sys.path.append(os.path.realpath(os.path.join(basedir, "../../third_party/python")))

import hstore
import hstore.codespeed
# This has all the functions we can use to invoke experiments on EC2
import hstore.fabfile

import argparse
from fabric.api import *
from fabric.network import *
from fabric.contrib.files import *

## ==============================================
## LOGGING CONFIGURATION
## ==============================================

LOG = logging.getLogger(__name__)
LOG_handler = logging.StreamHandler()
LOG_formatter = logging.Formatter(fmt='%(asctime)s [%(funcName)s:%(lineno)03d] %(levelname)-5s: %(message)s',
                                  datefmt='%m-%d-%Y %H:%M:%S')
LOG_handler.setFormatter(LOG_formatter)
LOG.addHandler(LOG_handler)
LOG.setLevel(logging.INFO)

## ==============================================
## CONFIGURATION PARAMETERS
## ==============================================

OPT_BASE_BLOCKING_CONCURRENT = 1
OPT_BASE_TXNRATE_PER_PARTITION = 1000
OPT_BASE_TXNRATE = 1000
OPT_BASE_CLIENT_COUNT = 1
OPT_BASE_CLIENT_THREADS_PER_HOST = 100
OPT_BASE_SCALE_FACTOR = float(1.0)
OPT_BASE_PARTITIONS_PER_SITE = 7
DEFAULT_OPTIONS = {
    "hstore.git_branch": "strangelove"
}

DEBUG_OPTIONS = [
    "site.exec_profiling",
    #"site.txn_profiling",
    "site.pool_profiling",
    #"site.planner_profiling",
    "site.status_show_txn_info",
    "site.status_show_executor_info",
    #"client.output_basepartitions",
]

BASE_SETTINGS = {
    "ec2.site_type":                    "m2.4xlarge",
    "ec2.client_type":                  "c1.xlarge",
    #"ec2.site_type":                    "m2.4xlarge",
    #"ec2.client_type":                  "m1.large",
    #"ec2.site_type":                    "m1.xlarge",
    
    "ec2.change_type":                  True,
    
    "client.blocking":                  False,
    "client.blocking_concurrent":       OPT_BASE_BLOCKING_CONCURRENT,
    "client.txnrate":                   OPT_BASE_TXNRATE,
    "client.count":                     OPT_BASE_CLIENT_COUNT,
    "client.threads_per_host":          OPT_BASE_CLIENT_THREADS_PER_HOST,
    "client.interval":                  10000,
    "client.skewfactor":                -1,
    "client.duration":                  120000,
    "client.warmup":                    60000,
    "client.scalefactor":               OPT_BASE_SCALE_FACTOR,
    "client.txn_hints":                 True,
    "client.throttle_backoff":          50,
    "client.memory":                    6000,
    "client.output_basepartitions":     False,
    
    "site.log_backup":                                  False,
    "site.status_show_thread_info":                     False,
    "site.status_show_executor_info":                   False,
    "site.status_interval":                             20000,
    "site.txn_incoming_delay":                          1,
    "site.coordinator_init_thread":                     False,
    "site.coordinator_finish_thread":                   False,
    "site.txn_restart_limit":                           5,
    "site.txn_restart_limit_sysproc":                   100,
    
    "site.exec_force_singlepartitioned":                True,
    "site.exec_mispredict_crash":                       False,
    
    "site.sites_per_host":                              1,
    "hstore.partitions_per_site":                       OPT_BASE_PARTITIONS_PER_SITE,
    "site.num_hosts_round_robin":                       None,
    "site.memory":                                      61440,
    "site.queue_incoming_max_per_partition":            150,
    "site.queue_incoming_release_factor":               0.90,
    "site.queue_incoming_increase":                     10,
    "site.queue_incoming_throttle":                     False,
    "site.queue_dtxn_max_per_partition":                1000,
    "site.queue_dtxn_release_factor":                   0.90,
    "site.queue_dtxn_increase":                         0,
    "site.queue_dtxn_throttle":                         False,
    
    "site.exec_postprocessing_thread":                  False,
    "site.pool_localtxnstate_idle":                     20000,
    "site.pool_batchplan_idle":                         10000,
    "site.exec_db2_redirects":                          False,
    "site.cpu_affinity":                                True,
    "site.cpu_affinity_one_partition_per_core":         True,
}

EXPERIMENT_SETTINGS = {
    "motivation": {
        "benchmark.neworder_skew_warehouse": False,
        "benchmark.neworder_multip":         True,
        "benchmark.warehouse_debug":         False,
        "benchmark.noop":                    False,
    },
}

## ==============================================
## updateEnv
## ==============================================
def updateEnv(env, benchmark, exp_type):
    global OPT_BASE_TXNRATE_PER_PARTITION
  
    env["client.scalefactor"] = float(BASE_SETTINGS["client.scalefactor"] * env["hstore.partitions"])
    
    for k,v in BASE_SETTINGS.iteritems():
        env[k] = v
    ## FOR
  
  
    ## ----------------------------------------------
    ## MOTIVATION
    ## ----------------------------------------------
    if exp_type == "motivation":
        # Nothing for now...
        pass

## DEF

## ==============================================
## main
## ==============================================
if __name__ == '__main__':
    HSTORE_PARAMS = hstore.getAllParameters()
    
    aparser = argparse.ArgumentParser(description='H-Store Experiment Runner')
    
    aparser.add_argument('--benchmark', choices=hstore.getBenchmarks(), nargs='+',
                         help='Target benchmarks')
    
    # Cluster Parameters
    agroup = aparser.add_argument_group('EC2 Cluster Control Parameters')
    agroup.add_argument("--partitions", type=int, default=4, metavar='P', nargs='+',)
    agroup.add_argument("--start-cluster", action='store_true')
    agroup.add_argument("--fast-start", action='store_true')
    agroup.add_argument("--force-reboot", action='store_true')
    agroup.add_argument("--single-client", action='store_true')
    agroup.add_argument("--no-execute", action='store_true', help='Do no execute any experimetns after starting cluster')
    agroup.add_argument("--no-compile", action='store_true', help='Disable compiling before running benchmark')
    agroup.add_argument("--no-update", action='store_true', help='Disable synching git repository')
    agroup.add_argument("--no-jar", action='store_true', help='Disable constructing benchmark jar')
    agroup.add_argument("--no-conf", action='store_true', help='Disable updating HStoreConf properties file')
    agroup.add_argument("--no-sync", action='store_true', help='Disable synching time between nodes')
    agroup.add_argument("--no-json", action='store_true', help='Disable JSON output results')
    
    ## Experiment Parameters
    agroup = aparser.add_argument_group('Experiment Parameters')
    agroup.add_argument("--exp-type", type=str, choices=EXPERIMENT_SETTINGS.keys(), default=EXPERIMENT_SETTINGS.keys()[0])
    agroup.add_argument("--exp-trials", type=int, default=3, metavar='N')
    agroup.add_argument("--exp-attempts", type=int, default=3, metavar='N')
    
    ## Benchmark Parameters
    agroup = aparser.add_argument_group('Benchmark Configuration Parameters')
    agroup.add_argument("--multiply-scalefactor", action='store_true')
    agroup.add_argument("--stop-on-error", action='store_true', default=True)
    agroup.add_argument("--retry-on-zero", action='store_true', default=True)
    agroup.add_argument("--clear-logs", action='store_true')
    agroup.add_argument("--workload-trace", action='store_true')
    
    ## Codespeed Parameters
    agroup = aparser.add_argument_group('Codespeed Parameters')
    agroup.add_argument("--codespeed-url", type=str, metavar="URL")
    agroup.add_argument("--codespeed-benchmark", type=str, metavar="BENCHMARK")
    agroup.add_argument("--codespeed-revision", type=str, metavar="REV")
    agroup.add_argument("--codespeed-lastrevision", type=str, metavar="REV")
    agroup.add_argument("--codespeed-branch", type=str, metavar="BRANCH")
    
    # And our Boto environment keys
    agroup = aparser.add_argument_group('Boto Parameters')
    for key in sorted(hstore.fabfile.ENV_DEFAULT):
        keyPrefix = key.split(".")[0]
        if key not in BASE_SETTINGS and keyPrefix in [ "ec2", "hstore" ]:
            confType = type(hstore.fabfile.ENV_DEFAULT[key])
            if key in DEFAULT_OPTIONS and not DEFAULT_OPTIONS[key] is None:
                confDefault = DEFAULT_OPTIONS[key]
            else:
                confDefault = hstore.fabfile.ENV_DEFAULT[key]
            
            metavar = key.split(".")[-1].upper()
            agroup.add_argument("--"+key, type=confType, default=confDefault, metavar=metavar)
    ## FOR
    
    # Load in all of the possible parameters from our 'build-common.xml' file
    hstoreConfGroups = { }
    for key in sorted(HSTORE_PARAMS):
        keyPrefix = key.split(".")[0]
        if not keyPrefix in hstoreConfGroups:
            groupName = 'HStoreConf %s Parameters' % keyPrefix.title()
            hstoreConfGroups[keyPrefix] = aparser.add_argument_group(groupName)
        
        confType = str
        confDefault = None
        if key in BASE_SETTINGS and not BASE_SETTINGS[key] is None:
            confType = type(BASE_SETTINGS[key])
            confDefault = BASE_SETTINGS[key]
            
        metavar = key.split(".")[-1].upper()
        hstoreConfGroups[keyPrefix].add_argument("--"+key, type=confType, default=confDefault, metavar=metavar)
    ## FOR
    
    # Debug Parameters
    agroup = aparser.add_argument_group('Debug Parameters')
    agroup.add_argument("--debug", action='store_true')
    agroup.add_argument("--debug-hstore", action='store_true')

    args = vars(aparser.parse_args())
    
    ## ----------------------------------------------
    ## ARGUMENT PROCESSING 
    ## ----------------------------------------------
    
    for key in env.keys():
        if key in args and not args[key] is None:
            env[key] = args[key]
    ## FOR
    for key in args:
        if args[key] is None: continue
        if (key in BASE_SETTINGS or key in HSTORE_PARAMS):
            BASE_SETTINGS[key] = args[key]
    ## FOR
    
    if args['debug']:
        LOG.setLevel(logging.DEBUG)
        hstore.fabfile.LOG.setLevel(logging.DEBUG)
    if args['debug_hstore']:
        for param in DEBUG_OPTIONS:
            BASE_SETTINGS[param] = True
    if args['fast_start']:
        LOG.info("Enabling fast startup")
        for key in ['compile', 'update', 'conf', 'jar', 'sync']:
            args["no_%s" % key] = True
    if args['single_client']:
        LOG.info("Enabling single-client debug mode!")
        for key in ['count', 'threads_per_host', 'txnrate']:
            BASE_SETTINGS["client.%s" % key] = 1
    
    # If we get two consecutive intervals with zero results, then stop the benchmark
    if args['retry_on_zero']:
        env["hstore.exec_prefix"] += " -Dkillonzero=true"
    
    # Update Fabric env
    exp_opts = dict(BASE_SETTINGS.items() + EXPERIMENT_SETTINGS[args['exp_type']].items())
    assert exp_opts
    conf_remove = set()
    for key,val in exp_opts.items():
        if val == None: 
            LOG.debug("Parameter to Remove: %s" % key)
            conf_remove.add(key)
            del exp_opts[key]
            assert not key in exp_opts
        elif type(val) != types.FunctionType:
            env[key] = val
    ## FOR
    # Figure out what keys we need to remove to ensure that one experiment
    # doesn't contaminate another
    for other_type in EXPERIMENT_SETTINGS.keys():
        if other_type != args['exp_type']:
            for key in EXPERIMENT_SETTINGS[other_type].keys():
                if not key in exp_opts: conf_remove.add(key)
            ## FOR
        ## IF
    ## FOR
    LOG.debug("Configuration Parameters to Remove:\n" + pformat(conf_remove))
    
    # BenchmarkController Parameters
    controllerParams = { }
    
    needUpdate = (args['no_update'] == False)
    needSync = (args['no_sync'] == False)
    needCompile = (args['no_compile'] == False)
    needClearLogs = (args['clear_logs'] == False)
    forceStop = False
    origScaleFactor = BASE_SETTINGS['client.scalefactor']
    for benchmark in args['benchmark']: # XXX
        final_results = { }
        totalAttempts = args['exp_trials'] * args['exp_attempts']
        stop = False
        
        for partitions in map(int, args["partitions"]):
            LOG.info("%s - %s - %d Partitions" % (args['exp_type'].upper(), benchmark.upper(), partitions))
            env["hstore.partitions"] = partitions
            all_results = [ ]
                
            # Increase the client.scalefactor based on the number of partitions
            if args['multiply_scalefactor']:
                BASE_SETTINGS['client.scalefactor'] = int(origScaleFactor * partitions/2)
                
            if args['start_cluster']:
                LOG.info("Starting cluster for experiments [noExecute=%s]" % args['no_execute'])
                hstore.fabfile.start_cluster(updateSync=needSync)
                if args['no_execute']: sys.exit(0)
            ## IF
            
            ## Synchronize Instance Times
            if needSync: hstore.fabfile.sync_time()
            needSync = False
                
            ## Clear Log Files
            if needClearLogs: hstore.fabfile.clear_logs()
            needClearLogs = False
                
            client_inst = hstore.fabfile.__getRunningClientInstances__()[0]
            LOG.debug("Client Instance: " + client_inst.public_dns_name)
                
            updateJar = (args['no_jar'] == False)
            updateEnv(env, benchmark, args['exp_type'])
            LOG.debug("Parameters:\n%s" % pformat(env))
            conf_remove = conf_remove - set(env.keys())
            
            results = [ ]
            attempts = 0
            updateConf = (args['no_conf'] == False)
            while len(results) < args['exp_trials'] and attempts < totalAttempts and stop == False:
                ## Only compile for the very first invocation
                if needCompile:
                    if env["hstore.exec_prefix"].find("compile") == -1:
                        env["hstore.exec_prefix"] += " compile"
                else:
                    env["hstore.exec_prefix"] = env["hstore.exec_prefix"].replace("compile", "")
                    
                needCompile = False
                attempts += 1
                LOG.info("Executing %s Trial #%d/%d [attempt=%d/%d]" % (\
                            benchmark.upper(),
                            len(results),
                            args['exp_trials'],
                            attempts,
                            totalAttempts
                ))
                try:
                    with settings(host_string=client_inst.public_dns_name):
                        output, workloads = hstore.fabfile.exec_benchmark(
                                                project=benchmark, \
                                                removals=conf_remove, \
                                                json=(args['no_json'] == False), \
                                                trace=args['workload_trace'], \
                                                updateJar=updateJar, \
                                                updateConf=updateConf, \
                                                updateRepo=needUpdate, \
                                                updateLog4j=needUpdate, \
                                                extraParams=controllerParams)
                        if args['no_json'] == False:
                            data = hstore.parseJSONResults(output)
                            for key in [ 'TOTALTXNPERSECOND', 'TXNPERSECOND' ]:
                                if key in data:
                                    txnrate = float(data[key])
                                    break
                            ## FOR
                            minTxnRate = float(data["MINTXNPERSECOND"]) if "MINTXNPERSECOND" in data else None
                            maxTxnRate = float(data["MAXTXNPERSECOND"]) if "MAXTXNPERSECOND" in data else None
                            stddevTxnRate = float(data["STDDEVTXNPERSECOND"]) if "STDDEVTXNPERSECOND" in data else None
                            
                            if int(txnrate) == 0: pass
                            results.append(txnrate)
                            if args['workload_trace'] and workloads != None:
                                for f in workloads:
                                    LOG.info("Workload File: %s" % f)
                                ## FOR
                            ## IF
                            LOG.info("Throughput: %.2f" % txnrate)
                            
                            if args["codespeed_url"] and txnrate > 0:
                                upload_url = args["codespeed_url"][0]
                                
                                if args["codespeed_revision"]:
                                    # FIXME
                                    last_changed_rev = args["codespeed_revision"][0]
                                    last_changed_rev, last_changed_date = svnInfo(env["hstore.svn"], last_changed_rev)
                                else:
                                    last_changed_rev, last_changed_date = hstore.fabfile.get_version()
                                print "last_changed_rev:", last_changed_rev
                                print "last_changed_date:", last_changed_date
                                
                                codespeedBenchmark = benchmark
                                if not args["codespeed_benchmark"] is None:
                                    codespeedBenchmark = args["codespeed_benchmark"]
                                codespeedBranch = env["hstore.git_branch"]
                                if not args["codespeed_branch"] is None:
                                    codespeedBranch = args["codespeed_branch"]
                                
                                LOG.info("Uploading %s results to CODESPEED at %s" % (benchmark, upload_url))
                                result = hstore.codespeed.Result(
                                            commitid=last_changed_rev,
                                            branch=codespeedBranch,
                                            benchmark=codespeedBenchmark,
                                            project="H-Store",
                                            num_partitions=partitions,
                                            environment="ec2",
                                            result_value=txnrate,
                                            revision_date=last_changed_date,
                                            result_date=datetime.now(),
                                            min_result=minTxnRate,
                                            max_result=maxTxnRate,
                                            std_dev=stddevTxnRate
                                )
                                result.upload(upload_url)
                            ## IF
                        ## IF
                    ## WITH
                except KeyboardInterrupt:
                    stop = True
                    forceStop = True
                    break
                except SystemExit:
                    LOG.warn("Failed to complete trial succesfully")
                    if args['stop_on_error']:
                        stop = True
                        forceStop = True
                        break
                    pass
                except:
                    LOG.warn("Failed to complete trial succesfully")
                    stop = True
                    raise
                    break
                finally:
                    needUpdate = False
                    updateJar = False
                    updateConf = False
                ## TRY
                
            ## FOR (TRIALS)
            if results: final_results[partitions] = (benchmark, results, attempts)
            if stop or forceStop or (attempts == totalAttempts): break
            stop = False
        ## FOR (PARTITIONS)
        if forceStop: break
        stop = False
    ## FOR (BENCHMARKS)
    
    LOG.info("Disconnecting and dumping results")
    try:
        disconnect_all()
    finally:
        for partitions in sorted(final_results.keys()):
            all_results = final_results[partitions]
            print "%s - Partitions %d" % (args['exp_type'].upper(), partitions)
            for benchmark, results, attempts in all_results:
                print "   %s [Attempts:%d/%d]" % (benchmark.upper(), attempts, totalAttempts)
                for trial in range(len(results)):
                    print "      TRIAL #%d: %.4f" % (trial, results[trial])
                ## FOR
            ## FOR
            print
        ## FOR
    ## TRY
## MAIN

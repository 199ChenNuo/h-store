#!/usr/bin/env python

import os
import sys
import shutil
import subprocess
import logging

## H-Store Third-Party Libraries
realpath = os.path.realpath(__file__)
basedir = os.path.dirname(realpath)
if not os.path.exists(realpath):
    cwd = os.getcwd()
    basename = os.path.basename(realpath)
    if os.path.exists(os.path.join(cwd, basename)):
        basedir = cwd
sys.path.append(os.path.realpath(os.path.join(basedir, "../third_party/python")))
import argparse

LOG = logging.getLogger(__name__)
LOG_handler = logging.StreamHandler()
LOG_formatter = logging.Formatter(fmt='%(asctime)s [%(funcName)s:%(lineno)03d] %(levelname)-5s: %(message)s',
                                  datefmt='%m-%d-%Y %H:%M:%S')
LOG_handler.setFormatter(LOG_formatter)
LOG.addHandler(LOG_handler)
LOG.setLevel(logging.INFO)

## ==============================================
## DEFAULT CONFIGURATION
## ==============================================
GIT_REPO = "git://github.com/apavlo/h-store-files.git"
GIT_BRANCH = "master"

## ==============================================
## main
## ==============================================
if __name__ == '__main__':
    aparser = argparse.ArgumentParser(description='Install H-Store Research Files')
    aparser.add_argument('path', help='Installation path')
    aparser.add_argument('--git-repo', default=GIT_REPO, help='Git repository')
    aparser.add_argument('--git-branch', default=GIT_BRANCH, help='Git branch')
    aparser.add_argument('--overwrite', action='store_true', help='Overwrite existing directory')
    aparser.add_argument('--copy', default=None, help='Copy from existing local copy')
    args = vars(aparser.parse_args())

    cmd = "git clone --branch %(git_branch)s %(git_repo)s %(path)s" % args
    
    if os.path.exists(args['path']):
        if not args['overwrite']:
            LOG.info("Installation directory '%s' already exists. Not overwriting" % args['path'])
            sys.exit(0)
        LOG.warn("Deleting directory '%s' and reinstalling" % args['path'])
        shutil.rmtree(args['path'])
    if args['copy']:
        if not os.path.exists(args['copy']):
            LOG.warn("Unable to copy from local cache. The directory '%s' does not exist" % args['copy'])
        else:
            cmd = "cp -rvl %(copy)s %(path)s" % args

    # Bombs away!
    LOG.info(cmd)
    subprocess.check_call(cmd, shell=True)
## MAIN
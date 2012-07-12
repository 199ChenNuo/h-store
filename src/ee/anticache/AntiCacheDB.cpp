/* Copyright (C) 2012 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
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
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include "anticache/AntiCacheDB.h"
#include "common/debuglog.h"
#include "common/UnknownBlockAccessException.h"
#include "common/FatalException.hpp"

using namespace std;

namespace voltdb {

AntiCacheBlock::AntiCacheBlock(uint16_t block_id, Dbt value) :
        m_blockId(block_id),
        m_value(value) {
    // They see me rollin'
    // They hatin'
}

AntiCacheBlock::~AntiCacheBlock() {
    // we asked BDB to allocate memory for data dynamically, so we must delete
    delete [] (char*)m_value.get_data(); 
}
    
AntiCacheDB::AntiCacheDB(ExecutorContext *ctx, std::string db_dir) :
    m_executorContext(ctx),
    m_dbDir(db_dir),
    m_nextBlockId(0) {
        
    try {
        // allocate and initialize Berkeley DB database env
        m_dbEnv = new DbEnv(0); 
        m_dbEnv->open(m_dbDir.c_str(), DB_CREATE | DB_INIT_MPOOL, 0); 
        
        // allocate and initialize new Berkeley DB instance
        m_db = new Db(m_dbEnv, 0); 
        m_db->open(NULL, "anticache.db", NULL, DB_HASH, DB_CREATE, 0); 
        
    } catch (DbException &e) {
        VOLT_ERROR("Anti-Cache initialization error: %s", e.what());
        throwFatalException("Failed to initialize anti-cache database in directory %s: %s",
                            db_dir.c_str(), e.what());
    }
}

AntiCacheDB::~AntiCacheDB() {
    // NOTE: You have to close the database first before closing the environment
    try {
        m_db->close(0);
        delete m_db;
    } catch (DbException &e) {
        VOLT_ERROR("Anti-Cache database closing error: %s", e.what());
        throwFatalException("Failed to close anti-cache database: %s", e.what());
    }
    
    try {
        m_dbEnv->close(0);
        delete m_dbEnv;
    } catch (DbException &e) {
        VOLT_ERROR("Anti-Cache environment closing error: %s", e.what());
        throwFatalException("Failed to close anti-cache database environment: %s", e.what());
    }
}

void AntiCacheDB::writeBlock(uint16_t block_id, const char* data, const long size) {
    Dbt key; 
    key.set_data(&block_id);
    key.set_size(sizeof(uint16_t));
    
    Dbt value;
    value.set_data(const_cast<char*>(data));
    value.set_size(static_cast<int32_t>(size)); 
    
    VOLT_INFO("Writing out a block #%d to anti-cache database [size=%ld]",
               block_id, size);
    m_db->put(NULL, &key, &value, 0);
}

AntiCacheBlock AntiCacheDB::readBlock(std::string tableName, uint16_t block_id) {
    Dbt key;
    key.set_data(&block_id);
    key.set_size(sizeof(uint16_t));

    Dbt value;
    value.set_flags(DB_DBT_MALLOC);
    
    int ret_value = m_db->get(NULL, &key, &value, 0);
    if (ret_value != 0) {
        VOLT_ERROR("Invalid anti-cache blockId '%d' for table '%s'", block_id, tableName.c_str());
        throw UnknownBlockAccessException(tableName, block_id);
    }
    assert(value.get_data() != NULL);
    
    AntiCacheBlock block(block_id, value);
    return (block);
}
    
}


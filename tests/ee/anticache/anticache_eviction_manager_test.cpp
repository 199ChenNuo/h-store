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

#include "harness.h"
#include "common/TupleSchema.h"
#include "common/types.h"
#include "common/NValue.hpp"
#include "common/ValueFactory.hpp"
#include "common/ValuePeeker.hpp"
#include "execution/VoltDBEngine.h"
#include "storage/persistenttable.h"
#include "storage/tablefactory.h"
#include "storage/tableutil.h"
#include "indexes/tableindex.h"
#include "storage/tableiterator.h"
#include "storage/CopyOnWriteIterator.h"
#include "common/DefaultTupleSerializer.h"
#include <vector>
#include <string>
#include <stdint.h>
#include <set>
#include "boost/scoped_ptr.hpp"

#include "anticache/AntiCacheDB.h"

using namespace std;
using namespace voltdb;
using stupidunit::ChTempDir;

/**
 * AntiCacheDB Tests
 */
class AntiCacheEvictionManagerTest : public Test {
public:
    AntiCacheEvictionManagerTest() {
        m_tuplesInserted = 0;
        m_tuplesUpdated = 0;
        m_tuplesDeleted = 0;

        m_engine = new voltdb::VoltDBEngine();
        m_engine->initialize(1,1, 0, 0, "");
        
        m_columnNames.push_back("1");
        m_columnNames.push_back("2");
        
        m_tableSchemaTypes.push_back(voltdb::VALUE_TYPE_INTEGER);
        m_primaryKeyIndexSchemaTypes.push_back(voltdb::VALUE_TYPE_INTEGER);
        m_tableSchemaTypes.push_back(voltdb::VALUE_TYPE_INTEGER);
        
        m_tableSchemaColumnSizes.push_back(NValue::getTupleStorageSize(voltdb::VALUE_TYPE_INTEGER));
        m_primaryKeyIndexSchemaColumnSizes.push_back(voltdb::VALUE_TYPE_INTEGER);
        m_tableSchemaColumnSizes.push_back(NValue::getTupleStorageSize(voltdb::VALUE_TYPE_INTEGER));
        m_primaryKeyIndexSchemaColumnSizes.push_back(voltdb::VALUE_TYPE_INTEGER);
        
        m_tableSchemaAllowNull.push_back(false);
        m_primaryKeyIndexSchemaAllowNull.push_back(false);
        m_tableSchemaAllowNull.push_back(false);
        m_primaryKeyIndexSchemaAllowNull.push_back(false);
        
        m_primaryKeyIndexColumns.push_back(0);
        
        m_undoToken = 0;
    };
    
    ~AntiCacheEvictionManagerTest() {
        delete m_engine;
        delete m_table;
        voltdb::TupleSchema::freeTupleSchema(m_primaryKeyIndexSchema);
    }
    
    
    void initTable(bool allowInlineStrings) {
        m_tableSchema = voltdb::TupleSchema::createTupleSchema(m_tableSchemaTypes,
                                                               m_tableSchemaColumnSizes,
                                                               m_tableSchemaAllowNull,
                                                               allowInlineStrings);
        
        m_primaryKeyIndexSchema = voltdb::TupleSchema::createTupleSchema(m_primaryKeyIndexSchemaTypes,
                                                                         m_primaryKeyIndexSchemaColumnSizes,
                                                                         m_primaryKeyIndexSchemaAllowNull,
                                                                         allowInlineStrings);
        voltdb::TableIndexScheme indexScheme = voltdb::TableIndexScheme("primaryKeyIndex",
                                                                        voltdb::BALANCED_TREE_INDEX,
                                                                        m_primaryKeyIndexColumns,
                                                                        m_primaryKeyIndexSchemaTypes,
                                                                        true, false, m_tableSchema);
        indexScheme.keySchema = m_primaryKeyIndexSchema;
        std::vector<voltdb::TableIndexScheme> indexes;
        
        m_table = dynamic_cast<voltdb::PersistentTable*>(voltdb::TableFactory::getPersistentTable
                                                         (0, m_engine->getExecutorContext(), "Foo",
                                                          m_tableSchema, &m_columnNames[0], indexScheme, indexes, 0,
                                                          false, false));
    }
    
    void addRandomUniqueTuples(Table *table, int numTuples) {
        TableTuple tuple = table->tempTuple();
        for (int ii = 0; ii < numTuples; ii++) {
            tuple.setNValue(0, ValueFactory::getIntegerValue(m_primaryKeyIndex++));
            tuple.setNValue(1, ValueFactory::getIntegerValue(rand()));
            table->insertTuple(tuple);
        }
    }
    
    voltdb::VoltDBEngine *m_engine;
    voltdb::TupleSchema *m_tableSchema;
    voltdb::TupleSchema *m_primaryKeyIndexSchema;
    voltdb::PersistentTable *m_table;
    std::vector<std::string> m_columnNames;
    std::vector<voltdb::ValueType> m_tableSchemaTypes;
    std::vector<int32_t> m_tableSchemaColumnSizes;
    std::vector<bool> m_tableSchemaAllowNull;
    std::vector<voltdb::ValueType> m_primaryKeyIndexSchemaTypes;
    std::vector<int32_t> m_primaryKeyIndexSchemaColumnSizes;
    std::vector<bool> m_primaryKeyIndexSchemaAllowNull;
    std::vector<int> m_primaryKeyIndexColumns;
    
    int32_t m_tuplesInserted;
    int32_t m_tuplesUpdated;
    int32_t m_tuplesDeleted;
    
};

TEST_F(AntiCacheEvictionManagerTest, GetTupleID)
{
    initTable(true); 
    
    TableTuple tuple = table->tempTuple();
    
    tuple.setNValue(0, ValueFactory::getIntegerValue(m_primaryKeyIndex++));
    tuple.setNValue(1, ValueFactory::getIntegerValue(rand()));
    table->insertTuple(tuple);
    
    // get the tuple that was just inserted
    tuple = lookupTuple(tuple); 
    
    int tuple_id = m_table->getTupleID(tuple.address()); 

    ASSERT_NE(tuple_id, -1); 
}

TEST_F(AntiCacheEvictionManagerTest, InsertTuple)
{
    initTable(true); 
    
    TableTuple tuple = table->tempTuple();
    
    tuple.setNValue(0, ValueFactory::getIntegerValue(m_primaryKeyIndex++));
    tuple.setNValue(1, ValueFactory::getIntegerValue(rand()));
    table->insertTuple(tuple);

}


int main() {
    return TestSuite::globalInstance()->runAll();
}


















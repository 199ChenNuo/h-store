/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
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

#include "harness.h"
#include "common/TupleSchema.h"
#include "common/types.h"
#include "common/NValue.hpp"
#include "common/ValueFactory.hpp"
#include <vector>
#include <string>
#include <stdint.h>

using namespace voltdb;

class TupleSchemaTest : public Test {
public:
    TupleSchemaTest() {
        int num_columns = 10;
        
        for (int i = 0; i < num_columns; i++) {
            // COlUMN TYPE
            int col_type = (i % 2 == 0 ? VALUE_TYPE_BIGINT : VALUE_TYPE_INTEGER);
            m_tableSchemaTypes.push_back(col_type);
            m_tableSchemaColumnSizes.push_back(NValue::getTupleStorageSize(col_type));
            
            // COLUMN NAME
            std::ostringstream stream;
            stream << "col" << i;
            m_columnNames.push_back(stream.str());
            
            // PRIMARY KEY
            if (i <= 3) {
                m_tableSchemaAllowNull.push_back(false);
                m_primaryKeyIndexSchemaTypes.push_back(col_type);
                m_primaryKeyIndexColumns.push_back(i);
                m_primaryKeyIndexSchemaAllowNull.push_back(false);
                m_primaryKeyIndexSchemaColumnSizes.push_back(NValue::getTupleStorageSize(col_type));
            }
            // NON-PRIMARY KEY
            else {
                m_tableSchemaAllowNull.push_back(true);
            }
        } // FOR
    }

    ~TupleSchemaTest() {
        TupleSchema::freeTupleSchema(m_primaryKeyIndexSchema);
    }

    void initTable(bool allowInlineStrings) {
        m_tableSchema = TupleSchema::createTupleSchema(
                m_tableSchemaTypes,
                m_tableSchemaColumnSizes,
                m_tableSchemaAllowNull,
                allowInlineStrings);

        m_primaryKeyIndexSchema = TupleSchema::createTupleSchema(
                m_primaryKeyIndexSchemaTypes,
                m_primaryKeyIndexSchemaColumnSizes,
                m_primaryKeyIndexSchemaAllowNull,
                allowInlineStrings);

        TableIndexScheme indexScheme = TableIndexScheme(
                "primaryKeyIndex",
                BALANCED_TREE_INDEX,
                m_primaryKeyIndexColumns,
                m_primaryKeyIndexSchemaTypes,
                true, false, m_tableSchema);
        indexScheme.keySchema = m_primaryKeyIndexSchema;

    }



    TupleSchema *m_tableSchema;
    TupleSchema *m_primaryKeyIndexSchema;
    std::vector<std::string> m_columnNames;
    std::vector<ValueType> m_tableSchemaTypes;
    std::vector<int32_t> m_tableSchemaColumnSizes;
    std::vector<bool> m_tableSchemaAllowNull;
    std::vector<ValueType> m_primaryKeyIndexSchemaTypes;
    std::vector<int32_t> m_primaryKeyIndexSchemaColumnSizes;
    std::vector<bool> m_primaryKeyIndexSchemaAllowNull;
    std::vector<int> m_primaryKeyIndexColumns;
};

TEST_F(TupleSchemaTest, CreateEvictedTupleSchema) {
    initTable(true);
    fprintf(stderr, "TABLE SCHEMA\n%s\n", m_tableSchema->debug().c_str());
    
}

int main() {
    return TestSuite::globalInstance()->runAll();
}

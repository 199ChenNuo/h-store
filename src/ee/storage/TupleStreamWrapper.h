/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef TUPLESTREAMWRAPPER_H_
#define TUPLESTREAMWRAPPER_H_

#include "StreamBlock.h"

#include "common/ids.h"
#include "common/tabletuple.h"
#include "common/executorcontext.hpp"
#include "common/FatalException.hpp"

#include <deque>

namespace voltdb {

class Topend;
const int EL_BUFFER_SIZE = /* 1024; */ 2 * 1024 * 1024;

class TupleStreamWrapper {
public:
    enum Type { INSERT, DELETE };

    TupleStreamWrapper(CatalogId partitionId, CatalogId siteId,
                       CatalogId tableId, int64_t createTime);

    ~TupleStreamWrapper() {
        cleanupManagedBuffers(NULL);
    }

    /**
     * Drop and release all claimed buffers. Intended for use at
     * shutdown to achieve full memory deallocation for valgrind.
     */
    void cleanupManagedBuffers(Topend *);

    /**
     * Configure the buffer size requested from JNI pool.
     * This allows testcases to use significantly smaller buffers
     * to test buffer rollover.
     */
    void setDefaultCapacity(size_t capacity);

    /** Read the total bytes used over the life of the stream */
    size_t bytesUsed() {
        return m_uso;
    }

    /** truncate stream back to mark */
    void rollbackTo(size_t mark);

    /** age out committed data */
    void periodicFlush(int64_t timeInMillis,
                       int64_t lastTickTime,
                       int64_t lastComittedTxnId,
                       int64_t currentTxnId);

    /** write a tuple to the stream */
    size_t appendTuple(int64_t lastCommittedTxnId,
                       int64_t txnId,
                       int64_t seqNo,
                       int64_t timestamp,
                       TableTuple &tuple,
                       TupleStreamWrapper::Type type);

    /**
     * Poll the stream for a buffer of committed bytes.
     */
    StreamBlock* getCommittedEltBytes();

    /**
     * Release data up to (not including) releaseOffset
     *
     * @return true if the release was valid, false if not
     */
    bool releaseEltBytes(int64_t releaseOffset);

private:

    size_t computeOffsets(TableTuple &tuple,size_t *rowHeaderSz);
    void extendBufferChain(size_t minLength);
    void discardBlock(StreamBlock *sb);

    /** Send committed data to the top end */
    void commit(int64_t lastCommittedTxnId, int64_t txnId);

    // cached catalog values
    const CatalogId m_partitionId;
    const CatalogId m_siteId;
    const CatalogId m_tableId;

    /** timestamp of most recent flush() */
    int64_t m_lastFlush;

    /** size of buffer requested from the top-end */
    size_t m_defaultCapacity;

    /** Universal stream offset. Total bytes appended to this stream. */
    size_t m_uso;

    /** Current block */
    StreamBlock *m_currBlock;

    /** Fake block.  Sometimes we need to return no-progress state
        to the caller, which we can't do with an existing StreamBlock.
        However, the convention is that we have ownership of them,
        so we stuff it here
    */
    StreamBlock* m_fakeBlock;

    /** Blocks not yet polled by the top-end */
    std::deque<StreamBlock*> m_pendingBlocks;

    /** Free list of blocks */
    std::deque<StreamBlock*> m_freeBlocks;

    /** transaction id of the current (possibly uncommitted) transaction */
    int64_t m_openTransactionId;

    /** Universal stream offset when current transaction was opened */
    size_t m_openTransactionUso;

    /** last committed transaction id */
    int64_t m_committedTransactionId;

    /** current committed uso */
    size_t m_committedUso;

    /** The oldest USO that has not yet been returned to the EE on a poll */
    size_t m_firstUnpolledUso;
};

}

#endif

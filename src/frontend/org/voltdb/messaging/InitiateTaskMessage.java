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

package org.voltdb.messaging;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.voltdb.StoredProcedureInvocation;
import org.voltdb.utils.DBBPool;

/**
 * Message from an initiator to an execution site, instructing the
 * site to begin executing a stored procedure, coordinating other
 * execution sites if needed.
 *
 */
public class InitiateTaskMessage extends TransactionInfoBaseMessage {

    boolean m_isSinglePartition;
    StoredProcedureInvocation m_invocation;
    long m_lastSafeTxnID; // this is the largest txn acked by all partitions running the java for it
    int[] m_nonCoordinatorSites = null;

    /** Empty constructor for de-serialization */
    InitiateTaskMessage() {
        super();
    }

    public InitiateTaskMessage(long txnId, long clientHandle, StoredProcedureInvocation invocation) {
        this(-1, -1, txnId, clientHandle, false, false, invocation, -1);
    }
    
    public InitiateTaskMessage(int initiatorSiteId,
                        int coordinatorSiteId,
                        long txnId,
                        long clientHandle,
                        boolean isReadOnly,
                        boolean isSinglePartition,
                        StoredProcedureInvocation invocation,
                        long lastSafeTxnID) {
        super(initiatorSiteId, coordinatorSiteId, txnId, clientHandle, isReadOnly);
        m_isSinglePartition = isSinglePartition;
        m_invocation = invocation;
        m_invocation.buildParameterSet();
        m_lastSafeTxnID = lastSafeTxnID;
    }

    public void setNonCoordinatorSites(int[] siteIds) {
        m_nonCoordinatorSites = siteIds;
    }

    @Override
    public boolean isReadOnly() {
        return m_isReadOnly;
    }

    @Override
    public boolean isSinglePartition() {
        return m_isSinglePartition;
    }

    public String getStoredProcedureName() {
        assert(m_invocation != null);
        return m_invocation.getProcName();
    }

    public int getParameterCount() {
        assert(m_invocation != null);
        if (m_invocation.getParams() == null)
            return 0;
        return m_invocation.getParams().toArray().length;
    }

    public Object getParameter(int index) {
        assert(m_invocation != null);
        return m_invocation.getParams().toArray()[index];
    }

    public Object[] getParameters() {
        return m_invocation.getParams().toArray();
    }

    public int[] getNonCoordinatorSites() {
        return m_nonCoordinatorSites;
    }

    public long getLastSafeTxnId() {
        return m_lastSafeTxnID;
    }
    
    @Override
    protected void flattenToBuffer(final DBBPool pool) {
        // stupid lame flattening of the proc invocation
        FastSerializer fs = new FastSerializer();
        try {
            fs.writeObject(m_invocation);
        } catch (IOException e) {
            e.printStackTrace();
            assert(false);
        }
        ByteBuffer invocationBytes = fs.getBuffer();

        // size of MembershipNotice
        int msgsize = super.getMessageByteCount();
        msgsize += 1 + 2 + 8 + invocationBytes.remaining();
        if (m_nonCoordinatorSites != null) msgsize += m_nonCoordinatorSites.length * 4;

        if (m_buffer == null) {
            m_container = pool.acquire(msgsize + 1 + HEADER_SIZE);
            m_buffer = m_container.b;
        }
        setBufferSize(msgsize + 1, pool);

        m_buffer.position(HEADER_SIZE);
        m_buffer.put(INITIATE_TASK_ID);

        super.writeToBuffer();

        m_buffer.putLong(m_lastSafeTxnID);

        m_buffer.put(m_isSinglePartition ? (byte) 1 : (byte) 0);
        if (m_nonCoordinatorSites == null)
            m_buffer.putShort((short) 0);
        else {
            m_buffer.putShort((short) m_nonCoordinatorSites.length);
            for (int i = 0; i < m_nonCoordinatorSites.length; i++)
                m_buffer.putInt(m_nonCoordinatorSites[i]);
        }
        m_buffer.put(invocationBytes);
        m_buffer.limit(m_buffer.position());
    }

    @Override
    protected void initFromBuffer() {
        m_buffer.position(HEADER_SIZE + 1); // skip the msg id
        super.readFromBuffer();

        m_lastSafeTxnID = m_buffer.getLong();

        m_isSinglePartition = m_buffer.get() == 1;
        int siteCount = m_buffer.getShort();
        if (siteCount > 0) {
            m_nonCoordinatorSites = new int[siteCount];
            for (int i = 0; i < siteCount; i++)
                m_nonCoordinatorSites[i] = m_buffer.getInt();
        }
        FastDeserializer fds = new FastDeserializer(m_buffer);
        try {
            m_invocation = fds.readObject(StoredProcedureInvocation.class);
            m_invocation.buildParameterSet();
        } catch (IOException e) {
            e.printStackTrace();
            assert(false);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("INITITATE_TASK (FROM ");
        sb.append(m_initiatorSiteId);
        sb.append(" TO ");
        sb.append(m_coordinatorSiteId);
        sb.append(") FOR TXN ");
        sb.append(m_txnId);

        sb.append("\n");
        if (m_isReadOnly)
            sb.append("  READ, ");
        else
            sb.append("  WRITE, ");
        if (m_isSinglePartition)
            sb.append("SINGLE PARTITION, ");
        else
            sb.append("MULTI PARTITION, ");
        sb.append("COORD ");
        sb.append(m_coordinatorSiteId);

        if ((m_nonCoordinatorSites != null) && (m_nonCoordinatorSites.length > 0)) {
            sb.append("\n  NON-COORD SITES: ");
            for (int i : m_nonCoordinatorSites)
                sb.append(i).append(", ");
            sb.setLength(sb.lastIndexOf(", "));
        }

        sb.append("\n  PROCEDURE: ");
        sb.append(m_invocation.getProcName());
        sb.append("\n  PARAMS: ");
        sb.append(m_invocation.getParams().toString());

        return sb.toString();
    }
}

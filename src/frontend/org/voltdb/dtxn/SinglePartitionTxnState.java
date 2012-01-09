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

package org.voltdb.dtxn;

import java.util.ArrayList;

import edu.brown.hstore.PartitionExecutor;
import org.voltdb.TransactionIdManager;
import org.voltdb.debugstate.ExecutorContext.ExecutorTxnState;
import org.voltdb.messaging.InitiateResponseMessage;
import org.voltdb.messaging.InitiateTaskMessage;
import org.voltdb.messaging.Mailbox;
import org.voltdb.messaging.MessagingException;
import org.voltdb.messaging.TransactionInfoBaseMessage;

public class SinglePartitionTxnState extends TransactionState {

    InitiateTaskMessage m_task = null;

    public SinglePartitionTxnState(Mailbox mbox,
                                   PartitionExecutor site,
                                   TransactionInfoBaseMessage task)
    {
        super(mbox, site, task);
        assert(task instanceof InitiateTaskMessage) :
            "Creating single partition txn from invalid membership notice.";
        m_task = (InitiateTaskMessage)task;
    }

    @Override
    public boolean doWork() {
        if (!m_done) {
            m_site.beginNewTxn(m_task.getTxnId(), isReadOnly);
            InitiateResponseMessage response = m_site.processInitiateTask(this, m_task);
            if (response.shouldCommit() == false) {
                if (!isReadOnly) {
                    m_site.rollbackTransaction(isReadOnly);
                }
            }

            try {
                m_mbox.send(initiatorSiteId, 0, response);
            } catch (MessagingException e) {
                throw new RuntimeException(e);
            }
            m_done = true;
        }
        return m_done;
    }

    @Override
    public void getDumpContents(StringBuilder sb) {
        sb.append("  Single Partition Txn State with id ").append(txnId);
    }

    @Override
    public ExecutorTxnState getDumpContents() {
        ExecutorTxnState retval = new ExecutorTxnState();
        retval.txnId = txnId;
        retval.coordinatorSiteId = coordinatorSiteId;
        retval.initiatorSiteId = initiatorSiteId;
        retval.isReadOnly = isReadOnly;
        retval.nonCoordinatingSites = null;
        retval.procedureIsAborting = false;
        return retval;
    }

    @Override
    public String toString() {
        return "SinglePartitionTxnState initiator: " + initiatorSiteId +
            " txnId: " + TransactionIdManager.toString(txnId);
    }

    @Override
    public void handleSiteFaults(ArrayList<Integer> failedSites) {
        // nothing to be done here.
    }
}

/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.vpn.processor.tcp

import com.duckduckgo.mobile.android.vpn.service.VpnQueues
import timber.log.Timber
import xyz.hexene.localvpn.ByteBufferPool
import xyz.hexene.localvpn.Packet
import xyz.hexene.localvpn.TCB
import javax.inject.Inject

class TCBCloser @Inject constructor(private val socketWriter: TcpSocketWriter) {

    /**
     * Close the TCB connection and perform the necessary cleanup to remove it from caches
     */
    fun closeConnection(tcb: TCB) {
        Timber.v("Closing TCB connection %s", tcb.ipAndPort)
        socketWriter.removeFromWriteQueue(tcb)
        tcb.close()
    }

    @Synchronized
    fun sendResetPacket(
        connectionParams: ConnectionInitializer.TcpConnectionParams,
        queues: VpnQueues,
        payloadSize: Int,
        isFIN: Boolean,
    ) {
        val buffer = ByteBufferPool.acquire()
        val tcb = connectionParams.tcbOrClose() ?: return

        var responseAck = tcb.acknowledgementNumberToClient.addAndGet(payloadSize.toLong())
        val responseSeq = tcb.acknowledgementNumberToServer.get()

        if (isFIN) {
            responseAck = TcpPacketProcessor.increaseOrWraparound(responseAck, 1)
        }

        synchronized(this) {
            Timber.d(
                "%s - Sending RST, %s %s, response=[seqNum=%d, ackNum=%d] - previous=[seqNum=%d, ackNum =%d, payloadSize=%d]",
                tcb.ipAndPort,
                tcb.requestingAppPackage, tcb.trackerHostName,
                responseSeq, responseAck,
                tcb.sequenceNumberToClient.get(), tcb.acknowledgementNumberToClient.get(), payloadSize
            )

            tcb.referencePacket.updateTcpBuffer(buffer, (Packet.TCPHeader.RST or Packet.TCPHeader.ACK).toByte(), responseSeq, responseAck, 0)
        }
        queues.networkToDevice.offerFirst(buffer)

        closeConnection(tcb)
        connectionParams.close()
    }
}

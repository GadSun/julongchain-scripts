/*
 * Copyright IBM Corp., DTCC All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package shim.impl;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import shim.ISmartContract;
import org.bcia.javachain.protos.node.SmartContractSupportGrpc;
import org.bcia.javachain.protos.node.SmartcontractShim;

public class ChatStream implements StreamObserver<SmartcontractShim.SmartContractMessage> {

	private Log logger = LogFactory.getLog(ChatStream.class);

	private final ManagedChannel connection;
	private final Handler handler;
	private StreamObserver<SmartcontractShim.SmartContractMessage> streamObserver;

	public ChatStream(ManagedChannel connection, ISmartContract smartcontract) {
		// Establish stream with validating peer
		SmartContractSupportGrpc.SmartContractSupportStub stub = SmartContractSupportGrpc.newStub(connection);

		logger.info("Connecting to peer.");

		try {
			this.streamObserver = stub.register(this);
		} catch (Exception e) {
			logger.error("Unable to connect to peer server", e);
			System.exit(-1);
		}
		this.connection = connection;

		// Create the org.hyperledger.fabric.shim handler responsible for all
		// control logic
		this.handler = new Handler(this, smartcontract);
	}

	public synchronized void serialSend(SmartcontractShim.SmartContractMessage message) {
			logger.info(String.format("[%-8s]Sending %s message to peer.", message.getTxid(), message.getType()));
			logger.info(String.format("[%-8s]SmartcontractMessage: %s", message.getTxid(), toJsonString(message)));
		try {
			this.streamObserver.onNext(message);
			logger.info(String.format("[%-8s]%s message sent.", message.getTxid(), message.getType()));
		} catch (Exception e) {
			logger.info(String.format("[%-8s]Error sending %s: %s", message.getTxid(), message.getType(), e));
			throw new RuntimeException(String.format("Error sending %s: %s", message.getType(), e));
		}
	}

	@Override
	public void onNext(SmartcontractShim.SmartContractMessage message) {
			logger.info("Got message from peer: " + toJsonString(message));
		try {
			logger.info(String.format("[%-8s]Received message %s from org.bcia.javachain.shim", message.getTxid(), message.getType()));
			handler.handleMessage(message);
		} catch (Exception e) {
			System.exit(-1);
		}
	}

	@Override
	public void onError(Throwable e) {
		logger.error("Unable to connect to peer server: " + e.getMessage());
		System.exit(-1);
	}

	@Override
	public void onCompleted() {
		connection.shutdown();
		handler.nextState.close();
	}

	static String toJsonString(SmartcontractShim.SmartContractMessage message) {
		try {
			return JsonFormat.printer().print(message);
		} catch (InvalidProtocolBufferException e) {
			return String.format("{ Type: %s, TxId: %s }", message.getType(), message.getTxid());
		}
	}

	public void receive() throws Exception {
		NextStateInfo nsInfo = handler.nextState.take();
		logger.info(nsInfo.toString());
		SmartcontractShim.SmartContractMessage message = nsInfo.message;
		onNext(message);

		// keepalive messages are PONGs to the fabric's PINGs
		if (nsInfo.sendToSC || message.getType() == SmartcontractShim.SmartContractMessage.Type.KEEPALIVE) {
			if (message.getType() == SmartcontractShim.SmartContractMessage.Type.KEEPALIVE) {
				logger.info("Sending KEEPALIVE response");
			} else {
				logger.info(String.format("[%-8s]Send state message %s", message.getTxid(), message.getType()));
			}
			serialSend(message);
		}
	}
}

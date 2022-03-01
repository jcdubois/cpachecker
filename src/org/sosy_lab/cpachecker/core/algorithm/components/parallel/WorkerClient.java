// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components.parallel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import org.sosy_lab.cpachecker.core.algorithm.components.parallel.Message.MessageConverter;

public class WorkerClient {

  private final SocketChannel client;
  private final MessageConverter converter;

  public WorkerClient(String pAddress, int pPort) throws IOException {
    InetSocketAddress hostAddress = new InetSocketAddress(pAddress, pPort);
    client = SocketChannel.open(hostAddress);
    converter = new MessageConverter();
  }

  public void broadcast(Message pMessage) throws IOException {
    String json = converter.messageToJson(pMessage);
    byte [] message = json.getBytes();
    ByteBuffer buffer = ByteBuffer.wrap(message);
    client.write(buffer);
    buffer.clear();
  }

  public void close() throws IOException {
    client.close();
  }
}


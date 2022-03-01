// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components.exchange.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Message;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Message.MessageConverter;

public class NetworkSender {

  private final MessageConverter converter;
  private final InetSocketAddress address;

  public NetworkSender(String pAddress, int pPort) throws IOException {
    this(new InetSocketAddress(pAddress, pPort));
  }

  public NetworkSender(InetSocketAddress pAddress) throws IOException {
    converter = new MessageConverter();
    address = pAddress;
  }

  public void send(Message pMessage) throws IOException {
    SocketChannel client = SocketChannel.open(address);
    String json = converter.messageToJson(pMessage);
    byte[] message = json.getBytes();
    ByteBuffer buffer = ByteBuffer.wrap(message);
    client.write(buffer);
    client.close();
  }

}


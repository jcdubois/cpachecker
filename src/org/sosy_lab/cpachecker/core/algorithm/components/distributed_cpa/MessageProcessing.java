// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components.distributed_cpa;

import com.google.common.collect.ForwardingCollection;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Message;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Payload;

/**
 * Proceed operators need two return a collection of messages and a boolean indicating
 * whether the analysis should proceed. This class combines these two return types by forwarding
 * a collection and having an unmodifiable boolean attribute {@code end}.
 */
public class MessageProcessing extends ForwardingCollection<Message> {

  private final Collection<Message> messages;
  private final boolean end;

  private final static MessageProcessing emptyProceed = new MessageProcessing(ImmutableList.of(), false);
  private final static MessageProcessing emptyStop = new MessageProcessing(ImmutableList.of(), true);

  private MessageProcessing(Collection<Message> pMessages, boolean pEnd) {
    messages = pMessages;
    end = pEnd;
  }

  public static MessageProcessing proceed() {
    return emptyProceed;
  }

  public static MessageProcessing stop() {
    return emptyStop;
  }

  public static MessageProcessing proceedWith(Collection<Message> pMessages) {
    return new MessageProcessing(pMessages, false);
  }

  public static MessageProcessing stopWith(Collection<Message> pMessages) {
    return new MessageProcessing(pMessages, true);
  }

  public static MessageProcessing proceedWith(Message... pMessages) {
    return new MessageProcessing(ImmutableList.copyOf(pMessages), false);
  }

  public static MessageProcessing stopWith(Message... pMessages) {
    return new MessageProcessing(ImmutableList.copyOf(pMessages), true);
  }

  public boolean end() {
    return end;
  }

  public Collection<Payload> toPayloadCollection() {
    return messages.stream().map(m -> m.getPayload()).collect(ImmutableList.toImmutableList());
  }

  public MessageProcessing merge(MessageProcessing pProcessing, boolean removeDuplicates) {
    Collection<Message> copy =
        removeDuplicates ? new HashSet<>(messages) : new ArrayList<>(messages);
    copy.addAll(pProcessing);
    return new MessageProcessing(copy, end || pProcessing.end);
  }

  @Override
  protected Collection<Message> delegate() {
    return messages;
  }
}

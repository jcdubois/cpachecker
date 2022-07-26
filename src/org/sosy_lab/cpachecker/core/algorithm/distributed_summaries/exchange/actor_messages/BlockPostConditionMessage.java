// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages;

import java.time.Instant;
import java.util.Set;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.Payload;

public class BlockPostConditionMessage extends ActorMessage {

  private final boolean fullPath;
  private final boolean reachable;
  private final Set<String> visited;

  BlockPostConditionMessage(
      String pUniqueBlockId, int pTargetNodeNumber, Payload pPayload, Instant pInstant) {
    super(MessageType.BLOCK_POSTCONDITION, pUniqueBlockId, pTargetNodeNumber, pPayload, pInstant);
    fullPath = extractFlag(Payload.FULL_PATH, false);
    reachable = extractFlag(Payload.REACHABLE, true);
    visited = extractVisited();
  }

  public boolean representsFullPath() {
    return fullPath;
  }

  public Set<String> visitedBlockIds() {
    return visited;
  }

  public boolean isReachable() {
    return reachable;
  }

  @Override
  protected ActorMessage replacePayload(Payload pPayload) {
    return new BlockPostConditionMessage(
        getUniqueBlockId(), getTargetNodeNumber(), pPayload, getTimestamp());
  }
}

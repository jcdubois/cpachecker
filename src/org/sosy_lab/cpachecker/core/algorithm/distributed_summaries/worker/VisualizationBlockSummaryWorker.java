// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.worker;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Collection;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.decomposition.BlockGraph;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.Connection;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.ActorMessage;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.ActorMessage.MessageType;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.visualization.MessageLogger;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.java_smt.api.SolverException;

public class VisualizationBlockSummaryWorker extends BlockSummaryWorker {

  private final MessageLogger messageLogger;
  private final Connection connection;
  private boolean shutdown = false;

  protected VisualizationBlockSummaryWorker(
      BlockGraph pTree, Connection pConnection, LogManager pLogger, Configuration pConfiguration)
      throws InvalidConfigurationException {
    super("visualization-worker", pLogger);
    connection = pConnection;
    messageLogger = new MessageLogger(pTree, pConfiguration);
    try {
      messageLogger.logTree();
    } catch (IOException pE) {
      getLogger()
          .logException(
              Level.WARNING,
              pE,
              "VisualizationWorker failed to log the BlockTree. "
                  + "The visualization might contain old data or will not work. "
                  + "However, the analysis continues normally.");
    }
  }

  @Override
  public Collection<ActorMessage> processMessage(ActorMessage pMessage)
      throws InterruptedException, IOException, SolverException, CPAException {
    messageLogger.log(pMessage);
    boolean stop = false;
    while (!connection.isEmpty()) {
      ActorMessage m = connection.read();
      messageLogger.log(m);
      stop |= m.getType() == MessageType.ERROR || m.getType() == MessageType.FOUND_RESULT;
    }
    if (stop) {
      shutdown = true;
    }
    return ImmutableSet.of();
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public boolean shutdownRequested() {
    return shutdown;
  }
}

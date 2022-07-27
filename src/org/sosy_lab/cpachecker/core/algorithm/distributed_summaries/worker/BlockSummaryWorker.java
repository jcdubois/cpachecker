// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.worker;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collection;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.Connection;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.ActorMessage;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.java_smt.api.SolverException;

public abstract class BlockSummaryWorker implements BlockSummaryActor {

  private final LogManager logger;
  private final String id;

  /**
   * Abstract definition of a Worker. All workers enter the same routine of receiving and producing
   * messages.
   *
   * @param pId the id of the worker
   */
  protected BlockSummaryWorker(String pId, LogManager pLogger) {
    id = pId;
    logger = pLogger.withComponentName(pId);
  }

  @Override
  public void broadcast(Collection<ActorMessage> pMessage) throws InterruptedException {
    pMessage.forEach(m -> logger.log(Level.INFO, m));
    for (ActorMessage message : pMessage) {
      getConnection().write(message);
    }
  }

  protected void broadcastOrLogException(Collection<ActorMessage> pMessage) {
    try {
      broadcast(pMessage);
    } catch (InterruptedException pE) {
      logger.logfException(
          Level.SEVERE, pE, "Broadcasting %s messages interrupted unexpectedly.", pMessage);
    }
  }

  @Override
  public void run() {
    final Connection connection = getConnection();
    try (connection) {
      while (!shutdownRequested()) {
        broadcast(processMessage(nextMessage()));
        if (Thread.currentThread().isInterrupted()) {
          break;
        }
      }
    } catch (CPAException | InterruptedException | IOException | SolverException pE) {
      logger.logfException(
          Level.SEVERE, pE, "%s faced a problem while processing messages.", getId());
      broadcastOrLogException(ImmutableList.of(ActorMessage.newErrorMessage(getId(), pE)));
    } finally {
      logger.logf(Level.INFO, "Worker %s finished and shuts down.", id);
    }
  }

  public boolean hasPendingMessages() {
    return !getConnection().isEmpty();
  }

  @Override
  public final String getId() {
    return id;
  }

  public LogManager getLogger() {
    return logger;
  }
}

// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components.worker;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.BlockNode;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Message;
import org.sosy_lab.cpachecker.core.specification.Specification;
import org.sosy_lab.cpachecker.exceptions.CPAException;

public class MonitoredAnalysisWorker extends AnalysisWorker {

  private final Monitor monitor;
  private final BlockNode block;

  MonitoredAnalysisWorker(
      String pId,
      BlockNode pBlock,
      LogManager pLogger,
      CFA pCFA,
      Specification pSpecification,
      Configuration pConfiguration,
      ShutdownManager pShutdownManager,
      Monitor pMonitor)
      throws CPAException, IOException, InterruptedException, InvalidConfigurationException {
    super(pId, pBlock, pLogger, pCFA, pSpecification,
        pConfiguration,
        pShutdownManager);
    monitor = pMonitor;
    block = pBlock;
  }

  @Override
  public Message nextMessage() throws InterruptedException {
    Message next =  super.nextMessage();
    monitor.blockAcquire(next, block);
    return next;
  }

  @Override
  public void broadcast(Message pMessage) throws IOException, InterruptedException {
    super.broadcast(pMessage);
    monitor.blockRelease();
  }

  @Override
  public void run() {
    try {
      monitor.blockAcquire(Message.noResponse(), block);
      super.run();
    } catch (InterruptedException pE) {
      logger.log(Level.SEVERE, pE);
    }
  }

  public static class Monitor {

    private final Semaphore semaphore;
    private final LogManager logger;
    private int open;

    public Monitor(LogManager pLogManager, int pPermits) {
      semaphore = new Semaphore(pPermits);
      logger = pLogManager;
      open = pPermits;
    }

    public void blockAcquire(Message pMessage, BlockNode pBlockNode) throws InterruptedException {
      logger.log(Level.FINEST, pBlockNode.getId() + " acquires for " + pMessage + "(" + open + ")");
      semaphore.acquire();
      open--;
    }

    public void blockRelease() {
      semaphore.release();
      open++;
    }
  }

}

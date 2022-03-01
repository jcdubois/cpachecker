// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components.worker;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Message;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.java_smt.api.SolverException;

public class TimeoutWorker extends Worker {

  private final long waitTime;

  private boolean shouldScheduleTimer;
  private final Timer timer;
  private final TimerTask task;

  protected TimeoutWorker(LogManager pLogger, long pMillis) {
    super("timeout-worker", pLogger);
    waitTime = pMillis;
    shouldScheduleTimer = true;
    timer = new Timer();
    task = new TimerTask() {
      @Override
      public void run() {
        try {
          broadcast(ImmutableSet.of(Message.newResultMessage("timeout", 0, Result.UNKNOWN, ImmutableSet.of())));
          shutdown();
        } catch (IOException pE) {
          logger.log(Level.SEVERE, "Cannot broadcast timeout message properly because of", pE);
          logger.log(Level.INFO, "Trying to send timeout message one last time...");
          shouldScheduleTimer = false;
          TimeoutWorker.this.run();
        } catch (InterruptedException pE) {
          shouldScheduleTimer = false;
          TimeoutWorker.this.run();
        }
      }
    };
  }

  @Override
  public Collection<Message> processMessage(
      Message pMessage) throws InterruptedException, IOException, SolverException, CPAException {
    switch (pMessage.getType()) {
      case ERROR:
      case FOUND_RESULT:
        shutdown();
      case ERROR_CONDITION:
      case ERROR_CONDITION_UNREACHABLE:
      case BLOCK_POSTCONDITION:
        break;
      default:
        throw new AssertionError("Unknown type of " + pMessage.getType().getClass() + ": " + pMessage.getType());
    }
    return ImmutableSet.of();
  }

  @Override
  public void run() {
    if (shouldScheduleTimer) {
      // abort in waitTime milliseconds
      timer.schedule(task, waitTime);
      // read incoming messages to terminate in case analysis finished early
      super.run();
    } else {
      // if the timer encounters a problem with sending the timeout message try it one last time...
      try {
        broadcast(ImmutableSet.of(Message.newResultMessage("timeout", 0, Result.UNKNOWN, ImmutableSet.of())));
      } catch (IOException | InterruptedException pE) {
        logger.log(Level.SEVERE, "TimeoutWorker failed to send message.", pE);
      }
    }
  }

  @Override
  public synchronized void shutdown() throws IOException {
    timer.cancel();
    timer.purge();
    super.shutdown();
  }

}

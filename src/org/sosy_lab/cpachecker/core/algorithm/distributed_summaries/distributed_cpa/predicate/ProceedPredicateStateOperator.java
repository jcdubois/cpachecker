// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.predicate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.AnalysisDirection;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.decomposition.BlockNode;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.ActorMessageProcessing;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.DistributedConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.operators.DeserializeOperator;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.operators.proceed.ProceedOperator;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.Payload;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.ActorMessage;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.BlockPostConditionActorMessage;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.ErrorConditionActorMessage;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.worker.AnalysisOptions;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateAbstractState;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.SolverException;

public class ProceedPredicateStateOperator implements ProceedOperator {

  private final AnalysisDirection direction;
  private final AnalysisOptions analysisOptions;
  private final BlockNode block;
  private final Solver solver;
  private final DeserializeOperator deserialize;

  private final Set<String> unsatPredecessors;
  private final Map<String, ActorMessage> receivedPostConditions;

  private final FormulaManagerView fmgr;
  private final String trueString;

  private BlockPostConditionActorMessage latestOwnPostConditionMessage;
  private BooleanFormula latestOwnPostCondition;

  public ProceedPredicateStateOperator(
      AnalysisOptions pOptions,
      AnalysisDirection pDirection,
      BlockNode pBlockNode,
      Solver pSolver,
      DeserializeOperator pDeserializeOperator) {
    direction = pDirection;
    analysisOptions = pOptions;
    block = pBlockNode;
    solver = pSolver;
    fmgr = solver.getFormulaManager();
    trueString = fmgr.dumpFormula(fmgr.getBooleanFormulaManager().makeTrue()).toString();
    deserialize = pDeserializeOperator;

    unsatPredecessors = new HashSet<>();
    receivedPostConditions = new HashMap<>();
  }

  @Override
  public ActorMessageProcessing proceed(ActorMessage pMessage)
      throws InterruptedException, SolverException {
    return direction == AnalysisDirection.FORWARD
        ? proceedForward((BlockPostConditionActorMessage) pMessage)
        : proceedBackward((ErrorConditionActorMessage) pMessage);
  }

  @Override
  public ActorMessageProcessing proceedBackward(ErrorConditionActorMessage message)
      throws SolverException, InterruptedException {
    CFANode node = block.getNodeWithNumber(message.getTargetNodeNumber());
    if (!(node.equals(block.getLastNode())
        || (!node.equals(block.getLastNode())
            && !node.equals(block.getStartNode())
            && block.getNodesInBlock().contains(node)))) {
      return ActorMessageProcessing.stop();
    }
    if (analysisOptions.checkEveryErrorConditionForUnsatisfiability()) {
      // can the error condition be denied?
      BooleanFormula messageFormula =
          fmgr.parse(message.getAbstractStateString(PredicateCPA.class).orElse(trueString));
      if (solver.isUnsat(messageFormula)) {
        return ActorMessageProcessing.stopWith(
            ActorMessage.newErrorConditionUnreachableMessage(
                block.getId(), "unsat-formula: " + messageFormula));
      }
    }
    if (latestOwnPostConditionMessage != null
        && (receivedPostConditions.size() <= 3
            || analysisOptions.checkEveryErrorConditionForUnsatisfiability())
        && receivedPostConditions.size() + unsatPredecessors.size()
            == block.getPredecessors().size()) {
      BooleanFormula messageFormula =
          fmgr.parse(message.getAbstractStateString(PredicateCPA.class).orElse(trueString));
      BooleanFormula check =
          fmgr.getBooleanFormulaManager().and(messageFormula, latestOwnPostCondition);
      if (solver.isUnsat(check)) {
        return ActorMessageProcessing.stopWith(
            ActorMessage.newErrorConditionUnreachableMessage(
                block.getId(), "unsat-with-last-post: " + check));
      }
    }
    return ActorMessageProcessing.proceedWith(message);
  }

  @Override
  public ActorMessageProcessing proceedForward(BlockPostConditionActorMessage message)
      throws InterruptedException {
    CFANode node = block.getNodeWithNumber(message.getTargetNodeNumber());
    if (!block.getStartNode().equals(node)) {
      return ActorMessageProcessing.stop();
    }
    if (!message.isReachable()) {
      unsatPredecessors.add(message.getUniqueBlockId());
      return ActorMessageProcessing.stop();
    }
    PredicateAbstractState state = (PredicateAbstractState) deserialize.deserialize(message);
    try {
      if (solver.isUnsat(state.getPathFormula().getFormula())) {
        receivedPostConditions.remove(message.getUniqueBlockId());
        unsatPredecessors.add(message.getUniqueBlockId());
        return ActorMessageProcessing.stop();
      }
    } catch (SolverException pE) {
      return ActorMessageProcessing.stopWith(ActorMessage.newErrorMessage(block.getId(), pE));
    }
    unsatPredecessors.remove(message.getUniqueBlockId());
    storePostCondition(message);
    // check if every predecessor contains the full path (root node)
    if (receivedPostConditions.size() + unsatPredecessors.size()
        == block.getPredecessors().size()) {
      return ActorMessageProcessing.proceedWith(receivedPostConditions.values());
    } else {
      // would equal initial message that has already been or will be processed by other workers
      return ActorMessageProcessing.stop();
    }
  }

  private void storePostCondition(BlockPostConditionActorMessage pMessage) {
    ActorMessage toStore = ActorMessage.removeEntry(pMessage, Payload.SMART);
    if (analysisOptions.storeCircularPostConditions()
        && pMessage.visitedBlockIds().stream().anyMatch(s -> s.equals(block.getId()))) {
      if (pMessage.representsFullPath()) {
        receivedPostConditions.put(pMessage.getUniqueBlockId(), toStore);
      } else {
        receivedPostConditions.remove(pMessage.getUniqueBlockId());
      }
    } else {
      receivedPostConditions.put(pMessage.getUniqueBlockId(), toStore);
    }
  }

  @Override
  public void synchronizeKnowledge(DistributedConfigurableProgramAnalysis pDCPA) {
    ProceedPredicateStateOperator proceed =
        ((ProceedPredicateStateOperator) pDCPA.getProceedOperator());
    if (direction == AnalysisDirection.BACKWARD) {
      if (proceed.latestOwnPostConditionMessage != null) {
        update(proceed.latestOwnPostConditionMessage);
      }
      unsatPredecessors.clear();
      unsatPredecessors.addAll(proceed.unsatPredecessors);
      receivedPostConditions.clear();
      receivedPostConditions.putAll(proceed.receivedPostConditions);
    }
  }

  @Override
  public void update(BlockPostConditionActorMessage pLatestOwnPreconditionMessage) {
    latestOwnPostConditionMessage = pLatestOwnPreconditionMessage;
    latestOwnPostCondition =
        fmgr.parse(
            latestOwnPostConditionMessage
                .getAbstractStateString(PredicateCPA.class)
                .orElse(trueString));
  }
}

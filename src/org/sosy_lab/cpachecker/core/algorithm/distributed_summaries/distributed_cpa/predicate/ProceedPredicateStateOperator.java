// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.predicate;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.AnalysisDirection;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.decomposition.BlockNode;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.BlockSummaryMessageProcessing;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.DistributedConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.operators.DeserializeOperator;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.operators.proceed.ProceedOperator;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.Payload;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.BlockSummaryErrorConditionMessage;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.BlockSummaryMessage;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.BlockSummaryPostConditionMessage;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.worker.BlockSummaryAnalysisOptions;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateAbstractState;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.SolverException;

public class ProceedPredicateStateOperator implements ProceedOperator {

  private final AnalysisDirection direction;
  private final BlockSummaryAnalysisOptions analysisOptions;
  private final BlockNode block;
  private final Solver solver;
  private final DeserializeOperator deserialize;

  private final Set<String> unsatPredecessors;
  private final Map<String, BlockSummaryMessage> receivedPostConditions;

  private final FormulaManagerView fmgr;
  private final String trueString;

  private BlockSummaryPostConditionMessage latestOwnPostConditionMessage;
  private BooleanFormula latestOwnPostCondition;

  public ProceedPredicateStateOperator(
      BlockSummaryAnalysisOptions pOptions,
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

    unsatPredecessors = new LinkedHashSet<>();
    receivedPostConditions = new HashMap<>();
  }

  @Override
  public BlockSummaryMessageProcessing proceed(BlockSummaryMessage pMessage)
      throws InterruptedException, SolverException {
    return direction == AnalysisDirection.FORWARD
        ? proceedForward((BlockSummaryPostConditionMessage) pMessage)
        : proceedBackward((BlockSummaryErrorConditionMessage) pMessage);
  }

  @Override
  public BlockSummaryMessageProcessing proceedBackward(BlockSummaryErrorConditionMessage message)
      throws SolverException, InterruptedException {
    CFANode node = block.getNodeWithNumber(message.getTargetNodeNumber());
    if (!(node.equals(block.getLastNode())
        || (!node.equals(block.getLastNode())
            && !node.equals(block.getStartNode())
            && block.getNodesInBlock().contains(node)))) {
      return BlockSummaryMessageProcessing.stop();
    }
    if (analysisOptions.checkEveryErrorConditionForUnsatisfiability()) {
      // can the error condition be denied?
      BooleanFormula messageFormula =
          fmgr.parse(message.getAbstractStateString(PredicateCPA.class).orElse(trueString));
      if (solver.isUnsat(messageFormula)) {
        return BlockSummaryMessageProcessing.stopWith(
            BlockSummaryMessage.newErrorConditionUnreachableMessage(
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
        return BlockSummaryMessageProcessing.stopWith(
            BlockSummaryMessage.newErrorConditionUnreachableMessage(
                block.getId(), "unsat-with-last-post: " + check));
      }
    }
    return BlockSummaryMessageProcessing.proceedWith(message);
  }

  @Override
  public BlockSummaryMessageProcessing proceedForward(BlockSummaryPostConditionMessage message)
      throws InterruptedException {
    CFANode node = block.getNodeWithNumber(message.getTargetNodeNumber());
    if (!block.getStartNode().equals(node)) {
      return BlockSummaryMessageProcessing.stop();
    }
    if (!message.isReachable()) {
      unsatPredecessors.add(message.getUniqueBlockId());
      return BlockSummaryMessageProcessing.stop();
    }
    PredicateAbstractState state = (PredicateAbstractState) deserialize.deserialize(message);
    try {
      if (solver.isUnsat(state.getPathFormula().getFormula())) {
        receivedPostConditions.remove(message.getUniqueBlockId());
        unsatPredecessors.add(message.getUniqueBlockId());
        return BlockSummaryMessageProcessing.stop();
      }
    } catch (SolverException pE) {
      return BlockSummaryMessageProcessing.stopWith(
          BlockSummaryMessage.newErrorMessage(block.getId(), pE));
    }
    unsatPredecessors.remove(message.getUniqueBlockId());
    storePostCondition(message);
    // check if every predecessor contains the full path (root node)
    if (receivedPostConditions.size() + unsatPredecessors.size()
        == block.getPredecessors().size()) {
      return BlockSummaryMessageProcessing.proceedWith(receivedPostConditions.values());
    } else {
      // would equal initial message that has already been or will be processed by other workers
      return BlockSummaryMessageProcessing.stop();
    }
  }

  private void storePostCondition(BlockSummaryPostConditionMessage pMessage) {
    BlockSummaryMessage toStore = BlockSummaryMessage.removeEntry(pMessage, Payload.SMART);
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
  public void update(BlockSummaryPostConditionMessage pLatestOwnPreconditionMessage) {
    latestOwnPostConditionMessage = pLatestOwnPreconditionMessage;
    latestOwnPostCondition =
        fmgr.parse(
            latestOwnPostConditionMessage
                .getAbstractStateString(PredicateCPA.class)
                .orElse(trueString));
  }
}

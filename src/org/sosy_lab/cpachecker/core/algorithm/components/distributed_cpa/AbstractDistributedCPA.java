// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components.distributed_cpa;

import java.util.ArrayList;
import java.util.List;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.AnalysisDirection;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.BlockNode;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Message;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Payload;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.UpdatedTypeMap;
import org.sosy_lab.cpachecker.core.algorithm.components.worker.AnalysisOptions;
import org.sosy_lab.cpachecker.core.interfaces.AbstractDomain;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.MergeOperator;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.StopOperator;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.java_smt.api.SolverException;

public abstract class AbstractDistributedCPA implements ConfigurableProgramAnalysis {

  protected final BlockNode block;
  protected final UpdatedTypeMap typeMap;
  protected final AnalysisDirection direction;
  protected final String id;
  protected final AnalysisOptions analysisOptions;
  protected ConfigurableProgramAnalysis parentCPA;
  protected Precision precision;

  protected Message latestOwnPostConditionMessage;

  /**
   * Extends CPAs for distribution.
   * Forwards the parent CPA, this DCPA can be used as CPA, too.
   * @param pId id of the block
   * @param pNode block on which this CPA operates on
   * @param pTypeMap map with variables and their type
   * @param pPrecision initial precission
   * @param pDirection analysis direction of this DCPA
   * @param pOptions user options
   * @throws CPAException wrapper exception
   */
  public AbstractDistributedCPA(
      String pId,
      BlockNode pNode,
      UpdatedTypeMap pTypeMap,
      Precision pPrecision,
      AnalysisDirection pDirection,
      AnalysisOptions pOptions) throws
                                CPAException {
    block = pNode;
    typeMap = pTypeMap;
    direction = pDirection;
    id = pId;
    precision = pPrecision;
    analysisOptions = pOptions;
  }

  /**
   * Transform a message back to an abstract state
   * @param pMessage transforms this message to an abstract state
   * @return abstract state represented by {@code pMessage}
   * @throws InterruptedException thread interrupted
   */
  public abstract AbstractState deserialize(Message pMessage)
      throws InterruptedException;

  /**
   * Serializes a message to an abstract state
   * @param pState abstract state
   * @return payload for messages
   */
  public abstract Payload serialize(AbstractState pState);

  protected abstract MessageProcessing proceedForward(Message newMessage)
      throws InterruptedException, SolverException;

  protected abstract MessageProcessing proceedBackward(Message newMessage)
      throws SolverException, InterruptedException;

  /**
   * Combine two abstract states to one abstract state
   * @param pState1 First abstract state
   * @param pState2 Second abstract state
   * @return combined abstract state
   * @throws InterruptedException thread interrupted
   * @throws CPAException wrapper exception
   */
  public abstract AbstractState combine(AbstractState pState1, AbstractState pState2)
      throws InterruptedException, CPAException;

  /**
   * Check if the inputted message allows a forward analysis
   * @param newMessage current message to process
   * @return a set of messages paired with a boolean telling the {@link org.sosy_lab.cpachecker.core.algorithm.components.worker.AnalysisWorker} whether to proceed
   * @throws InterruptedException thread interrupted
   * @throws SolverException solver throws an internal error
   */
  public MessageProcessing proceed(Message newMessage)
      throws SolverException, InterruptedException {
    return direction == AnalysisDirection.FORWARD ? proceedForward(newMessage)
                                                  : proceedBackward(newMessage);
  }

  public final AbstractState combine(List<AbstractState> pStates)
      throws InterruptedException, CPAException {
    if (pStates.isEmpty()) {
      return getInitialState(
          direction == AnalysisDirection.FORWARD ? block.getStartNode() : block.getLastNode(),
          StateSpacePartition.getDefaultPartition());
    }
    if (pStates.size() == 1) {
      return pStates.get(0);
    }

    List<AbstractState> ordered = new ArrayList<>(pStates);
    AbstractState state = ordered.remove(0);

    for (AbstractState abstractState : ordered) {
      state = combine(state, abstractState);
    }

    return state;
  }

  public abstract boolean doesOperateOn(Class<? extends AbstractState> pClass);

  public AnalysisDirection getDirection() {
    return direction;
  }

  public ConfigurableProgramAnalysis getParentCPA() {
    return parentCPA;
  }

  public void setParentCPA(ConfigurableProgramAnalysis pParentCPA) throws CPAException {
    parentCPA = pParentCPA;
  }

  @Override
  public MergeOperator getMergeOperator() {
    return parentCPA.getMergeOperator();
  }

  @Override
  public StopOperator getStopOperator() {
    return parentCPA.getStopOperator();
  }

  @Override
  public AbstractDomain getAbstractDomain() {
    return parentCPA.getAbstractDomain();
  }

  @Override
  public TransferRelation getTransferRelation() {
    return parentCPA.getTransferRelation();
  }

  @Override
  public AbstractState getInitialState(
      CFANode node, StateSpacePartition partition) throws InterruptedException {
    return parentCPA.getInitialState(node, partition);
  }

  /**
   * Allows an exchange of information of DCPAs on the same block but different directions.
   * (The last message of the forward analysis decides whether to continue the backward analysis)
   * @param pDCPA distributed cpa on same block in different direction
   */
  public void synchronizeKnowledge(AbstractDistributedCPA pDCPA) {
    assert pDCPA.getClass().equals(getClass()) :
        "Can only synchronize knowledge between equal classes of DCPAs but got " + pDCPA.getClass()
            + " and " + getClass();
    assert pDCPA.direction != direction
        : "Can only exchange data from DCPAs operating in distinct directions (cannot override values)";
    assert block.getId().equals(pDCPA.block.getId()) : "DCPAs have to run on the same block";
    if (direction == AnalysisDirection.BACKWARD) {
      latestOwnPostConditionMessage = pDCPA.latestOwnPostConditionMessage;
    }
  }

  public CFANode getStartNode() {
    return direction == AnalysisDirection.FORWARD ? block.getStartNode() : block.getLastNode();
  }

  public void setLatestOwnPostConditionMessage(Message m) {
    latestOwnPostConditionMessage = m;
  }
}

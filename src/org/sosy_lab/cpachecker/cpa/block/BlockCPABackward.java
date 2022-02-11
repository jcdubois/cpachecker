// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cpa.block;

import static com.google.common.base.Preconditions.checkState;

import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.AnalysisDirection;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.BlockNode;
import org.sosy_lab.cpachecker.core.defaults.AbstractCPA;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.CPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;
import org.sosy_lab.cpachecker.cpa.block.BlockState.BlockStateType;
import org.sosy_lab.cpachecker.cpa.block.BlockTransferRelation.BackwardBlockTransferRelation;

public class BlockCPABackward extends AbstractCPA {

  private final CFA cfa;
  private BlockNode blockNode;

  public BlockCPABackward(CFA pCFA) {
    super("sep", "sep", new BlockDomain(), new BackwardBlockTransferRelation());
    cfa = pCFA;
  }

  public void init(BlockNode pBlockNode) {
    blockNode = pBlockNode;
    TransferRelation relation = getTransferRelation();
    checkState(relation instanceof BackwardBlockTransferRelation, "Expected %s but got %s",
        BackwardBlockTransferRelation.class, relation.getClass());
    ((BackwardBlockTransferRelation) relation).init(pBlockNode);
  }

  public static CPAFactory factory() {
    return new BlockCPAFactory(AnalysisDirection.BACKWARD);
  }

  @Override
  public AbstractState getInitialState(
      CFANode node, StateSpacePartition partition) throws InterruptedException {
    CFANode target = blockNode == null ? cfa.getMainFunction() : blockNode.getLastNode();
    return new BlockState(node, target, AnalysisDirection.BACKWARD, BlockStateType.INITIAL);
  }

  public static BlockCPABackward create(CFA pCFA, Configuration pConfig)
      throws InvalidConfigurationException {
    return new BlockCPABackward(pCFA);
  }
}

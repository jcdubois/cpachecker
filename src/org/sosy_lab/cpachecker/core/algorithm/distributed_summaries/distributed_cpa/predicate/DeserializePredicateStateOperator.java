// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.predicate;

import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.decomposition.BlockNode;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.distributed_cpa.operators.DeserializeOperator;
import org.sosy_lab.cpachecker.core.algorithm.distributed_summaries.exchange.actor_messages.ActorMessage;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateAbstractState;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.util.predicates.pathformula.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;

public class DeserializePredicateStateOperator implements DeserializeOperator {

  private final PredicateCPA predicateCPA;
  private final FormulaManagerView formulaManagerView;
  private final PathFormulaManager pathFormulaManager;
  private final BlockNode block;

  public DeserializePredicateStateOperator(
      PredicateCPA pPredicateCPA,
      FormulaManagerView pFormulaManagerView,
      PathFormulaManager pPathFormulaManager,
      BlockNode pBlockNode) {
    predicateCPA = pPredicateCPA;
    formulaManagerView = pFormulaManagerView;
    pathFormulaManager = pPathFormulaManager;
    block = pBlockNode;
  }

  @Override
  public AbstractState deserialize(ActorMessage pMessage) {
    String formula =
        PredicateOperatorUtil.extractFormulaString(
            pMessage, predicateCPA.getClass(), formulaManagerView);
    return PredicateAbstractState.mkNonAbstractionStateWithNewPathFormula(
        PredicateOperatorUtil.getPathFormula(formula, pathFormulaManager, formulaManagerView),
        (PredicateAbstractState)
            predicateCPA.getInitialState(
                block.getNodeWithNumber(pMessage.getTargetNodeNumber()),
                StateSpacePartition.getDefaultPartition()));
  }
}

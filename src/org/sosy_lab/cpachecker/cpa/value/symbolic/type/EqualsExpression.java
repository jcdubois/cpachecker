// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cpa.value.symbolic.type;

import org.sosy_lab.cpachecker.cfa.types.Type;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.BinaryConstraint;
import org.sosy_lab.cpachecker.util.states.MemoryLocation;

/** {@link BinarySymbolicExpression} representing the 'equals' operation. */
public final class EqualsExpression extends BinarySymbolicExpression implements BinaryConstraint {

  private static final long serialVersionUID = 5539278811391062614L;

  EqualsExpression(
      SymbolicExpression pOperand1,
      SymbolicExpression pOperand2,
      Type pExpressionType,
      Type pCalculationType) {
    super(pOperand1, pOperand2, pExpressionType, pCalculationType);
  }

  EqualsExpression(
      final SymbolicExpression pOperand1,
      final SymbolicExpression pOperand2,
      final Type pExpressionType,
      final Type pCalculationType,
      final MemoryLocation pRepresentedLocation) {
    super(pOperand1, pOperand2, pExpressionType, pCalculationType, pRepresentedLocation);
  }

  private EqualsExpression(
      final SymbolicExpression pOperand1,
      final SymbolicExpression pOperand2,
      final Type pExpressionType,
      final Type pCalculationType,
      final AbstractState pAbstractState) {
    super(pOperand1, pOperand2, pExpressionType, pCalculationType, pAbstractState);
  }

  @Override
  public EqualsExpression copyForLocation(final MemoryLocation pRepresentedLocation) {
    return new EqualsExpression(
        getOperand1(), getOperand2(), getType(), getCalculationType(), pRepresentedLocation);
  }

  @Override
  public SymbolicExpression copyForState(AbstractState pCurrentState) {
    return new EqualsExpression(
        getOperand1(), getOperand2(), getType(), getCalculationType(), pCurrentState);
  }

  @Override
  public <VisitorReturnT> VisitorReturnT accept(SymbolicValueVisitor<VisitorReturnT> pVisitor) {
    return pVisitor.visit(this);
  }

  @Override
  public String getOperationString() {
    return "==";
  }
}

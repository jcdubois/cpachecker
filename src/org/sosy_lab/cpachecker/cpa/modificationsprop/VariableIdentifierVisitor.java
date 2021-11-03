// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cpa.modificationsprop;

import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.Set;
import org.sosy_lab.cpachecker.cfa.ast.c.CArraySubscriptExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CCastExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CComplexCastExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFieldReference;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CLeftHandSideVisitor;
import org.sosy_lab.cpachecker.cfa.ast.c.CPointerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CRightHandSideVisitor;
import org.sosy_lab.cpachecker.cfa.ast.c.CUnaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.DefaultCExpressionVisitor;

/** Provides a set of variable names used. */
public class VariableIdentifierVisitor
    extends DefaultCExpressionVisitor<Set<String>, PointerAccessException>
    implements CRightHandSideVisitor<Set<String>, PointerAccessException>,
        CLeftHandSideVisitor<Set<String>, PointerAccessException> {

  private final boolean strict;

  public VariableIdentifierVisitor(boolean pStrict) {
    strict = pStrict;
  }

  @Override
  public Set<String> visit(CFunctionCallExpression pIastFunctionCallExpression)
      throws PointerAccessException {
    Set<String> resultSet = new HashSet<>();
    for (CExpression exp : pIastFunctionCallExpression.getParameterExpressions()) {
      resultSet.addAll(exp.accept(this));
    }
    return resultSet;
  }

  @Override
  protected Set<String> visitDefault(final CExpression pExp) throws PointerAccessException {
    return new HashSet<>();
  }

  // We leave this exception-less for now, as we usually do not expect problems here.
  @Override
  public Set<String> visit(final CArraySubscriptExpression pE) throws PointerAccessException {
    Set<String> resultSet = pE.getArrayExpression().accept(this);
    resultSet.addAll(pE.getSubscriptExpression().accept(this));
    return resultSet;
  }

  @Override
  public Set<String> visit(final CBinaryExpression pE) throws PointerAccessException {
    Set<String> resultSet = pE.getOperand1().accept(this);
    resultSet.addAll(pE.getOperand2().accept(this));
    return resultSet;
  }

  @Override
  public Set<String> visit(final CCastExpression pE) throws PointerAccessException {
    return pE.getOperand().accept(this);
  }

  @Override
  public Set<String> visit(final CComplexCastExpression pE) throws PointerAccessException {
    return pE.getOperand().accept(this);
  }

  @Override
  public Set<String> visit(final CFieldReference pE) throws PointerAccessException {
    if (strict) {
      throw new PointerAccessException();
    } else {
      return pE.getFieldOwner().accept(this);
    }
  }

  @Override
  public Set<String> visit(final CIdExpression pE) throws PointerAccessException {
    return Sets.newHashSet(pE.getDeclaration().getQualifiedName());
  }

  @Override
  public Set<String> visit(final CUnaryExpression pE) throws PointerAccessException {
    return pE.getOperand().accept(this);
  }

  @Override
  public Set<String> visit(final CPointerExpression pE) throws PointerAccessException {
    if (strict) {
      throw new PointerAccessException();
    } else {
      return pE.getOperand().accept(this);
    }
  }
}

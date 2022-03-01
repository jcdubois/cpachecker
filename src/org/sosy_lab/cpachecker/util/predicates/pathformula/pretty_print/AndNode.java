// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.predicates.pathformula.pretty_print;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;

class AndNode implements FormulaNode {

  private FormulaNode left;
  private FormulaNode right;

  void setLeft(FormulaNode pLeft) {
    left = pLeft;
  }

  void setRight(FormulaNode pRight) {
    right = pRight;
  }

  public FormulaNode getLeft() {
    return left;
  }

  public FormulaNode getRight() {
    return right;
  }

  @Override
  public String toString() {
    return left + " ∧ " + right;
  }

  @Override
  public boolean equals(Object pO) {
    if (!(pO instanceof AndNode)) {
      return false;
    }
    AndNode andNode = (AndNode) pO;
    return Objects.equals(left, andNode.left) &&
        Objects.equals(right, andNode.right);
  }

  @Override
  public int hashCode() {
    return Objects.hash(left, right, AndNode.class);
  }

  @Override
  public boolean logicallyEquivalentTo(FormulaNode node) {
    if (node instanceof AndNode) {
      AndNode andNode = (AndNode) node;
      if ((andNode.left.logicallyEquivalentTo(left) && andNode.right.logicallyEquivalentTo(right)) ||
          (andNode.left.logicallyEquivalentTo(right) && andNode.right.logicallyEquivalentTo(left))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public List<FormulaNode> getSuccessors() {
    return ImmutableList.of(left,right);
  }

  @Override
  public FormulaNodeType getType() {
    return FormulaNodeType.AndNode;
  }
}

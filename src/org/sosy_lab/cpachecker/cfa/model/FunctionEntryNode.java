// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.model;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.AVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;

public abstract class FunctionEntryNode extends CFANode {

  private static final long serialVersionUID = 1837494813423960670L;
  private final FileLocation location;
  private final AFunctionDeclaration functionDefinition;
  private final @Nullable AVariableDeclaration returnVariable;

  // Check if call edges are added in the second pass
  // Some function entry nodes do not have a corresponding function exit node that is also part of
  // the CFA. If a function never returns, because it always aborts the program or always executes
  // an infinite loop, the CFA doesn't contain an exit node for the function. If this is the case,
  // this field is null.
  private @Nullable FunctionExitNode exitNode;

  protected FunctionEntryNode(
      final FileLocation pFileLocation,
      @Nullable FunctionExitNode pExitNode,
      final AFunctionDeclaration pFunctionDefinition,
      final Optional<? extends AVariableDeclaration> pReturnVariable) {

    super(pFunctionDefinition);
    location = checkNotNull(pFileLocation);
    functionDefinition = pFunctionDefinition;
    exitNode = pExitNode;
    returnVariable = pReturnVariable.orElse(null);
  }

  public FileLocation getFileLocation() {
    return location;
  }

  /**
   * Returns an optional containing the corresponding function exit node for this entry node, if it
   * exists.
   *
   * <p>Some function entry nodes do not have a corresponding function exit node. If a function
   * never returns, because it always aborts the program or always executes an infinite loop, the
   * CFA doesn't contain an exit node for the function.
   *
   * @return If this function entry node has a corresponding function exit node, an optional
   *     containing the function exit node is returned. Otherwise, if the function entry node does
   *     not have a corresponding function exit node, {@code Optional.empty()} is returned.
   */
  public Optional<FunctionExitNode> getExitNode() {
    return Optional.ofNullable(exitNode);
  }

  /**
   * Removes the corresponding function exit node from this entry node.
   *
   * <p>Only call this method if the function exit node isn't part of the CFA. Do not call this
   * method outside CFA construction.
   */
  public void removeExitNode() {
    exitNode = null;
  }

  public AFunctionDeclaration getFunctionDefinition() {
    return functionDefinition;
  }

  public List<String> getFunctionParameterNames() {
    return Lists.transform(functionDefinition.getParameters(), AParameterDeclaration::getName);
  }

  public abstract List<? extends AParameterDeclaration> getFunctionParameters();

  /**
   * Return a declaration for a pseudo variable that can be used to store the return value of this
   * function (if it has one). This variable is the same as the one used by {@link
   * AReturnStatement#asAssignment()}.
   */
  public Optional<? extends AVariableDeclaration> getReturnVariable() {
    return Optional.ofNullable(returnVariable);
  }
}

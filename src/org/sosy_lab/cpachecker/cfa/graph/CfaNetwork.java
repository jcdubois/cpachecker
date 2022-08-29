// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.graph;

import com.google.common.graph.Network;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionReturnEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionSummaryEdge;
import org.sosy_lab.cpachecker.util.CFAUtils;

/**
 * Represents a {@link CFA} as a {@link Network}.
 *
 * <p>All connections between CFA nodes and/or edges are defined by a {@code CfaNetwork} and may
 * differ from the connections represented by its components (e.g., {@link CFAEdge#getSuccessor()},
 * {@link CFAUtils#allEnteringEdges(CFANode)}, {@link FunctionCallEdge#getSummaryEdge()}, {@link
 * FunctionEntryNode#getExitNode()}, etc). It's important to use methods provided by {@link
 * CfaNetwork}, if more than a single CFA node and/or edge is involved. For example, one should use
 * {@link CfaNetwork#outEdges(CFANode)} instead of {@link CFAUtils#allLeavingEdges(CFANode)} and
 * {@link #getFunctionSummaryEdge(FunctionCallEdge)} instead of {@link
 * FunctionCallEdge#getSummaryEdge()}.
 *
 * <p>For performance reasons, not all {@link CfaNetwork} implementations check whether CFA nodes
 * and edges given as method arguments actually belong to the CFA represented by a {@code
 * CfaNetwork}.
 *
 * <p>All returned sets are unmodifiable views, so attempts to modify such a set will throw an
 * exception, but modifications to the CFA represented by a {@code CfaNetwork} will be reflected in
 * the set. Don't try to modify the CFA represented by a {@code CfaNetwork} while iterating though
 * such a view as correctness of the iteration cannot be guaranteed anymore.
 */
public interface CfaNetwork extends Network<CFANode, CFAEdge> {

  /**
   * Returns a {@link CfaNetwork} view that represents the specified {@link CFA} as a {@link
   * Network}.
   *
   * <p>IMPORTANT: The specified CFA must not contain any parallel edges (i.e., edges that connect
   * the same nodes in the same order) and never add them in the future (if the CFA is mutable).
   * Additionally, the set returned by {@link CFA#getAllNodes()} must not contain any duplicates and
   * never add them in the future (if the CFA is mutable). Be aware that these requirements are not
   * enforced, so violating them may lead to unexpected results.
   *
   * @param pCfa the CFA to create a {@link CfaNetwork} view for
   * @return a {@link CfaNetwork} view that represents the specified {@link CFA} as a {@link
   *     Network}
   * @throws NullPointerException if {@code pCfa == null}
   */
  public static CfaNetwork wrap(CFA pCfa) {
    return new WrappingCfaNetwork(pCfa);
  }

  public static CfaNetwork filterEdges(CfaNetwork pNetwork, Predicate<CFAEdge> pFilter) {
    return new EdgeFilteringCfaNetwork(pNetwork, pFilter);
  }

  public static CfaNetwork of(CFA pCfa, Predicate<CFAEdge> pFilter) {
    return filterEdges(wrap(pCfa), pFilter);
  }

  public static CfaNetwork transformEdges(
      CfaNetwork pNetwork, Function<CFAEdge, CFAEdge> pTransformer) {
    return new EdgeTransformingCfaNetwork(pNetwork, pTransformer);
  }

  public static CfaNetwork of(CFA pCfa, Set<String> pFunctions) {
    return new FunctionFilteringCfaNetwork(pCfa, pFunctions);
  }

  public static CfaNetwork of(FunctionEntryNode pFunctionEntryNode) {
    return new SingleFunctionCfaNetwork(pFunctionEntryNode);
  }

  /**
   * Returns the predecessor of the specified CFA edge.
   *
   * @param pEdge the CFA edge to get the predecessor for
   * @return the predecessor of the specified CFA edge
   * @throws NullPointerException if {@code pEdge == null}
   */
  CFANode predecessor(CFAEdge pEdge);

  /**
   * Returns the successor of the specified CFA edge.
   *
   * @param pEdge the CFA edge to get the successor for
   * @return the successor of the specified CFA edge
   * @throws NullPointerException if {@code pEdge == null}
   */
  CFANode successor(CFAEdge pEdge);

  Optional<FunctionExitNode> getFunctionExitNode(FunctionEntryNode pFunctionEntryNode);

  FunctionSummaryEdge getFunctionSummaryEdge(FunctionCallEdge pFunctionCallEdge);

  FunctionSummaryEdge getFunctionSummaryEdge(FunctionReturnEdge pFunctionReturnEdge);

  FunctionEntryNode getFunctionEntryNode(FunctionSummaryEdge pFunctionSummaryEdge);
}

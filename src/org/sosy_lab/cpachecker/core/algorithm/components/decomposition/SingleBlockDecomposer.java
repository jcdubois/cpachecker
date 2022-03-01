// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components.decomposition;

import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Set;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.BlockNode.BlockNodeFactory;
import org.sosy_lab.cpachecker.util.CFAUtils;

public class SingleBlockDecomposer implements CFADecomposer {

  @Override
  public BlockTree cut(CFA cfa) {
    BlockNodeFactory factory = new BlockNodeFactory(cfa);
    CFANode startNode = cfa.getMainFunction();
    // we do not get error conditions
    CFANode lastNode = CFANode.newDummyCFANode();
    Set<CFAEdge> edges = new HashSet<>();
    for (CFANode allNode : cfa.getAllNodes()) {
      CFAUtils.leavingEdges(allNode).copyInto(edges);
      CFAUtils.enteringEdges(allNode).copyInto(edges);
    }
    Set<CFANode> nodes = new HashSet<>(cfa.getAllNodes());
    nodes.add(lastNode);
    BlockNode root = factory.makeBlock(startNode, startNode, ImmutableSet.of(startNode), ImmutableSet.of());
    BlockNode workerBlock = factory.makeBlock(startNode, lastNode, nodes, edges);
    factory.linkSuccessor(root, workerBlock);
    return new BlockTree(root, factory);
  }

}

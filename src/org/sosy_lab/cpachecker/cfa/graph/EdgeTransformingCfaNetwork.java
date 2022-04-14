// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.graph;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Iterators;
import com.google.common.graph.EndpointPair;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Function;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.util.UnmodifiableSetView;

final class EdgeTransformingCfaNetwork implements CfaNetwork {

  private final CfaNetwork delegate;
  private final Function<CFAEdge, CFAEdge> transformer;

  EdgeTransformingCfaNetwork(CfaNetwork pDelegate, Function<CFAEdge, CFAEdge> pTransformer) {

    delegate = checkNotNull(pDelegate);
    transformer = checkNotNull(pTransformer);
  }

  @Override
  public Set<CFAEdge> inEdges(CFANode pNode) {
    return new UnmodifiableSetView<>() {

      @Override
      public Iterator<CFAEdge> iterator() {
        return Iterators.transform(delegate.inEdges(pNode).iterator(), transformer::apply);
      }
    };
  }

  @Override
  public Set<CFAEdge> outEdges(CFANode pNode) {
    return new UnmodifiableSetView<>() {

      @Override
      public Iterator<CFAEdge> iterator() {
        return Iterators.transform(delegate.outEdges(pNode).iterator(), transformer::apply);
      }
    };
  }

  @Override
  public EndpointPair<CFANode> incidentNodes(CFAEdge pEdge) {
    return delegate.incidentNodes(pEdge);
  }

  @Override
  public Set<CFANode> nodes() {
    return delegate.nodes();
  }
}

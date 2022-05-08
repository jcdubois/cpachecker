// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.graph.dominance;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A data structure containing dominance frontiers for all nodes in a graph.
 *
 * @param <T> the node-type of the original graph.
 */
public final class DomFrontiers<T> {

  private final DomInput<T> input;
  private final Frontier[] frontiers;

  DomFrontiers(DomInput<T> pInput, Frontier[] pFrontiers) {
    input = pInput;
    frontiers = pFrontiers;
  }

  private Set<T> getFrontier(int pId) {

    Frontier frontier = frontiers[pId];
    Set<T> nodeSet = new HashSet<>();

    for (int id : frontier.getSet()) {
      nodeSet.add(input.getNodeForReversePostOrderId(id));
    }

    return Collections.unmodifiableSet(nodeSet);
  }

  /**
   * Returns the dominance frontier for the specified node.
   *
   * @param pNode the node to get the dominance frontier for.
   * @return the dominance frontier for the specified node.
   * @throws NullPointerException if {@code pNode} is {@code null}.
   * @throws IllegalArgumentException if {@code pNode} was not part of the original graph during
   *     graph traversal in {@link Dominance#createDomTree}.
   */
  public Set<T> getFrontier(T pNode) {

    Objects.requireNonNull(pNode, "pNode must not be null");

    Integer id = input.getReversePostOrderId(pNode);

    if (id == null) {
      throw new IllegalArgumentException("unknown node: " + pNode);
    }

    return getFrontier(id);
  }

  /**
   * Returns the iterated dominance frontier for the specified set of nodes.
   *
   * @param pNodes the set of nodes to get the iterated dominance frontier for.
   * @return an unmodifiable set consisting of all nodes in the iterated dominance frontier.
   * @throws NullPointerException if {@code pNodes} is {@code null}.
   * @throws IllegalArgumentException if {@code pNodes} contains a node that was not part of the
   *     original graph during graph traversal in {@link Dominance#createDomTree}.
   */
  public Set<T> getIteratedFrontier(Set<T> pNodes) {

    Objects.requireNonNull(pNodes, "pNodes must not be null");

    Set<T> frontier = new HashSet<>();
    Set<Integer> seen = new HashSet<>(); // a node is in seen if it is or has been in the waitlist
    Deque<Integer> waitlist = new ArrayDeque<>();

    for (T node : pNodes) {

      Integer id = input.getReversePostOrderId(node);

      if (id == null) {
        throw new IllegalArgumentException(
            "pNodes contains node that has no dominance frontier: " + node);
      }

      waitlist.add(id);
      seen.add(id);
    }

    while (!waitlist.isEmpty()) {

      int removed = waitlist.remove();

      for (int id : frontiers[removed].getSet()) {
        if (frontier.add(input.getNodeForReversePostOrderId(id))) {
          if (seen.add(id)) { // if not previously seen -> add to waitlist
            waitlist.add(id);
          }
        }
      }
    }

    return Collections.unmodifiableSet(frontier);
  }

  @Override
  public String toString() {
    return Arrays.toString(frontiers);
  }

  static final class Frontier {

    private Set<Integer> set;

    Frontier() {
      set = new HashSet<>();
    }

    private Set<Integer> getSet() {
      return set;
    }

    void add(int pId) {
      set.add(pId);
    }

    @Override
    public String toString() {
      return set.toString();
    }
  }
}

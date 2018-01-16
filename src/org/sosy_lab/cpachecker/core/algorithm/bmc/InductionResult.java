/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2017  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core.algorithm.bmc;

import ap.Prover.ProofResult;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.sosy_lab.java_smt.api.BooleanFormula;

public class InductionResult<T extends CandidateInvariant> extends ProofResult {

  private final @Nullable T invariantAbstraction;

  private final Set<SingleLocationFormulaInvariant> model;

  private final @Nullable BooleanFormula inputAssignments;

  private final int k;

  private InductionResult(T pInvariantAbstraction) {
    invariantAbstraction = Objects.requireNonNull(pInvariantAbstraction);
    model = Collections.emptySet();
    inputAssignments = null;
    k = -1;
  }

  private InductionResult(
      Set<? extends SingleLocationFormulaInvariant> pModel,
      BooleanFormula pInputAssignments,
      int pK) {
    if (pModel.isEmpty()) {
      throw new IllegalArgumentException(
          "A model should be present if (and only if) induction failed.");
    }
    if (pK < 0) {
      throw new IllegalArgumentException(
          "k must not be negative for failed induction results, but is " + pK);
    }
    invariantAbstraction = null;
    model = ImmutableSet.copyOf(pModel);
    inputAssignments = pInputAssignments;
    k = pK;
  }

  public boolean isSuccessful() {
    return invariantAbstraction != null;
  }

  public T getInvariantAbstraction() {
    if (!isSuccessful()) {
      throw new IllegalArgumentException(
          "An invariant abstraction is only present if induction succeeded.");
    }
    return invariantAbstraction;
  }

  public Set<SingleLocationFormulaInvariant> getModel() {
    if (isSuccessful()) {
      throw new IllegalStateException("A model is only present if induction failed.");
    }
    assert !model.isEmpty();
    return model;
  }

  public BooleanFormula getInputAssignments() {
    if (isSuccessful()) {
      throw new IllegalStateException("Input assignments are only present if induction failed.");
    }
    assert inputAssignments != null;
    return inputAssignments;
  }

  public int getK() {
    if (isSuccessful()) {
      throw new IllegalStateException(
          "Input-assignment length is only present if induction failed.");
    }
    return k;
  }

  public static <T extends CandidateInvariant> InductionResult<T> getSuccessful(
      T pInvariantAbstraction) {
    return new InductionResult<>(pInvariantAbstraction);
  }

  public static <T extends CandidateInvariant> InductionResult<T> getFailed(
      Set<? extends SingleLocationFormulaInvariant> pModel,
      BooleanFormula pInputAssignments,
      int pK) {
    return new InductionResult<>(pModel, pInputAssignments, pK);
  }
}